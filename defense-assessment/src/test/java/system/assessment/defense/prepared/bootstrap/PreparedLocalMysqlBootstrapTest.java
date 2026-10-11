package system.assessment.defense.prepared.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Policy tests only; fake executables must never be contacted during validation. */
class PreparedLocalMysqlBootstrapTest {
    @TempDir Path temporary;
    private static final String PASSWORD = "synthetic_fixture_password_1234567890";

    @Test void acceptsOnlyAnEmptyProjectOwnedTargetWithoutWritingOrPrintingCredentials() throws Exception {
        Map<String, String> env = fixture();
        var plan = PreparedLocalMysqlBootstrap.validate(temporary, env);
        assertEquals(temporary.resolve(".runtime/mysql"), plan.data());
        assertEquals("prepared_interview_fixture", plan.schema());
        assertEquals(14327, plan.port());
        assertFalse(plan.toString().contains(PASSWORD));
        assertFalse(Files.exists(temporary.resolve(".runtime")));
    }

    @Test void existingDatabaseIsRejectedWithoutCreatingOrReplacingAnyCredential() throws Exception {
        Map<String, String> env = fixture();
        Files.createDirectories(temporary.resolve(".runtime/mysql"));
        Path marker = temporary.resolve(".runtime/mysql/keep-me");
        Files.writeString(marker, "existing data");
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.initialize(temporary, env));
        assertEquals("existing data", Files.readString(marker));
        assertFalse(Files.exists(temporary.resolve(".runtime/mysql-admin.cnf")));
    }

    @ParameterizedTest @ValueSource(strings = {".runtime", ".runtime/mysql", ".env.local"})
    void symbolicLinksAreRejectedBeforeAnyProcessOrWrite(String path) throws Exception {
        Map<String, String> env = fixture();
        Path target = temporary.resolve(path);
        if (Files.exists(target)) Files.move(target, temporary.resolve("moved-env"));
        if (target.getParent().equals(temporary.resolve(".runtime"))) {
            Files.createDirectories(target.getParent());
            Files.setPosixFilePermissions(target.getParent(), PosixFilePermissions.fromString("rwx------"));
        }
        Files.createSymbolicLink(target, temporary.resolve("outside"));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
        assertFalse(Files.exists(temporary.resolve("contact")));
    }

    @ParameterizedTest @ValueSource(strings = {".runtime/mysql-admin.cnf", ".runtime/mysql-migration.cnf",
            ".runtime/mysql-init.sql", ".runtime/mysql-bootstrap.lock", ".runtime/mysql-bootstrap.log"})
    void existingControlOrCredentialFilesAreNeverOverwritten(String path) throws Exception {
        Map<String, String> env = fixture();
        Files.createDirectories(temporary.resolve(".runtime"));
        Files.setPosixFilePermissions(temporary.resolve(".runtime"), PosixFilePermissions.fromString("rwx------"));
        Files.writeString(temporary.resolve(path), "preserve");
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
        assertEquals("preserve", Files.readString(temporary.resolve(path)));
    }

    @ParameterizedTest @ValueSource(strings = {"PREPARED_DB_PASSWORD", "PREPARED_DB_USERNAME",
            "PREPARED_DB_JDBC_URL", "PREPARED_MYSQL_BASEDIR"})
    void missingNewConfigurationCannotFallBackToLegacyVariables(String key) throws Exception {
        Map<String, String> env = fixture(); env.remove(key);
        env.put("DB_PASSWORD", PASSWORD); env.put("DB_USERNAME", "root");
        var failure = assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
        assertFalse(failure.toString().contains(PASSWORD));
    }

    @ParameterizedTest @ValueSource(strings = {"jdbc:mysql://localhost:14327/prepared_interview",
            "jdbc:mysql://127.0.0.1:3306/defense", "jdbc:mysql://127.0.0.1:14327/mysql",
            "jdbc:mysql://127.0.0.1:14327/prepared_interview;DROP DATABASE mysql",
            "jdbc:mysql://127.0.0.1:14327/prepared_interview?sslMode=DISABLED"})
    void rejectsForeignEndpointSchemaAndUnprotectedTransport(String url) throws Exception {
        Map<String, String> env = fixture(); env.put("PREPARED_DB_JDBC_URL", url);
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
    }

    @ParameterizedTest @ValueSource(strings = {"root", "prepared_migrator", "app';GRANT", "app@localhost", ""})
    void rejectsPrivilegedOrUnsafeApplicationIdentity(String user) throws Exception {
        Map<String, String> env = fixture(); env.put("PREPARED_DB_USERNAME", user);
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
    }

    @ParameterizedTest @ValueSource(strings = {"short", "a-password-containing-a-single-quote'123456", "a password with spaces 12345678901234567890"})
    void rejectsWeakOrUnsafePasswordsWithoutDisclosingThem(String password) throws Exception {
        Map<String, String> env = fixture(); env.put("PREPARED_DB_PASSWORD", password);
        var failure = assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
        assertFalse(failure.toString().contains(password));
    }

    @Test void publicReadableConfigurationIsRejected() throws Exception {
        Map<String, String> env = fixture();
        Files.setPosixFilePermissions(temporary.resolve(".env.local"), PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
    }

    @Test void runtimeMustBeOwnerOnlyAndToolsExecutable() throws Exception {
        Map<String, String> env = fixture();
        Files.createDirectories(temporary.resolve(".runtime"));
        Files.setPosixFilePermissions(temporary.resolve(".runtime"), PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
        Files.setPosixFilePermissions(temporary.resolve(".runtime"), PosixFilePermissions.fromString("rwx------"));
        Files.delete(temporary.resolve("mysql/bin/mysqladmin"));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
    }

    @ParameterizedTest @ValueSource(strings = {"sslMode=REQUIRED&sslMode=DISABLED", "sslMode=REQUIRED&allowMultiQueries=true",
            "sslMode=REQUIRED&createDatabaseIfNotExist=true", "sslMode=REQUIRED&unknown=unsafe"})
    void ambiguousOrUnsafeJdbcOptionsCannotBypassTheTransportPolicy(String options) throws Exception {
        Map<String, String> env = fixture();
        env.put("PREPARED_DB_JDBC_URL", "jdbc:mysql://127.0.0.1:14327/prepared_interview?" + options);
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
    }

    @Test void standardExampleTimezoneAndEncodingRemainSupported() throws Exception {
        Map<String, String> env = fixture();
        env.put("PREPARED_DB_JDBC_URL", "jdbc:mysql://127.0.0.1:14327/prepared_interview?useUnicode=true&characterEncoding=UTF-8&sslMode=REQUIRED&serverTimezone=Asia/Shanghai");
        assertEquals("prepared_interview", PreparedLocalMysqlBootstrap.validate(temporary, env).schema());
    }

    @Test void missingRootConfigurationAndForeignProfileAreRejectedSafely() throws Exception {
        Map<String, String> env = fixture();
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(null, env));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, null));
        env.put("SPRING_PROFILE", "dev");
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
    }

    @Test void externalConfigurationAndOverlongSchemaCannotBecomeNewResources() throws Exception {
        Map<String, String> env = fixture();
        env.put("ENV_FILE", temporary.getParent().resolve("foreign.env").toString());
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
        env.remove("ENV_FILE");
        env.put("PREPARED_DB_JDBC_URL", "jdbc:mysql://127.0.0.1:14327/prepared_interview_" + "a".repeat(60) + "?sslMode=REQUIRED");
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
    }

    @Test void failedSecureInitializationRetainsPrivateArtifactsAndCannotBeBlindlyRetried() throws Exception {
        Map<String, String> env = shortFixture();
        var failure = assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.initialize(temporary, env));
        assertTrue(failure.getMessage().contains("initialization failed"));
        assertTrue(Files.exists(temporary.resolve(".runtime/mysql-bootstrap.lock")));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(temporary.resolve(".runtime/mysql-admin.cnf")));
        assertFalse(failure.toString().contains(PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.initialize(temporary, env));
    }

    @Test void portOccupancyIsRejectedBeforeArtifactsOrAuthenticationAreCreated() throws Exception {
        Map<String, String> env = shortFixture();
        try (java.net.ServerSocket occupied = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            env.put("PREPARED_MYSQL_PORT", Integer.toString(occupied.getLocalPort()));
            env.put("PREPARED_DB_JDBC_URL", "jdbc:mysql://127.0.0.1:" + occupied.getLocalPort() + "/prepared_interview?sslMode=REQUIRED");
            var failure = assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.initialize(temporary, env));
            assertTrue(failure.getMessage().contains("port is unavailable"));
            assertFalse(Files.exists(temporary.resolve(".runtime")));
        }
    }

    @Test void failedPrivateBootstrapDaemonIsNotLeftRunningOrReportedSuccessful() throws Exception {
        Map<String, String> env = shortFixture();
        Files.writeString(temporary.resolve("mysql/bin/mysqld"), "#!/bin/sh\nexit 0\n");
        var failure = assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.initialize(temporary, env));
        assertTrue(failure.getMessage().contains("identity could not be verified"));
        assertFalse(Files.exists(temporary.resolve(".runtime/mysql-bootstrap.complete")));
        assertTrue(Files.exists(temporary.resolve(".runtime/mysql-init.sql")));
    }

    @Test void nonCanonicalOrNonDirectoryProjectCannotBeProvisioned() throws Exception {
        Map<String, String> env = fixture();
        Path alias = temporary.resolve("alias"); Files.createSymbolicLink(alias, temporary);
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(alias, env));
        Path file = temporary.resolve("plain-file"); Files.writeString(file, "not a directory");
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(file, env));
    }

    @Test void configurationCannotBeTheProjectRootOrMissing() throws Exception {
        Map<String, String> env = fixture(); env.put("ENV_FILE", temporary.toString());
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
        env.put("ENV_FILE", temporary.resolve("missing.env").toString());
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
    }

    @Test void anInstalledButNonExecutableToolIsNotTrusted() throws Exception {
        Map<String, String> env = fixture();
        Files.setPosixFilePermissions(temporary.resolve("mysql/bin/mysqld"), PosixFilePermissions.fromString("rw-------"));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
    }

    @ParameterizedTest @ValueSource(strings = {"jdbc:mysql://127.0.0.1:14328/prepared_interview?sslMode=REQUIRED",
            "jdbc:mysql://127.0.0.1:14327/prepared_interview", "jdbc:mysql://127.0.0.1:14327/prepared_interview?sslMode=REQUIRED&useUnicode",
            "jdbc:mysql://127.0.0.1:14327/prepared_interview?sslMode=REQUIRED&useUnicode=false"})
    void incompleteOrMismatchedConfigurationIsRejected(String url) throws Exception {
        Map<String, String> env = fixture(); env.put("PREPARED_DB_JDBC_URL", url);
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
    }

    @Test void tooLongSocketFailsBeforeCreatingCredentialsOrData() throws Exception {
        temporary = Files.createDirectory(temporary.resolve("long-project-" + "x".repeat(70)));
        Map<String, String> env = fixture();
        var failure = assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.initialize(temporary, env));
        assertTrue(failure.getMessage().contains("socket path is too long"));
        assertFalse(Files.exists(temporary.resolve(".runtime")));
    }

    @Test void anExistingEmptyOwnedTargetIsAllowedButStillPreservedWhenInitializationFails() throws Exception {
        Map<String, String> env = shortFixture();
        Files.createDirectories(temporary.resolve(".runtime/mysql"));
        Files.setPosixFilePermissions(temporary.resolve(".runtime"), PosixFilePermissions.fromString("rwx------"));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.initialize(temporary, env));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(temporary.resolve(".runtime/mysql")));
        assertTrue(Files.exists(temporary.resolve(".runtime/mysql-bootstrap.lock")));
    }

    private Map<String, String> shortFixture() throws Exception {
        temporary = Files.createDirectory(Path.of("../.runtime").toRealPath().resolve("mb-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        return fixture();
    }

    @ParameterizedTest @ValueSource(strings = {"0", "1023", "65536", "abc", "13014", "13015"})
    void rejectsInvalidOrConflictingPorts(String port) throws Exception {
        Map<String, String> env = fixture(); env.put("PREPARED_MYSQL_PORT", port);
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validate(temporary, env));
    }

    private Map<String, String> fixture() throws Exception {
        temporary = temporary.toRealPath();
        Files.writeString(temporary.resolve(".env.local"), "# synthetic policy fixture\n");
        Files.setPosixFilePermissions(temporary.resolve(".env.local"), PosixFilePermissions.fromString("rw-------"));
        Files.createDirectories(temporary.resolve("mysql/bin"));
        for (String tool : new String[]{"mysql", "mysqld", "mysqladmin"}) {
            Path binary = temporary.resolve("mysql/bin/" + tool);
            Files.writeString(binary, "#!/bin/sh\nexit 88\n");
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        }
        Map<String, String> env = new HashMap<>();
        env.put("PREPARED_MYSQL_BASEDIR", temporary.resolve("mysql").toString());
        env.put("PREPARED_DB_JDBC_URL", "jdbc:mysql://127.0.0.1:14327/prepared_interview_fixture?sslMode=REQUIRED");
        env.put("PREPARED_MYSQL_PORT", "14327");
        env.put("PREPARED_DB_USERNAME", "prepared_app");
        env.put("PREPARED_DB_PASSWORD", PASSWORD);
        return env;
    }
}
