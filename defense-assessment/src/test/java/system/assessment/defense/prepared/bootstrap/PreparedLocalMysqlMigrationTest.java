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
import static org.mockito.Mockito.*;

/** Installed-target policy tests. No real daemon or database connections here. */
class PreparedLocalMysqlMigrationTest {
    @TempDir Path root;

    @Test void installedPolicyDoesNotResetOrRotateExistingCredentials() throws Exception {
        Map<String, String> env = fixture();
        var plan = PreparedLocalMysqlBootstrap.validateInstalled(root, env);
        assertEquals("prepared_interview_fixture", plan.schema());
        assertFalse(plan.toString().contains("synthetic"));
        assertEquals("preserved administrator file", Files.readString(root.resolve(".runtime/mysql-admin.cnf")));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.initialize(root, env));
    }

    @Test void migrationTransportHasExplicitConnectionAndSocketDeadlines() throws Exception {
        Map<String, String> env = fixture();
        String url = PreparedLocalMysqlMigration.boundedDatabaseUrl(env.get("PREPARED_DB_JDBC_URL"));
        assertTrue(url.endsWith("&connectTimeout=5000&socketTimeout=30000"));
        assertTrue(url.contains("sslMode=REQUIRED"));
    }

    @ParameterizedTest @ValueSource(strings = {".runtime/mysql/mysql.ibd", ".runtime/mysql/mysql", ".runtime/mysql-bootstrap.complete"})
    void missingInstalledEvidenceRefusesMigration(String target) throws Exception {
        Map<String, String> env = fixture(); Files.delete(root.resolve(target));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validateInstalled(root, env));
    }

    @ParameterizedTest @ValueSource(strings = {".runtime/mysql/mysql.ibd", ".runtime/mysql/mysql", ".runtime/mysql-bootstrap.complete"})
    void linkedInstalledEvidenceCannotAdoptAnotherDatabase(String target) throws Exception {
        Map<String, String> env = fixture(); Path path = root.resolve(target);
        Files.delete(path); Files.createSymbolicLink(path, root.resolve("external"));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validateInstalled(root, env));
    }

    @Test void unknownBootstrapVersionCannotBeMigrated() throws Exception {
        Map<String, String> env = fixture();
        Files.writeString(root.resolve(".runtime/mysql-bootstrap.complete"), "unknown-version\n");
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validateInstalled(root, env));
    }

    @Test void noPidOrUnownedProcessRefusesBeforeReadingMigrationSecretsOrCreatingLock() throws Exception {
        Map<String, String> env = fixture();
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlMigration.migrate(root, env));
        assertFalse(Files.exists(root.resolve(".runtime/mysql-migrate.lock")));
        // Current test JVM is not mysqld, even if its PID is placed in the fixture.
        Files.writeString(root.resolve(".runtime/mysql/server.pid"), Long.toString(ProcessHandle.current().pid()));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlMigration.migrate(root, env));
        assertFalse(Files.exists(root.resolve(".runtime/mysql-migrate.lock")));
        assertEquals("preserved administrator file", Files.readString(root.resolve(".runtime/mysql-admin.cnf")));
    }

    @Test void dedicatedMigrationOptionsArePrivateStrictAndRedacted() throws Exception {
        Map<String, String> env = fixture();
        var plan = PreparedLocalMysqlBootstrap.validateInstalled(root, env);
        Path config = root.resolve(".runtime/mysql-migration.cnf");
        privateFile(config, validConfig());
        var credential = PreparedLocalMysqlMigration.readCredential(plan);
        assertFalse(credential.toString().contains("synthetic_migration"));
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlMigration.readCredential(plan));
    }

    @ParameterizedTest @ValueSource(strings = {"user=\"root\"", "host=external.example", "port=3306", "ssl-mode=DISABLED",
            "password=\"short\"", "socket=/foreign/socket", "!include /foreign/root.cnf", "user=\"prepared_migrator\"\nuser=\"root\""})
    void unexpectedOptionsAndPrivilegedIdentityAreRejected(String invalid) throws Exception {
        Map<String, String> env = fixture(); var plan = PreparedLocalMysqlBootstrap.validateInstalled(root, env);
        String config = validConfig(); String key = invalid.split("=", 2)[0];
        if (config.lines().anyMatch(line -> line.startsWith(key + "="))) config = config.replaceFirst("(?m)^" + java.util.regex.Pattern.quote(key) + "=.*$", java.util.regex.Matcher.quoteReplacement(invalid));
        else config += invalid + "\n";
        privateFile(root.resolve(".runtime/mysql-migration.cnf"), config);
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlMigration.readCredential(plan));
    }

    @Test void missingLinkedOversizedAndMalformedCredentialsNeverFallBackToAdministratorFile() throws Exception {
        Map<String, String> env = fixture(); var plan = PreparedLocalMysqlBootstrap.validateInstalled(root, env);
        Path config = plan.migration();
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlMigration.readCredential(plan));
        Files.createSymbolicLink(config, plan.admin());
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlMigration.readCredential(plan)); Files.delete(config);
        for (String content : new String[]{"x".repeat(5000), "", "[mysqld]\n", "[client]\n", "[client]\ninvalid syntax\n"}) {
            privateFile(config, content);
            assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlMigration.readCredential(plan));
        }
        assertEquals("preserved administrator file", Files.readString(plan.admin()));
    }

    @Test void staleLinkedAndMalformedPidCannotReachAuthentication() throws Exception {
        Map<String, String> env = fixture(); Path pid = root.resolve(".runtime/mysql/server.pid");
        Files.createSymbolicLink(pid, root.resolve(".runtime/mysql-admin.cnf"));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlMigration.migrate(root, env)); Files.delete(pid);
        for (String value : new String[]{"0", "-1", "not-a-pid", "1".repeat(40), "99999999999999999"}) {
            privateFile(pid, value);
            assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlMigration.migrate(root, env));
            assertFalse(Files.exists(root.resolve(".runtime/mysql-migrate.lock")));
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4})
    void mismatchedAuthenticatedIdentityIsRejectedWithoutSqlMutation(int badField) throws Exception {
        Map<String, String> env = fixture(); var plan = PreparedLocalMysqlBootstrap.validateInstalled(root, env);
        var connection = mock(java.sql.Connection.class); var statement = mock(java.sql.PreparedStatement.class);
        var rows = mock(java.sql.ResultSet.class);
        when(connection.prepareStatement("SELECT @@datadir,@@port,@@partial_revokes,CURRENT_USER()")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(badField != 0);
        when(rows.getString(1)).thenReturn(badField == 1 ? "/foreign/mysql/" : plan.data() + "/");
        when(rows.getInt(2)).thenReturn(badField == 2 ? 3306 : plan.port());
        when(rows.getInt(3)).thenReturn(badField == 3 ? 0 : 1);
        when(rows.getString(4)).thenReturn(badField == 4 ? "root@localhost" : "prepared_migrator@127.0.0.1");
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlMigration.verifyServer(connection, plan, "prepared_migrator"));
        verify(statement).setQueryTimeout(3); verify(statement).close(); verify(rows).close();
        verify(connection).prepareStatement("SELECT @@datadir,@@port,@@partial_revokes,CURRENT_USER()");
        verifyNoMoreInteractions(connection);
    }

    @ParameterizedTest @ValueSource(strings = {"--no-defaults", "--datadir=", "--pid-file=", "--port=", "--bind-address=", "--partial-revokes="})
    void incompleteProcessArgumentsRefuseBeforeReadingCredentials(String missingPrefix) throws Exception {
        Map<String, String> env = fixture(); var plan = PreparedLocalMysqlBootstrap.validateInstalled(root, env);
        var arguments = new java.util.ArrayList<>(java.util.List.of("--no-defaults", "--datadir=" + plan.data(),
                "--pid-file=" + plan.data().resolve("server.pid"), "--port=" + plan.port(),
                "--bind-address=127.0.0.1", "--partial-revokes=ON"));
        arguments.removeIf(argument -> argument.startsWith(missingPrefix));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlMigration.verifyProcessCommand(plan,
                plan.base().resolve("bin/mysqld").toString(), arguments));
        assertFalse(Files.exists(root.resolve(".runtime/mysql-migrate.lock")));
    }

    @Test void residualInitializationLockAndOversizedVersionMarkerPreventAdoption() throws Exception {
        Map<String, String> env = fixture();
        Files.createDirectory(root.resolve(".runtime/mysql-bootstrap.lock"));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validateInstalled(root, env));
        Files.delete(root.resolve(".runtime/mysql-bootstrap.lock"));
        Files.writeString(root.resolve(".runtime/mysql-bootstrap.complete"), "x".repeat(128));
        assertThrows(IllegalArgumentException.class, () -> PreparedLocalMysqlBootstrap.validateInstalled(root, env));
    }

    private Map<String, String> fixture() throws Exception {
        root = root.toRealPath();
        privateFile(root.resolve(".env.local"), "# isolated policy fixture\n");
        Files.createDirectories(root.resolve("mysql/bin"));
        for (String tool : new String[]{"mysqld", "mysql", "mysqladmin"}) {
            Path binary = root.resolve("mysql/bin/" + tool); privateFile(binary, "#!/bin/sh\nexit 88\n");
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        }
        Files.createDirectories(root.resolve(".runtime/mysql/mysql"));
        Files.setPosixFilePermissions(root.resolve(".runtime"), PosixFilePermissions.fromString("rwx------"));
        privateFile(root.resolve(".runtime/mysql/mysql.ibd"), "synthetic data marker");
        privateFile(root.resolve(".runtime/mysql-admin.cnf"), "preserved administrator file");
        privateFile(root.resolve(".runtime/mysql-bootstrap.complete"), "prepared-local-mysql-v1\n");
        return new HashMap<>(Map.of("PREPARED_MYSQL_BASEDIR", root.resolve("mysql").toString(),
                "PREPARED_MYSQL_PORT", "14327", "PREPARED_DB_USERNAME", "prepared_app",
                "PREPARED_DB_PASSWORD", "synthetic_application_password_1234567890",
                "PREPARED_DB_JDBC_URL", "jdbc:mysql://127.0.0.1:14327/prepared_interview_fixture?sslMode=REQUIRED"));
    }

    private static String validConfig() {
        return "[client]\nuser=\"prepared_migrator\"\npassword=\"synthetic_migration_password_1234567890\"\nprotocol=TCP\nhost=127.0.0.1\nport=14327\nssl-mode=REQUIRED\n";
    }

    private static void privateFile(Path path, String content) throws Exception {
        Files.writeString(path, content); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
}
