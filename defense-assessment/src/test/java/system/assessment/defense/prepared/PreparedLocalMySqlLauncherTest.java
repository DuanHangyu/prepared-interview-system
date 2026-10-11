package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Only isolated shell fixtures: no real daemon, credentials or host port changes. */
class PreparedLocalMySqlLauncherTest {
    @TempDir Path fixture;

    @Test void missingNewConfigurationIsRejectedBeforeAnyDatabaseContact() throws Exception {
        prepare();
        Result result = run(Map.of());
        assertNotEquals(0, result.exit(), result.output());
        assertFalse(Files.exists(fixture.resolve("contact")));
    }

    @Test void foreignEnvironmentFileIsRejectedBeforeAnyDatabaseContact() throws Exception {
        prepare(); config();
        Path outside = Files.createTempFile("prepared-mysql-outside-", ".env");
        try {
            Result result = run(Map.of("ENV_FILE", outside.toString()));
            assertNotEquals(0, result.exit(), result.output());
            assertFalse(Files.exists(fixture.resolve("contact")));
        } finally { Files.deleteIfExists(outside); }
    }

    @Test void anUninitializedNewDirectoryCannotFallBackToTheOldGlobalDatabase() throws Exception {
        prepare(); config();
        Files.delete(fixture.resolve(".runtime/mysql/mysql.ibd"));
        Result result = run(Map.of());
        assertNotEquals(0, result.exit(), result.output());
        assertFalse(Files.exists(fixture.resolve("contact")));
        assertFalse(Files.exists(fixture.resolve("launched")));
    }

    @Test void aRuntimeSymbolicLinkCannotReachAnExternalDatabase() throws Exception {
        prepare(); config();
        Path data = fixture.resolve(".runtime");
        Path moved = fixture.resolve("external-runtime");
        Files.move(data, moved);
        Files.createSymbolicLink(data, moved);
        Result result = run(Map.of());
        assertNotEquals(0, result.exit(), result.output());
        assertFalse(Files.exists(fixture.resolve("contact")));
    }

    @Test void aForeignListenerIsNeverProbedStoppedOrReplaced() throws Exception {
        prepare(); config();
        executable(fixture.resolve("bin/lsof"), "#!/bin/bash\nprintf '999999\\n'\n");
        Result result = run(Map.of());
        assertNotEquals(0, result.exit(), result.output());
        assertFalse(Files.exists(fixture.resolve("contact")));
        assertFalse(Files.exists(fixture.resolve("launched")));
    }

    @Test void ownedLiveDatabaseMustMatchPidCommandPortAndCanonicalDataDirectory() throws Exception {
        prepare(); config();
        Result result = run(Map.of());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("13317"), result.output());
        assertTrue(Files.exists(fixture.resolve("contact")));
        assertFalse(Files.exists(fixture.resolve("launched")));
        assertFalse(result.output().contains("defense123"));
    }

    @Test void authenticatedWrongDataDirectoryDoesNotCountAsTheNewDatabase() throws Exception {
        prepare(); config();
        executable(fixture.resolve("mysql/bin/mysql"), "#!/bin/bash\nprintf 'contact\\n' >> \"$PREPARED_MYSQL_TEST_CONTACT\"\nprintf '13317\\t/external/mysql/\\n'\n");
        Result result = run(Map.of());
        assertNotEquals(0, result.exit(), result.output());
        assertFalse(Files.exists(fixture.resolve("launched")));
    }

    @Test void nativeMysqlBatchEscapingCannotCauseFalseServerIdentityRejection() throws Exception {
        prepare(); config();
        executable(fixture.resolve("mysql/bin/mysql"), "#!/bin/bash\nprintf 'contact\\n' >> \"$PREPARED_MYSQL_TEST_CONTACT\"\n"
                + "if [[ \"$*\" == *'SELECT CONCAT('* ]]; then\n"
                + "  printf '13317\\\\t%s/.runtime/mysql/\\n' \"$PREPARED_MYSQL_TEST_ROOT\"\n"
                + "else\n  printf '13317\\t%s/.runtime/mysql/\\n' \"$PREPARED_MYSQL_TEST_ROOT\"\nfi\n");
        Result result = run(Map.of());
        assertEquals(0, result.exit(), result.output());
        assertFalse(Files.exists(fixture.resolve("launched")));
    }

    @Test void initializedNewServerStartsOnlyOnLoopbackWithoutApplicationSecretsInItsEnvironment() throws Exception {
        prepare(); config();
        Files.delete(fixture.resolve(".runtime/mysql/server.pid"));
        executable(fixture.resolve("bin/lsof"), "#!/bin/bash\nexit 1\n");
        executable(fixture.resolve("mysql/bin/mysqld"), "#!/bin/bash\nprintf '%s\\n' \"$*\"\n"
                + "printf 'CHILD_ENV_DB=%s\\n' \"${PREPARED_DB_PASSWORD:-unset}\"\n"
                + "printf 'CHILD_ENV_MODEL=%s\\n' \"${PREPARED_DASHSCOPE_API_KEY:-unset}\"\n");
        Result result = run(Map.of("PREPARED_DASHSCOPE_API_KEY", "synthetic-never-a-secret"));
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("--no-defaults"), result.output());
        assertTrue(result.output().contains("--datadir=" + fixture.resolve(".runtime/mysql")), result.output());
        assertTrue(result.output().contains("--bind-address=127.0.0.1 --port=13317 --mysqlx=OFF"), result.output());
        assertTrue(result.output().contains("CHILD_ENV_DB=unset"), result.output());
        assertTrue(result.output().contains("CHILD_ENV_MODEL=unset"), result.output());
        assertFalse(Files.exists(fixture.resolve("contact")));
    }

    @Test void freshPolicyAlwaysAppliesLiteralSchemaGrantsOnNormalStartup() throws Exception {
        prepare(); config(); freshPolicy();
        Files.delete(fixture.resolve(".runtime/mysql/server.pid"));
        executable(fixture.resolve("bin/lsof"), "#!/bin/bash\nexit 1\n");
        executable(fixture.resolve("mysql/bin/mysqld"), "#!/bin/bash\nprintf '%s\\n' \"$*\"\n");
        Result result = run(Map.of());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("--partial-revokes=ON"));
    }

    @Test void aRunningFreshServerWithUnsafeGrantModeIsNotAcceptedAsHealthy() throws Exception {
        prepare(); config(); freshPolicy();
        executable(fixture.resolve("mysql/bin/mysql"), "#!/bin/bash\nprintf '13317\\t%s/.runtime/mysql/\\t0\\n' \"$PREPARED_MYSQL_TEST_ROOT\"\n");
        assertNotEquals(0, run(Map.of()).exit());
        assertFalse(Files.exists(fixture.resolve("launched")));
    }

    @Test void aRunningFreshServerRequiresItsVerifiedLiteralGrantMode() throws Exception {
        prepare(); config(); freshPolicy();
        executable(fixture.resolve("mysql/bin/mysql"), "#!/bin/bash\nprintf '13317\\t%s/.runtime/mysql/\\t1\\n' \"$PREPARED_MYSQL_TEST_ROOT\"\n");
        assertEquals(0, run(Map.of()).exit());
        assertFalse(Files.exists(fixture.resolve("launched")));
    }

    @Test void aBrokenFreshPolicyIsRejectedBeforeDatabaseAuthentication() throws Exception {
        prepare(); config(); freshPolicy();
        Files.writeString(fixture.resolve(".runtime/mysql-bootstrap.complete"), "unknown-version\n");
        assertNotEquals(0, run(Map.of()).exit());
        assertFalse(Files.exists(fixture.resolve("contact")));
    }

    private void freshPolicy() throws Exception {
        Path marker = fixture.resolve(".runtime/mysql-bootstrap.complete");
        Files.writeString(marker, "prepared-local-mysql-v1\n");
        Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-------"));
    }

    private void prepare() throws Exception {
        fixture = fixture.toRealPath();
        Files.createDirectories(fixture.resolve("scripts"));
        Files.createDirectories(fixture.resolve("bin"));
        Files.createDirectories(fixture.resolve("mysql/bin"));
        Files.createDirectories(fixture.resolve(".runtime/mysql/mysql"));
        Files.writeString(fixture.resolve(".runtime/mysql/mysql.ibd"), "synthetic initialized marker");
        Files.writeString(fixture.resolve(".runtime/mysql/server.pid"), "424242\n");
        String source = Files.readString(Path.of("../scripts/start-local-mysql.sh"));
        // Redirect the unsafe historical binary path into the fixture BEFORE RED.
        // The fake daemon never starts and the real user HOME is never replaced.
        source = source.replace("BASE=\"$HOME/.local/mysql-8.4.3\"", "BASE=\"$PREPARED_MYSQL_TEST_BASE\"");
        source = source.replace("DATA=\"$HOME/.local/mysql-data\"", "DATA=\"$PREPARED_MYSQL_TEST_ROOT/legacy-data\"");
        executable(fixture.resolve("scripts/start-local-mysql.sh"), source);
        executable(fixture.resolve("scripts/prepared-local-config.sh"), Files.readString(Path.of("../scripts/prepared-local-config.sh")));
        executable(fixture.resolve("mysql/bin/mysqladmin"), "#!/bin/bash\nprintf 'contact\\n' >> \"$PREPARED_MYSQL_TEST_CONTACT\"\n");
        executable(fixture.resolve("mysql/bin/mysql"), "#!/bin/bash\nprintf 'contact\\n' >> \"$PREPARED_MYSQL_TEST_CONTACT\"\nprintf '13317\\t%s/.runtime/mysql/\\n' \"$PREPARED_MYSQL_TEST_ROOT\"\n");
        executable(fixture.resolve("mysql/bin/mysqld"), "#!/bin/bash\nprintf '%s\\n' \"$*\" >> \"$PREPARED_MYSQL_TEST_LAUNCHED\"\n");
        executable(fixture.resolve("bin/lsof"), "#!/bin/bash\nprintf '424242\\n'\n");
        executable(fixture.resolve("bin/ps"), "#!/bin/bash\nprintf '%s/mysql/bin/mysqld --no-defaults --datadir=%s/.runtime/mysql --pid-file=%s/.runtime/mysql/server.pid --bind-address=127.0.0.1 --port=13317\\n' \"$PREPARED_MYSQL_TEST_ROOT\" \"$PREPARED_MYSQL_TEST_ROOT\" \"$PREPARED_MYSQL_TEST_ROOT\"\n");
    }

    private void config() throws Exception {
        Files.writeString(fixture.resolve(".env.local"), "SPRING_PROFILE='prepared-local'\nPREPARED_MYSQL_BASEDIR='"
                + fixture.resolve("mysql") + "'\nPREPARED_DB_JDBC_URL='jdbc:mysql://127.0.0.1:13317/prepared_interview?sslMode=REQUIRED'\n"
                + "PREPARED_DB_USERNAME='synthetic-user'\nPREPARED_DB_PASSWORD='synthetic-value'\n");
    }

    private Result run(Map<String, String> overrides) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", fixture.resolve("scripts/start-local-mysql.sh").toString());
        builder.directory(fixture.toFile()).redirectErrorStream(true);
        Map<String, String> env = builder.environment(); env.clear();
        env.put("PATH", fixture.resolve("bin") + ":/usr/bin:/bin:/usr/sbin:/sbin");
        env.put("PREPARED_MYSQL_TEST_BASE", fixture.resolve("mysql").toString());
        env.put("PREPARED_MYSQL_TEST_ROOT", fixture.toString());
        env.put("PREPARED_MYSQL_TEST_CONTACT", fixture.resolve("contact").toString());
        env.put("PREPARED_MYSQL_TEST_LAUNCHED", fixture.resolve("launched").toString());
        env.putAll(overrides);
        Process process = builder.start();
        boolean completed = process.waitFor(5, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        assertTrue(completed, "isolated fixture must finish without launching a daemon");
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void executable(Path path, String source) throws Exception {
        Files.writeString(path, source);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }

    private record Result(int exit, String output) {}
}
