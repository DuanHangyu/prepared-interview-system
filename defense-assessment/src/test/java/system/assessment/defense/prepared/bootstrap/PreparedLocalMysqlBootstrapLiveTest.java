package system.assessment.defense.prepared.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real fresh MySQL, full Flyway history and real permission rejection. No cloud calls. */
@EnabledIfEnvironmentVariable(named = "PREPARED_MYSQL_BOOTSTRAP_LIVE", matches = "true")
class PreparedLocalMysqlBootstrapLiveTest {
    // Explicit current migration contract; never infer a count from whatever ran.
    private static final int LATEST_VERSION=37;
    @Test void freshDatabaseMigratesWithSeparateIdentityAndRuntimeDenialsSurviveRestart() throws Exception {
        Path artifacts = Path.of("../.runtime").toRealPath();
        // macOS Unix sockets have a short path limit; preserve a unique owned fixture
        // without Java's 19-digit temporary suffix (no redirection to global data).
        Path root = Files.createDirectory(artifacts.resolve("mi-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
        Path base = Path.of(System.getenv("PREPARED_MYSQL_BASEDIR")).toRealPath();
        int port;
        try (ServerSocket available = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { port = available.getLocalPort(); }
        byte[] random = new byte[36]; new SecureRandom().nextBytes(random);
        String appPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String schema = "prepared_interview_bootstrap";
        String url = "jdbc:mysql://127.0.0.1:" + port + "/" + schema + "?sslMode=REQUIRED&connectionTimeZone=Asia%2FShanghai";
        Map<String, String> env = new HashMap<>(Map.of("PREPARED_MYSQL_BASEDIR", base.toString(),
                "PREPARED_MYSQL_PORT", Integer.toString(port), "PREPARED_DB_USERNAME", "prepared_app",
                "PREPARED_DB_PASSWORD", appPassword, "PREPARED_DB_JDBC_URL", url));
        Files.writeString(root.resolve(".env.local"), "SPRING_PROFILE='prepared-local'\nPREPARED_MYSQL_BASEDIR='" + base
                + "'\nPREPARED_MYSQL_PORT='" + port + "'\nPREPARED_DB_JDBC_URL='" + url
                + "'\nJAVA_HOME='" + System.getProperty("java.home") + "'\nPREPARED_DB_USERNAME='prepared_app'\nPREPARED_DB_PASSWORD='" + appPassword + "'\n");
        Files.setPosixFilePermissions(root.resolve(".env.local"), PosixFilePermissions.fromString("rw-------"));
        var result = PreparedLocalMysqlBootstrap.initialize(root, env);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(result.adminConfiguration()));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(result.migrationConfiguration()));
        String adminPassword = password(result.adminConfiguration());
        String migrationPassword = password(result.migrationConfiguration());
        assertFalse(appPassword.equals(adminPassword), "Administrator and application credentials must differ");
        assertFalse(appPassword.equals(migrationPassword), "Migration and application credentials must differ");
        assertFalse(adminPassword.equals(migrationPassword), "Administrator and migration credentials must differ");
        assertFalse(Files.exists(root.resolve(".runtime/mysql-init.sql")));
        assertFalse(Files.exists(root.resolve(".runtime/mysql-bootstrap.lock")));
        assertEquals("prepared-local-mysql-v1\n", Files.readString(root.resolve(".runtime/mysql-bootstrap.complete")));
        Files.createDirectory(root.resolve("scripts"));
        for (String script : List.of("start-local-mysql.sh", "prepared-local-config.sh", "migrate-local-mysql.sh", "start-backend.sh", "start-db-tunnel.sh")) {
            Path destination = root.resolve("scripts/" + script);
            Files.copy(Path.of("../scripts/" + script), destination);
            Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rwx------"));
        }
        copyBackendForRealBuild(root);
        // One fresh instance, three genuinely stopped/restarted generations.
        for (int round = 0; round < 3; round++) {
            Path serverOutput = root.resolve(".runtime/normal-" + round + ".log");
            Files.writeString(serverOutput, "");
            Files.setPosixFilePermissions(serverOutput, PosixFilePermissions.fromString("rw-------"));
            List<String> serverArgs = List.of("/bin/bash", root.resolve("scripts/start-local-mysql.sh").toString());
            ProcessBuilder builder = new ProcessBuilder(serverArgs).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"); builder.environment().put("LC_ALL", "C");
            Process daemon = builder.start();
            try {
                awaitRuntime(url, appPassword, daemon);
                if (round == 0) runRealMigrationEntry(root);
                assertEquals(0, PreparedLocalMysqlMigration.migrate(root, env));
                assertFalse(Files.exists(root.resolve(".runtime/mysql-migrate.lock")));
                try (Connection migrator = DriverManager.getConnection(url, "prepared_migrator", migrationPassword)) {
                    assertEquals(1, scalar(migrator, "SELECT @@partial_revokes"));
                    assertThrows(SQLException.class, () -> execute(migrator, "CREATE DATABASE foreign_schema"));
                    assertThrows(SQLException.class, () -> execute(migrator, "SELECT * FROM mysql.user"));
                    if (round == 0) {
                        execute(migrator, "CREATE TABLE bootstrap_role_probe (id INT PRIMARY KEY, value_text VARCHAR(32))");
                        rootQuery(base, result.adminConfiguration(), "CREATE DATABASE preparedxinterview_bootstrap; CREATE TABLE preparedxinterview_bootstrap.probe(id INT)");
                        verifyHistoryFailureProtection(root, env, migrator);
                    }
                    assertThrows(SQLException.class, () -> execute(migrator, "SELECT * FROM preparedxinterview_bootstrap.probe"));
                }
                try (Connection app = DriverManager.getConnection(url, "prepared_app", appPassword)) {
                    assertEquals(LATEST_VERSION, scalar(app, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1"));
                    execute(app, "INSERT INTO bootstrap_role_probe VALUES (" + (round + 1) + ",'original')");
                    execute(app, "INSERT INTO bootstrap_role_probe VALUES (" + (round + 1000) + ",'disposable')");
                    execute(app, "DELETE FROM bootstrap_role_probe WHERE id=" + (round + 1000));
                    assertEquals(round + 1, scalar(app, "SELECT COUNT(*) FROM bootstrap_role_probe"));
                    execute(app, "UPDATE bootstrap_role_probe SET value_text='retained' WHERE id=" + (round + 1));
                    assertThrows(SQLException.class, () -> execute(app, "CREATE TABLE forbidden(id INT)"));
                    assertThrows(SQLException.class, () -> execute(app, "ALTER TABLE bootstrap_role_probe ADD COLUMN forbidden INT"));
                    assertThrows(SQLException.class, () -> execute(app, "DROP TABLE bootstrap_role_probe"));
                    assertThrows(SQLException.class, () -> execute(app, "CREATE DATABASE forbidden"));
                    assertThrows(SQLException.class, () -> execute(app, "SELECT * FROM mysql.user"));
                    assertThrows(SQLException.class, () -> execute(app, "SELECT * FROM preparedxinterview_bootstrap.probe"));
                    assertThrows(SQLException.class, () -> execute(app, "CREATE USER 'forbidden'@'localhost' IDENTIFIED BY 'irrelevant-fixture'"));
                    assertThrows(SQLException.class, () -> execute(app, "CREATE PROCEDURE forbidden() SELECT 1"));
                    assertThrows(SQLException.class, () -> execute(app, "GRANT SELECT ON prepared_interview_bootstrap.* TO 'prepared_migrator'@'127.0.0.1'"));
                }
                verifyRealRuntimeApplication(root, round);
                rootQuery(base, result.adminConfiguration(), "SHUTDOWN");
                assertTrue(daemon.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, daemon.exitValue());
            } finally {
                if (daemon.isAlive()) { daemon.destroy(); if (!daemon.waitFor(15, TimeUnit.SECONDS)) daemon.destroyForcibly(); }
            }
        }
        // Reinvoking a fresh initializer cannot rotate or reset this database.
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.initialize(root, env));
        assertTrue(adminPassword.equals(password(result.adminConfiguration())), "Existing administrator credential must remain unchanged");
        // Keep these explicitly labelled, owner-only artifacts for failure diagnosis; no deletion/reset.
    }

    private static void awaitRuntime(String url, String password, Process daemon) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (daemon.isAlive() && System.nanoTime() < deadline) {
            try (Connection connection = DriverManager.getConnection(url, "prepared_app", password)) {
                if (scalar(connection, "SELECT 1") == 1) return;
            } catch (SQLException ignored) { Thread.sleep(200); }
        }
        fail("Fresh isolated runtime did not become ready; inspect owner-only fixture diagnostics.");
    }

    private static String password(Path config) throws Exception {
        return Files.readAllLines(config).stream().filter(line -> line.startsWith("password=\"")).findFirst().orElseThrow()
                .substring("password=\"".length()).replaceFirst("\"$", "");
    }

    private static int scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getInt(1); }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static void rootQuery(Path base, Path config, String sql) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(base.resolve("bin/mysql").toString(), "--defaults-file=" + config,
                "--no-login-paths", "--connect-timeout=2", "--batch", "-e", sql);
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
        Process command = builder.start();
        try { assertTrue(command.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, command.exitValue()); }
        finally { if (command.isAlive()) command.destroyForcibly(); }
    }

    private static void copyBackendForRealBuild(Path root) throws Exception {
        Path backend = root.resolve("defense-assessment"); Files.createDirectory(backend);
        for (String path : List.of("pom.xml", "mvnw", ".mvn/wrapper/maven-wrapper.properties")) {
            Path destination = backend.resolve(path); Files.createDirectories(destination.getParent());
            Files.copy(Path.of(path), destination);
        }
        Files.setPosixFilePermissions(backend.resolve("mvnw"), PosixFilePermissions.fromString("rwx------"));
        Path sources = Path.of("src/main");
        try (var paths = Files.walk(sources)) {
            for (Path path : paths.toList()) {
                assertFalse(Files.isSymbolicLink(path), "Only real new-project source files enter the isolated build fixture");
                Path destination = backend.resolve("src/main").resolve(sources.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }

    private static void runRealMigrationEntry(Path root) throws Exception {
        Path log = root.resolve(".runtime/migration-entry.log");
        Files.writeString(log, ""); Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", root.resolve("scripts/migrate-local-mysql.sh").toString());
        builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
        builder.environment().put("HOME", System.getenv("HOME")); // preserve the actual user/cache home
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process command = builder.start();
        try {
            assertTrue(command.waitFor(180, TimeUnit.SECONDS), "Real isolated build/migration entry must finish");
            assertEquals(0, command.exitValue(), "Inspect owner-only migration-entry.log; never dump credential-bearing diagnostics");
        } finally { if (command.isAlive()) command.destroyForcibly(); }
    }

    private static void verifyHistoryFailureProtection(Path root, Map<String, String> env, Connection migrator) throws Exception {
        int original;
        try (var statement = migrator.prepareStatement("SELECT checksum FROM flyway_schema_history WHERE version='1'"); var rows = statement.executeQuery()) {
            assertTrue(rows.next()); original = rows.getInt(1); assertFalse(rows.wasNull());
        }
        try (var statement = migrator.prepareStatement("UPDATE flyway_schema_history SET checksum=? WHERE version='1'")) {
            statement.setInt(1, original ^ 1); assertEquals(1, statement.executeUpdate());
        }
        // Fault injection changes only this test's own history row, never migration sources
        // or current/old environments. Production must refuse rather than repair/clean.
        assertThrows(org.flywaydb.core.api.FlywayException.class, () -> PreparedLocalMysqlMigration.migrate(root, env));
        Path lock = root.resolve(".runtime/mysql-migrate.lock"); assertTrue(Files.isDirectory(lock));
        assertEquals(LATEST_VERSION, scalar(migrator, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1"));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> PreparedLocalMysqlMigration.migrate(root, env));
        // Explicitly restore the fixture's injected value ourselves, not an application
        // auto-repair capability. Release only the empty operation lock owned by this test.
        try (var statement = migrator.prepareStatement("UPDATE flyway_schema_history SET checksum=? WHERE version='1'")) {
            statement.setInt(1, original); assertEquals(1, statement.executeUpdate());
        }
        Files.delete(lock);
        assertEquals(0, PreparedLocalMysqlMigration.migrate(root, env));
    }

    private static void verifyRealRuntimeApplication(Path root, int round) throws Exception {
        int port;
        try (ServerSocket available = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { port = available.getLocalPort(); }
        Path log = root.resolve(".runtime/runtime-application-" + round + ".log");
        Files.writeString(log, ""); Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", root.resolve("scripts/start-backend.sh").toString());
        builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
        builder.environment().put("HOME", System.getenv("HOME")); // original Maven cache, never another project/home
        builder.environment().put("PREPARED_BACKEND_PORT", Integer.toString(port));
        // Health-only uses the already-owned NEW local Redis, never old data or cloud.
        builder.environment().put("PREPARED_REDIS_HOST", "127.0.0.1"); builder.environment().put("PREPARED_REDIS_PORT", "16389");
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process application = builder.start();
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            boolean ready = false;
            while (application.isAlive() && System.nanoTime() < deadline) {
                try {
                    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
                            .timeout(java.time.Duration.ofSeconds(3)).GET().build();
                    var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"")) { ready = true; break; }
                } catch (java.io.IOException ignored) { /* listener has not started */ }
                Thread.sleep(300);
            }
            assertTrue(ready, "Inspect the isolated owner-only runtime-application log; never dump credentials");
            var anonymous = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/backend/prepared-papers/999/draft"))
                    .timeout(java.time.Duration.ofSeconds(3)).GET().build();
            assertEquals(401, client.send(anonymous, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        } finally {
            // Stop only the exact Maven/Java process tree created by this invocation.
            // No port-based kill, discovery of current/old app PIDs or global cleanup.
            List<ProcessHandle> descendants = application.descendants().toList();
            for (ProcessHandle child : descendants.reversed()) if (child.isAlive()) child.destroy();
            application.destroy();
            if (!application.waitFor(15, TimeUnit.SECONDS)) application.destroyForcibly();
            for (ProcessHandle child : descendants) if (child.isAlive()) child.destroyForcibly();
        }
    }
}
