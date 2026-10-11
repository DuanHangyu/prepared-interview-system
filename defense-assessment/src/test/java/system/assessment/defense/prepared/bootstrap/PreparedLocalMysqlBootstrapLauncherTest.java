package system.assessment.defense.prepared.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Shell boundary fixtures, not proof of actual database initialization. */
class PreparedLocalMysqlBootstrapLauncherTest {
    @TempDir Path fixture;

    @Test void helperReceivesOnlyDedicatedInputsWithoutPasswordsInArguments() throws Exception {
        prepare();
        Result result = run(Map.of("PREPARED_DASHSCOPE_API_KEY", "synthetic-cloud-secret",
                "MYSQL_PWD", "synthetic-admin-secret", "DB_PASSWORD", "synthetic-legacy-secret"));
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("MODEL=unset ADMIN=unset LEGACY=unset"));
        assertFalse(result.output().contains("synthetic-app-password"));
        assertFalse(result.output().contains("synthetic-cloud-secret"));
        assertTrue(Files.exists(fixture.resolve("contact")));
    }

    @Test void missingConfigurationNeverRunsHelper() throws Exception {
        prepare(); Files.delete(fixture.resolve(".env.local"));
        assertNotEquals(0, run(Map.of()).exit());
        assertFalse(Files.exists(fixture.resolve("contact")));
    }

    @Test void foreignConfigurationNeverRunsHelper() throws Exception {
        prepare();
        Path outside = Files.createTempFile("prepared-bootstrap-", ".env");
        try {
            assertNotEquals(0, run(Map.of("ENV_FILE", outside.toString())).exit());
            assertFalse(Files.exists(fixture.resolve("contact")));
        } finally { Files.delete(outside); }
    }

    @Test void publicReadableConfigurationIsRejectedBeforeSourcingIt() throws Exception {
        prepare();
        Files.writeString(fixture.resolve(".env.local"), "touch '" + fixture.resolve("sourced") + "'\n");
        Files.setPosixFilePermissions(fixture.resolve(".env.local"), PosixFilePermissions.fromString("rw-r--r--"));
        assertNotEquals(0, run(Map.of()).exit());
        assertFalse(Files.exists(fixture.resolve("sourced")));
        assertFalse(Files.exists(fixture.resolve("contact")));
    }

    @Test void linkedConfigurationIsRejectedBeforeSourcingIt() throws Exception {
        prepare(); Files.move(fixture.resolve(".env.local"), fixture.resolve("linked-env"));
        Files.createSymbolicLink(fixture.resolve(".env.local"), fixture.resolve("linked-env"));
        assertNotEquals(0, run(Map.of()).exit());
        assertFalse(Files.exists(fixture.resolve("contact")));
    }

    private void prepare() throws Exception {
        fixture = fixture.toRealPath();
        Files.createDirectories(fixture.resolve("scripts")); Files.createDirectories(fixture.resolve("jdk/bin"));
        Files.copy(Path.of("../scripts/init-local-mysql.sh"), fixture.resolve("scripts/init-local-mysql.sh"));
        Files.copy(Path.of("../scripts/prepared-local-config.sh"), fixture.resolve("scripts/prepared-local-config.sh"));
        Path java = fixture.resolve("jdk/bin/java");
        Files.writeString(java, "#!/bin/bash\nprintf 'MODEL=%s ADMIN=%s LEGACY=%s\\n' \"${PREPARED_DASHSCOPE_API_KEY:-unset}\" \"${MYSQL_PWD:-unset}\" \"${DB_PASSWORD:-unset}\"\n"
                + "printf '%s\\n' \"$*\"\ntouch '" + fixture.resolve("contact") + "'\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(fixture.resolve(".env.local"), "SPRING_PROFILE='prepared-local'\nJAVA_HOME='" + fixture.resolve("jdk")
                + "'\nPREPARED_DB_JDBC_URL='jdbc:mysql://127.0.0.1:14327/prepared_interview?sslMode=REQUIRED'\n"
                + "PREPARED_MYSQL_BASEDIR='/synthetic/mysql'\nPREPARED_MYSQL_PORT='14327'\nPREPARED_DB_USERNAME='prepared_app'\nPREPARED_DB_PASSWORD='synthetic-app-password'\n");
        Files.setPosixFilePermissions(fixture.resolve(".env.local"), PosixFilePermissions.fromString("rw-------"));
    }

    private Result run(Map<String, String> extra) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", fixture.resolve("scripts/init-local-mysql.sh").toString());
        builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"); builder.environment().putAll(extra);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private record Result(int exit, String output) {}
}
