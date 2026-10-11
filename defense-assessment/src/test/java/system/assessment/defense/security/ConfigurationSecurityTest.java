package system.assessment.defense.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationSecurityTest {

    @Test
    void sessionTokensUseHardenedCookiesAndDisableHeaderReads() throws IOException {
        String configuration = resource("application.yml");
        assertThat(configuration)
                .contains("is-read-cookie: true")
                .contains("is-read-header: false")
                .contains("is-write-header: false")
                .contains("http-only: true")
                .contains("same-site: Strict")
                .doesNotContain("is-read-cookie: false");
        assertThat(Files.readString(Path.of("../compose.yaml")))
                .contains("AUTH_COOKIE_SECURE: ${AUTH_COOKIE_SECURE:-true}");
    }

    @Test
    void cookieAuthenticationInstallsCsrfOriginValidation() throws IOException {
        String config = Files.readString(Path.of(
                "src/main/java/system/assessment/defense/infrastructure/config/SaTokenConfig.java"));
        assertThat(config).contains("CsrfOriginInterceptor");
    }

    @Test
    void environmentTemplatesKeepCookieTransportAndJdbcSafe() throws IOException {
        String localEnvironment = Files.readString(Path.of("../.env.example"));
        assertThat(localEnvironment)
                .contains("AUTH_COOKIE_SECURE='false'")
                .doesNotContain("allowMultiQueries=true");

        String composeEnvironment = Files.readString(Path.of("../.env.compose.example"));
        assertThat(composeEnvironment)
                .contains("PUBLIC_ORIGIN='https://interview.example.com'")
                .contains("AUTH_COOKIE_SECURE='true'");
    }

    @Test
    void databaseProfilesDoNotEnableMultiQueriesOrDefaultToPublicHosts() throws IOException {
        for (String profile : new String[]{"local", "dev", "test"}) {
            String configuration = resource("application-" + profile + ".yml");
            assertThat(configuration).doesNotContain("allowMultiQueries=true");
            assertThat(configuration).doesNotContain("jdbc:mysql://118.");
        }
    }

    @Test
    void productionResolvesClientAddressesFromTheTrustedReverseProxy() throws IOException {
        assertThat(resource("application-prod.yml"))
                .contains("forward-headers-strategy: native");
    }

    @Test
    void fileUploadRouteIsCoveredByTheApplicationRateLimiter() throws IOException {
        String config = Files.readString(Path.of("src/main/java/system/assessment/defense/infrastructure/config/SaTokenConfig.java"));
        String interceptor = Files.readString(Path.of("src/main/java/system/assessment/defense/infrastructure/config/SecurityRateLimitInterceptor.java"));

        assertThat(config).contains("/file/upload");
        assertThat(interceptor).contains("checkFileUpload");
    }

    @Test
    void productionRateLimitsUseAnAtomicSharedRedisCounter() throws IOException {
        String limiter = Files.readString(Path.of(
                "src/main/java/system/assessment/defense/application/service/SecurityRateLimitService.java"));
        Path redisCounterPath = Path.of(
                "src/main/java/system/assessment/defense/application/service/RedisRateLimitCounter.java");

        assertThat(limiter).contains("RedisRateLimitCounter");
        assertThat(redisCounterPath).exists();
        assertThat(Files.readString(redisCounterPath))
                .contains("DefaultRedisScript")
                .contains("INCRBY")
                .contains("PEXPIRE");
    }

    private String resource(String name) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(input).as("missing test resource %s", name).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
