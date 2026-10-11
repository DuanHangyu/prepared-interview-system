package system.assessment.defense.prepared.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit build/Java substitutes exercise only the shell secret boundary. */
class PreparedLocalMysqlMigrationLauncherTest {
    @TempDir Path root;

    @Test void buildHasNoCredentialsAndMigrationHasNoModelOrAdminSecretsInEnvOrArguments() throws Exception {
        prepare();
        Result result = run(Map.of("PREPARED_DASHSCOPE_API_KEY", "synthetic-model", "MYSQL_PWD", "synthetic-admin", "DB_PASSWORD", "synthetic-old"));
        assertEquals(0, result.exit(), result.output());
        assertTrue(Files.exists(root.resolve("migration-ran")));
        Path artifacts;
        try (var dirs = Files.list(root.resolve(".runtime"))) { artifacts = dirs.filter(p -> p.getFileName().toString().startsWith("mysql-migration-build.")).findFirst().orElseThrow(); }
        assertTrue(Files.readString(artifacts.resolve("build.log")).contains("BUILD_DB=unset BUILD_MODEL=unset BUILD_ADMIN=unset"));
        String output = Files.readString(artifacts.resolve("migration.log"));
        assertTrue(output.contains("MODEL=unset ADMIN=unset LEGACY=unset"));
        assertFalse(output.contains("synthetic-application-password"));
        assertFalse(result.output().contains("synthetic-model"));
    }

    @Test void publicReadableConfigIsNotSourcedOrBuilt() throws Exception {
        prepare(); Files.writeString(root.resolve(".env.local"), "touch '" + root.resolve("sourced") + "'\n");
        Files.setPosixFilePermissions(root.resolve(".env.local"), PosixFilePermissions.fromString("rw-r--r--"));
        assertNotEquals(0, run(Map.of()).exit());
        assertFalse(Files.exists(root.resolve("sourced"))); assertFalse(Files.exists(root.resolve("build-ran")));
    }

    @Test void incompleteInstalledEvidenceCannotReachBuildOrCredentialReading() throws Exception {
        prepare(); Files.delete(root.resolve(".runtime/mysql-bootstrap.complete"));
        assertNotEquals(0, run(Map.of()).exit()); assertFalse(Files.exists(root.resolve("build-ran")));
    }

    @Test void missingJdkOrBuildFailureCannotRunMigration() throws Exception {
        prepare(); Files.delete(root.resolve("jdk/bin/javac"));
        assertNotEquals(0, run(Map.of()).exit()); assertFalse(Files.exists(root.resolve("migration-ran")));
    }

    @Test void failedBuildRetainsPrivateDiagnosticAndNeverRunsMigration() throws Exception {
        prepare(); executable(root.resolve("defense-assessment/mvnw"), "#!/bin/sh\nexit 88\n");
        assertNotEquals(0, run(Map.of()).exit()); assertFalse(Files.exists(root.resolve("migration-ran")));
    }

    private void prepare() throws Exception {
        root = root.toRealPath();
        Files.createDirectories(root.resolve("scripts")); Files.createDirectories(root.resolve("jdk/bin"));
        Files.createDirectories(root.resolve("defense-assessment/target/classes")); Files.createDirectories(root.resolve(".runtime"));
        Files.setPosixFilePermissions(root.resolve(".runtime"), PosixFilePermissions.fromString("rwx------"));
        Files.writeString(root.resolve(".runtime/mysql-bootstrap.complete"), "prepared-local-mysql-v1\n");
        Files.copy(Path.of("../scripts/migrate-local-mysql.sh"), root.resolve("scripts/migrate-local-mysql.sh"));
        Files.copy(Path.of("../scripts/prepared-local-config.sh"), root.resolve("scripts/prepared-local-config.sh"));
        executable(root.resolve("jdk/bin/javac"), "#!/bin/sh\nexit 0\n");
        executable(root.resolve("jdk/bin/java"), "#!/bin/bash\nprintf 'MODEL=%s ADMIN=%s LEGACY=%s\\n' \"${PREPARED_DASHSCOPE_API_KEY:-unset}\" \"${MYSQL_PWD:-unset}\" \"${DB_PASSWORD:-unset}\"\nprintf '%s\\n' \"$*\"\ntouch '" + root.resolve("migration-ran") + "'\n");
        executable(root.resolve("defense-assessment/mvnw"), "#!/bin/bash\nprintf 'BUILD_DB=%s BUILD_MODEL=%s BUILD_ADMIN=%s\\n' \"${PREPARED_DB_PASSWORD:-unset}\" \"${PREPARED_DASHSCOPE_API_KEY:-unset}\" \"${MYSQL_PWD:-unset}\"\n"
                + "for option in \"$@\"; do if [[ \"$option\" == -Dmdep.outputFile=* ]]; then printf '/synthetic/dependency.jar' > \"${option#-Dmdep.outputFile=}\"; fi; done\ntouch '" + root.resolve("build-ran") + "'\n");
        Files.writeString(root.resolve(".env.local"), "JAVA_HOME='" + root.resolve("jdk") + "'\nPREPARED_DB_JDBC_URL='jdbc:mysql://127.0.0.1:14327/prepared_interview?sslMode=REQUIRED'\nPREPARED_MYSQL_BASEDIR='/synthetic/mysql'\nPREPARED_MYSQL_PORT='14327'\nPREPARED_DB_USERNAME='prepared_app'\nPREPARED_DB_PASSWORD='synthetic-application-password'\n");
        Files.setPosixFilePermissions(root.resolve(".env.local"), PosixFilePermissions.fromString("rw-------"));
    }

    private Result run(Map<String, String> extra) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", root.resolve("scripts/migrate-local-mysql.sh").toString());
        builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"); builder.environment().putAll(extra);
        // Preserve the actual user home for the normal Maven cache, never point
        // HOME at the fixture or at another user's/global project state.
        builder.environment().put("HOME", System.getenv("HOME"));
        builder.redirectErrorStream(true); Process process = builder.start();
        try { assertTrue(process.waitFor(10, TimeUnit.SECONDS)); return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private static void executable(Path path, String content) throws Exception {
        Files.writeString(path, content); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }

    private record Result(int exit, String output) {}
}
