package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PreparedRuntimeContractTest {
    private final Path root = Path.of("..");

    @Test void newProfileUsesIndependentResourcesAndNoLegacyPaidRealtime() throws Exception {
        String profile = Files.readString(Path.of("src/main/resources/application-prepared-local.yml"));
        assertTrue(profile.contains("PREPARED_DB_JDBC_URL"));
        assertTrue(profile.contains("/prepared_interview"));
        assertTrue(profile.contains("PREPARED_REDIS_PORT:16389"));
        assertTrue(profile.contains("sslMode=REQUIRED"));
        assertTrue(profile.contains("address: 127.0.0.1"));
        assertTrue(profile.contains("database: 1"));
        assertTrue(profile.contains("enabled: false"));
        assertTrue(profile.contains("start: stop"));
    }

    @Test void guardIsRegisteredBeforeDatabaseBeansAndFreshProfileIsDefault() throws Exception {
        String factories = Files.readString(Path.of("src/main/resources/META-INF/spring.factories"));
        assertTrue(factories.contains("system.assessment.defense.prepared.config.PreparedEnvironmentGuard"));
        String base = Files.readString(Path.of("src/main/resources/application.yml"));
        assertTrue(base.contains("SPRING_PROFILES_ACTIVE:prepared-local"));
        assertTrue(base.contains("token-name: prepared-interview-session"));
        assertTrue(base.contains("PREPARED_BACKEND_PORT:13014"));
    }

    @Test void newTemplateCannotSilentlyReuseTheOldDatabaseOrSecrets() throws Exception {
        String template = Files.readString(root.resolve(".env.example"));
        assertTrue(template.contains("SPRING_PROFILE='prepared-local'"));
        assertTrue(template.contains("PREPARED_DB_JDBC_URL="));
        assertFalse(template.contains("/defense_test"));
        assertTrue(template.contains("sslMode=REQUIRED"));
        assertTrue(template.contains("PREPARED_DASHSCOPE_API_KEY=''"));
        assertTrue(template.contains("PREPARED_MODEL_TEST_BUDGET_CNY='100'"));
        assertTrue(template.contains("PREPARED_MODEL_TEST_PAUSE_CNY='80'"));
    }

    @Test void frontendDefaultsNeverProxyTheLegacyPort() throws Exception {
        String config = Files.readString(root.resolve("defense-system/vite.config.mts"));
        assertTrue(config.contains("13015"));
        assertTrue(config.contains("http://127.0.0.1:13014/"));
        assertFalse(config.contains("http://localhost:8081/"));
    }

    @Test void preparedPaperCoverageChecksUnitAndRealBackendEvidenceAtEightyPercent() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<id>prepared-coverage</id>"));
        assertTrue(pom.contains("prepared-live.exec"));
        assertTrue(pom.contains("<minimum>0.80</minimum>"));
        assertTrue(pom.contains("system.assessment.defense.prepared.paper"));
    }

    @Test void durableJobAndCostCoverageHasAnIndependentEightyPercentGate() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<include>system/assessment/defense/prepared/jobs/**</include>"));
        assertTrue(pom.contains("<include>system.assessment.defense.prepared.jobs</include>"));
    }

    @Test void generationCoverageAndDisabledConfigurationAreExplicit() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<include>system.assessment.defense.prepared.generation</include>"));
        String profile = Files.readString(Path.of("src/main/resources/application-prepared-local.yml"));
        assertTrue(profile.contains("PREPARED_GENERATION_WORKER_ENABLED:false"));
        assertTrue(profile.contains("PREPARED_GENERATION_INPUT_PRICE_CNY:"));
    }
}
