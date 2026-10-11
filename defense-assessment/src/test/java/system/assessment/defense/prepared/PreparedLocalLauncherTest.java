package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real shell execution in an isolated fixture; launcher substitutes are NOT cloud/device integration. */
class PreparedLocalLauncherTest {
    @TempDir Path fixture;
    private static final String JDBC = "jdbc:mysql://127.0.0.1:13317/prepared_interview?sslMode=REQUIRED";

    @Test void backendUsesPreparedCredentialsProfileAndPortWithoutLegacyVariables() throws Exception {
        prepare();
        config("PREPARED_DB_JDBC_URL='" + JDBC + "'\nPREPARED_DB_USERNAME='fixture-user'\n"
                + "PREPARED_DB_PASSWORD='synthetic-not-a-credential'\n");
        Result result = run("start-backend.sh", Map.of());
        assertEquals(0, result.exit, result.output);
        assertTrue(result.output.contains("spring-boot.run.profiles=prepared-local"), result.output);
        assertTrue(result.output.contains("PREPARED_PORT=13014"), result.output);
        assertEquals("-z -w 2 127.0.0.1 13317\n", probe());
    }

    @Test void backendRejectsLegacyOnlyCredentialsBeforeNetworkOrLauncher() throws Exception {
        prepare(); config("");
        Result result = run("start-backend.sh", Map.of("DB_JDBC_URL", JDBC,
                "DB_USERNAME", "legacy-user", "DB_PASSWORD", "synthetic-legacy-value"));
        assertNotEquals(0, result.exit);
        assertTrue(result.output.contains("PREPARED_DB_JDBC_URL"), result.output);
        assertFalse(result.output.contains("WRAPPER_STARTED"));
        assertEquals("", probe());
    }

    @Test void legacyProfileCannotStartTheNewApplication() throws Exception {
        prepare(); config("SPRING_PROFILE='local'\nPREPARED_DB_JDBC_URL='" + JDBC + "'\n"
                + "PREPARED_DB_USERNAME='fixture-user'\nPREPARED_DB_PASSWORD='synthetic-value'\n");
        Result result = run("start-backend.sh", Map.of());
        assertNotEquals(0, result.exit);
        assertTrue(result.output.contains("prepared-local"), result.output);
        assertFalse(result.output.contains("WRAPPER_STARTED"));
        assertEquals("", probe());
    }

    @Test void frontendUsesPreparedPortAndMatchingLoopbackBackendNotInheritedLegacyProxy() throws Exception {
        prepare(); config("PREPARED_BACKEND_PORT='13026'\nPREPARED_FRONTEND_PORT='13027'\n");
        Result result = run("start-frontend.sh", Map.of("FRONTEND_PORT", "3004",
                "VUE_APP_PROXY_TARGET", "http://127.0.0.1:8081/"));
        assertEquals(0, result.exit, result.output);
        assertTrue(result.output.contains("--port 13027"), result.output);
        assertTrue(result.output.contains("--strictPort"), result.output);
        assertTrue(result.output.contains("--host 127.0.0.1"), result.output);
        assertTrue(result.output.contains("PROXY=http://127.0.0.1:13026/"), result.output);
        assertFalse(result.output.contains("3004"));
        assertFalse(result.output.contains("8081"));
    }

    @Test void frontendMissingLocalConfigDoesNotSilentlyStart() throws Exception {
        prepare();
        Result result = run("start-frontend.sh", Map.of());
        assertNotEquals(0, result.exit);
        assertFalse(result.output.contains("NPM_STARTED"));
    }

    @Test void invalidBackendPortDoesNotStartEitherProcess() throws Exception {
        for (String value : new String[]{"0", "80", "65536", "not-a-port", "13014;exit 0"}) {
            prepare(); config("PREPARED_BACKEND_PORT='" + value + "'\n");
            Result result = run("start-frontend.sh", Map.of());
            assertNotEquals(0, result.exit, value + " " + result.output);
            assertFalse(result.output.contains("NPM_STARTED"));
        }
    }

    @Test void invalidFrontendPortDoesNotStart() throws Exception {
        for (String value : new String[]{"0", "80", "65536", "not-a-port", "13015;exit 0"}) {
            prepare(); config("PREPARED_FRONTEND_PORT='" + value + "'\n");
            Result result = run("start-frontend.sh", Map.of());
            assertNotEquals(0, result.exit, value + " " + result.output);
            assertFalse(result.output.contains("NPM_STARTED"));
        }
    }

    @Test void restartRefusesOccupiedPortsAndNeverKillsUnrelatedListeners() throws Exception {
        prepare(); config("");
        executable(fixture.resolve("bin/lsof"), "#!/bin/bash\nprintf '424242\\n'\n");
        Result result = run("restart-local.sh", Map.of());
        assertNotEquals(0, result.exit, result.output);
        assertTrue(result.output.contains("occupied"), result.output);
        assertFalse(Files.exists(fixture.resolve("spawned")));
        assertEquals("", probe(), "must not invoke kill/sleep/other destructive port cleanup");
    }

    @Test void restartUsesPreparedPortsRatherThanInheritedLegacyPorts() throws Exception {
        prepare(); config("");
        Result result = run("restart-local.sh", Map.of("BACKEND_PORT", "8081", "FRONTEND_PORT", "3004"));
        assertEquals(0, result.exit, result.output);
        assertTrue(result.output.contains("13014"), result.output);
        assertTrue(result.output.contains("13015"), result.output);
        assertFalse(result.output.contains("8081"));
        assertFalse(result.output.contains("3004"));
    }

    @Test void allLaunchersRejectAnExplicitOldEnvFileOutsideTheirNewRoot() throws Exception {
        prepare();
        Path outside = Files.createTempFile("prepared-outside-launcher-", ".env");
        try {
            Files.writeString(outside, "# synthetic configuration, not a credential\n");
            for (String script : new String[]{"start-backend.sh", "start-frontend.sh", "restart-local.sh"}) {
                Result result = run(script, Map.of("ENV_FILE", outside.toString()));
                assertNotEquals(0, result.exit, script + " " + result.output);
                assertTrue(result.output.contains("inside the prepared project"), result.output);
            }
        } finally { Files.deleteIfExists(outside); }
    }

    private void prepare() throws Exception {
        Files.createDirectories(fixture.resolve("scripts")); Files.createDirectories(fixture.resolve("bin"));
        Files.createDirectories(fixture.resolve("defense-assessment")); Files.createDirectories(fixture.resolve("defense-system"));
        for (String name : new String[]{"start-backend.sh", "start-frontend.sh", "restart-local.sh", "start-db-tunnel.sh"}) {
            String source = Files.readString(Path.of("../scripts/" + name));
            // Even the unfixed launcher must never signal a real host process.
            if (name.equals("restart-local.sh")) source = source.replace("set -euo pipefail",
                    "set -euo pipefail\nkill() { printf 'UNEXPECTED_KILL\\n' >> \"$PREPARED_SCRIPT_PROBE\"; }\n");
            executable(fixture.resolve("scripts/" + name), source);
        }
        Path helper = Path.of("../scripts/prepared-local-config.sh");
        if (Files.exists(helper)) executable(fixture.resolve("scripts/prepared-local-config.sh"), Files.readString(helper));
        executable(fixture.resolve("defense-assessment/mvnw"), "#!/bin/bash\nprintf 'WRAPPER_STARTED %s\\n' \"$*\"\nprintf 'PREPARED_PORT=%s\\n' \"${PREPARED_BACKEND_PORT:-}\"\n");
        executable(fixture.resolve("bin/nc"), "#!/bin/bash\nprintf '%s\\n' \"$*\" >> \"$PREPARED_SCRIPT_PROBE\"\n");
        executable(fixture.resolve("bin/npm"), "#!/bin/bash\nprintf 'NPM_STARTED %s\\n' \"$*\"\nprintf 'PROXY=%s\\n' \"${VUE_APP_PROXY_TARGET:-}\"\n");
        executable(fixture.resolve("bin/lsof"), "#!/bin/bash\nexit 1\n");
        executable(fixture.resolve("bin/nohup"), "#!/bin/bash\nprintf '%s\\n' \"$*\" >> \"$PREPARED_SCRIPT_SPAWN\"\n");
        executable(fixture.resolve("bin/sleep"), "#!/bin/bash\nprintf 'UNEXPECTED_SLEEP\\n' >> \"$PREPARED_SCRIPT_PROBE\"\n");
        Files.deleteIfExists(fixture.resolve("probe"));
    }

    private void config(String contents) throws Exception {
        Files.writeString(fixture.resolve(".env.local"), "DB_TUNNEL_ENABLED='false'\nJAVA_HOME=''\n" + contents);
    }

    private String probe() throws Exception {
        return Files.exists(fixture.resolve("probe")) ? Files.readString(fixture.resolve("probe")) : "";
    }

    private Result run(String script, Map<String,String> override) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", fixture.resolve("scripts/" + script).toString());
        builder.directory(fixture.toFile()); builder.redirectErrorStream(true);
        Map<String,String> env = builder.environment(); env.clear();
        env.put("PATH", fixture.resolve("bin") + ":/usr/bin:/bin:/usr/sbin:/sbin");
        env.put("PREPARED_SCRIPT_PROBE", fixture.resolve("probe").toString());
        env.put("PREPARED_SCRIPT_SPAWN", fixture.resolve("spawned").toString());
        env.putAll(override);
        Process process = builder.start();
        boolean completed = process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) process.destroyForcibly();
        assertTrue(completed, "isolated shell fixture must finish without network/actual launch");
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void executable(Path path, String contents) throws Exception {
        Files.writeString(path, contents); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }
    private record Result(int exit, String output) {}
}
