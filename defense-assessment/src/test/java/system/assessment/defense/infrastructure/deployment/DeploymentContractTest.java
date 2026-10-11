package system.assessment.defense.infrastructure.deployment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentContractTest {

    private static final Path REPOSITORY_ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void backendImageIsPinnedMultiStageNonRootAndHealthChecked() throws IOException {
        String dockerfile = file("defense-assessment/Dockerfile");

        assertThat(dockerfile)
                .contains("FROM maven:3.10.0-eclipse-temurin-21-alpine AS build")
                .contains("FROM eclipse-temurin:21.0.10_7-jre-alpine-3.23")
                .contains("mvn -B -DskipTests package")
                .contains("USER app")
                .contains("HEALTHCHECK")
                .contains("/actuator/health/readiness");
    }

    @Test
    void frontendImageUsesReproducibleInstallAndUnprivilegedRuntime() throws IOException {
        String dockerfile = file("defense-system/Dockerfile");
        String nginx = file("defense-system/nginx.conf");
        String viteConfig = file("defense-system/vite.config.mts");

        assertThat(dockerfile)
                .contains("FROM node:22.23.3-alpine3.23 AS build-stage")
                .contains("npm ci")
                .contains("FROM nginx:1.30.5-alpine3.24 AS production-stage")
                .contains("USER nginx")
                .contains("VUE_APP_BASE_API")
                .contains("VUE_APP_BASE_TEXT_WS")
                .contains("pid /tmp/nginx.pid")
                .contains("CMD [\"-g\", \"daemon off;\"]")
                .contains("HEALTHCHECK");
        assertThat(nginx)
                .contains("listen 8080")
                .contains("client_max_body_size 21m")
                .contains("location = /healthz")
                .contains("location /api/")
                .contains("location /ws/")
                .contains("proxy_pass http://backend:8081");
        assertThat(viteConfig)
                .contains("sourcemap: false")
                .contains("envPrefix: \"VUE_APP_\"");

        String dockerignore = file("defense-system/.dockerignore");
        assertThat(dockerignore)
                .contains(".env.local")
                .contains(".env.*.local");
    }

    @Test
    void composeRunsMigrationsBeforeStartingTheApplication() throws IOException {
        String compose = file("compose.yaml");

        assertThat(compose)
                .contains("mysql:8.4.10")
                .contains("redis:8.8.3-alpine3.23")
                .contains("command: [\"migrate\"]")
                .contains("condition: service_completed_successfully")
                .contains("SPRING_FLYWAY_ENABLED: \"false\"")
                .contains("DB_USERNAME: root")
                .contains("JAVA_TOOL_OPTIONS: -Duser.timezone=Asia/Shanghai")
                .contains("serverTimezone=Asia%2FShanghai")
                .contains("VUE_APP_BASE_API: /api")
                .contains("REDIS_PASSWORD: ${REDIS_PASSWORD:?set REDIS_PASSWORD in .env.compose}")
                .contains("internal: true")
                .contains("127.0.0.1:${BACKEND_PORT:-8081}:8081")
                .contains("backend-logs:/app/logs")
                .contains("max-size: \"10m\"")
                .contains("read_only: true")
                .contains("no-new-privileges:true");

        String runtimeGrants = file("deploy/mysql/10-runtime-grants.sh");
        assertThat(runtimeGrants)
                .contains("REVOKE ALL PRIVILEGES")
                .contains("GRANT SELECT, INSERT, UPDATE, DELETE");
    }

    @Test
    void deploymentRunbookContainsExecutableHealthAndRollbackProcedure() throws IOException {
        String runbook = file("docs/DEPLOYMENT.md");

        assertThat(runbook)
                .contains("/actuator/health/liveness")
                .contains("/actuator/health/readiness")
                .contains("docker compose run --rm migrate")
                .contains("docker image inspect")
                .contains("--no-build")
                .contains("/user-detail")
                .contains("redis-cli")
                .doesNotContain("带 `@Cached` 的真实业务接口")
                .contains("mysqldump")
                .contains("migrate validate")
                .contains("回滚应用")
                .contains("恢复数据库")
                .contains("Flyway");
    }

    @Test
    void ciEnforcesBackendCoverageAndDependencySecurityReview() throws IOException {
        String pom = file("defense-assessment/pom.xml");
        String workflow = file(".github/workflows/ci.yml");

        assertThat(pom)
                .contains("jacoco-maven-plugin")
                .contains("<minimum>0.45</minimum>")
                .contains("<minimum>0.35</minimum>");
        assertThat(workflow)
                .contains("actions/dependency-review-action@v5")
                .contains("fail-on-severity: high")
                .contains("run: npm audit --audit-level=high")
                .doesNotContain("npm audit --omit=dev")
                .contains("MYSQL_INTEGRATION_SERVER_URL")
                .contains("DatabaseMigrationMySqlIT")
                .contains("InterviewConcurrencyMySqlIT")
                .contains("InterviewLifecycleTransactionMySqlIT")
                .contains("DataRetentionMySqlIT")
                .contains("ManagedObjectCleanupMySqlIT");
    }

    @Test
    void backendUsesPatchedSpringBootMaintenanceRelease() throws IOException {
        String pom = file("defense-assessment/pom.xml");

        assertThat(pom)
                .contains("<artifactId>spring-boot-starter-parent</artifactId>")
                .contains("<version>3.5.16</version>")
                .contains("<flyway.version>11.20.3</flyway.version>");
    }

    @Test
    void ossSdkDoesNotPinKnownVulnerableLegacyTransitives() throws IOException {
        String pom = file("defense-assessment/pom.xml");

        assertThat(pom)
                .contains("<artifactId>aliyun-sdk-oss</artifactId>")
                .contains("<version>3.18.5</version>");
    }

    @Test
    void securityPatchedTransitiveVersionsArePinned() throws IOException {
        String pom = file("defense-assessment/pom.xml");

        assertThat(pom)
                .contains("<jackson-bom.version>2.21.7</jackson-bom.version>")
                .contains("<netty.version>4.1.137.Final</netty.version>")
                .contains("<tomcat.version>10.1.60</tomcat.version>")
                .contains("<log4j2.version>2.25.5</log4j2.version>")
                .contains("<opentelemetry.version>1.62.0</opentelemetry.version>")
                .contains("<bouncycastle.version>1.85</bouncycastle.version>")
                .contains("<artifactId>bcprov-jdk18on</artifactId>")
                .contains("<version>${bouncycastle.version}</version>");
    }

    @Test
    void testRuntimeLoadsMockitoAgentWithoutDisplacingJacoco() throws IOException {
        String pom = file("defense-assessment/pom.xml");

        assertThat(pom)
                .contains("maven-surefire-plugin")
                .contains("@{argLine}")
                .contains("-javaagent:${settings.localRepository}/org/mockito/mockito-core/${mockito.version}/mockito-core-${mockito.version}.jar");
    }

    @Test
    void actuatorAndProbeConfigurationExposeOnlyHealthEndpoints() throws IOException {
        String pom = file("defense-assessment/pom.xml");
        String application = file("defense-assessment/src/main/resources/application.yml");
        String auth = file("defense-assessment/src/main/java/system/assessment/defense/infrastructure/config/SaTokenConfig.java");

        assertThat(pom).contains("spring-boot-starter-actuator");
        assertThat(application)
                .contains("include: health")
                .contains("probes:")
                .contains("readiness:")
                .contains("db,redis");
        assertThat(auth).contains("/actuator/health/**");
    }

    @Test
    void containerCacheAndTimeSettingsAreEnvironmentDriven() throws IOException {
        String local = file("defense-assessment/src/main/resources/application-local.yml");
        String application = file("defense-assessment/src/main/resources/application.yml");

        assertThat(local)
                .contains("type: caffeine")
                .doesNotContain("valueDecoder: java")
                .doesNotContain("JETCACHE_REDIS_URI");
        assertThat(application).contains("time-zone: ${APP_TIME_ZONE:Asia/Shanghai}");
    }

    @Test
    void productionUsesDedicatedProfileAndDisablesPublicApiDocumentation() throws IOException {
        String compose = file("compose.yaml");
        String application = file("defense-assessment/src/main/resources/application.yml");
        String production = file("defense-assessment/src/main/resources/application-prod.yml");

        assertThat(application).contains("active: ${SPRING_PROFILES_ACTIVE:prepared-local}");
        assertThat(compose).contains("SPRING_PROFILES_ACTIVE: prod");
        assertThat(production)
                .contains("enabled: ${SPRINGDOC_ENABLED:false}")
                .contains("username: ${DB_USERNAME}")
                .contains("password: ${DB_PASSWORD}")
                .contains("password: ${REDIS_PASSWORD}")
                .doesNotContain("valueDecoder: java")
                .doesNotContain("JETCACHE_REDIS_URI");
    }

    @Test
    void failedV2PrecheckCanBeRetriedWithoutManualProcedureCleanup() throws IOException {
        String migration = file("defense-assessment/src/main/resources/db/migration/V2__data_integrity_constraints.sql");

        assertThat(migration.indexOf("DROP PROCEDURE IF EXISTS flyway_precheck_v2_integrity"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(migration.indexOf("CREATE PROCEDURE flyway_precheck_v2_integrity"));
    }

    private String file(String relativePath) throws IOException {
        Path path = REPOSITORY_ROOT.resolve(relativePath);
        assertThat(Files.exists(path)).as("deployment file %s", relativePath).isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
