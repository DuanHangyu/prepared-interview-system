package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import system.assessment.defense.prepared.config.PreparedEnvironmentGuard;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PreparedEnvironmentGuardTest {
    private Map<String, Object> isolated() {
        return new HashMap<>(Map.of(
                "spring.datasource.hikari.jdbc-url", "jdbc:mysql://127.0.0.1:13317/prepared_interview_test",
                "spring.datasource.url", "jdbc:mysql://127.0.0.1:13317/prepared_interview_test",
                "spring.data.redis.database", "1",
                "sa-token.token-name", "prepared-interview-session",
                "aliyun.oss.bucketName", "prepared-interview-test",
                "dashscope.realtime.enabled", "false",
                "data-retention.enabled", "false",
                "scheduler.start", "stop"));
    }

    private void validate(Map<String, Object> values) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", values));
        new PreparedEnvironmentGuard().postProcessEnvironment(environment, new SpringApplication());
    }

    @Test void acceptsAnIsolatedPreparedDeployment() {
        assertDoesNotThrow(() -> validate(isolated()));
    }

    @Test void refusesTheLegacyDatabaseBeforeAnyConnectionIsOpened() {
        var config = isolated();
        config.put("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/defense_test");
        assertThrows(IllegalStateException.class, () -> validate(config));
    }

    @Test void validatesTheActualHikariUrlAsWellAsTheGenericUrl() {
        var config = isolated();
        config.put("spring.datasource.hikari.jdbc-url", "jdbc:mysql://127.0.0.1:3306/defense_test");
        assertThrows(IllegalStateException.class, () -> validate(config));
    }

    @Test void refusesAUrlThatOnlyMentionsTheNewSchemaInTheQuery() {
        var config = isolated();
        config.put("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/defense_test?hint=prepared_interview");
        assertThrows(IllegalStateException.class, () -> validate(config));
    }

    @Test void refusesMissingDatabaseConfiguration() {
        var config = isolated();
        config.remove("spring.datasource.url");
        config.remove("spring.datasource.hikari.jdbc-url");
        assertThrows(IllegalStateException.class, () -> validate(config));
    }

    @Test void refusesSharedSessionStorageAndCookieName() {
        for (String key : new String[]{"spring.data.redis.database", "sa-token.token-name"}) {
            var config = isolated();
            config.put(key, key.contains("redis") ? "0" : "Authorization");
            assertThrows(IllegalStateException.class, () -> validate(config), key);
        }
    }

    @Test void refusesTheLegacyObjectBucket() {
        var config = isolated();
        config.put("aliyun.oss.bucketName", "defense-assessment-new");
        assertThrows(IllegalStateException.class, () -> validate(config));
    }

    @Test void refusesLegacyRealtimeAndDestructiveSchedulers() {
        for (String key : new String[]{"dashscope.realtime.enabled", "data-retention.enabled", "scheduler.start"}) {
            var config = isolated();
            config.put(key, key.equals("scheduler.start") ? "start" : "true");
            assertThrows(IllegalStateException.class, () -> validate(config), key);
        }
    }

    @Test void neverIncludesSensitiveConnectionDetailsInAnError() {
        var config = isolated();
        config.put("spring.datasource.url", "jdbc:mysql://private-user:private-password@internal/defense_test");
        var error = assertThrows(IllegalStateException.class, () -> validate(config));
        assertFalse(error.getMessage().contains("private-password"));
        assertFalse(error.getMessage().contains("internal"));
    }

    @Test void budgetConfigurationCannotRaiseTheUserApprovedCostCeiling() {
        for (String[] pair : new String[][]{{"101", "80"}, {"100", "81"}, {"10", "11"}, {"-1", "0"}, {"NaN", "80"}}) {
            var config = isolated();
            config.put("prepared.models.test-budget-cny", pair[0]);
            config.put("prepared.models.pause-cny", pair[1]);
            assertThrows(IllegalStateException.class, () -> validate(config));
        }
    }

    @Test void lowerOrZeroTestBudgetsAreAllowedButDoNotExpandTheCeiling() {
        var config = isolated();
        config.put("prepared.models.test-budget-cny", "0");
        config.put("prepared.models.pause-cny", "0");
        assertDoesNotThrow(() -> validate(config));
    }
}
