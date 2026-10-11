package system.assessment.defense.prepared.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.URI;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Fail closed before datasource/Flyway/session/storage beans can touch legacy resources. */
public final class PreparedEnvironmentGuard implements EnvironmentPostProcessor, Ordered {
    private static final Pattern SCHEMA = Pattern.compile("/prepared_interview(?:_[a-z0-9]+)*");
    private static final Pattern BUCKET = Pattern.compile("prepared-interview-[a-z0-9][a-z0-9-]{0,43}");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String genericUrl = env.getProperty("spring.datasource.url");
        String hikariUrl = env.getProperty("spring.datasource.hikari.jdbc-url");
        if (genericUrl == null && hikariUrl == null) fail("spring.datasource.url");
        if (genericUrl != null) requirePreparedDatabase(genericUrl);
        if (hikariUrl != null) requirePreparedDatabase(hikariUrl);
        String redis = env.getProperty("spring.data.redis.database", "0");
        if (!redis.matches("[1-9]|1[0-5]")) fail("spring.data.redis.database");
        String cookie = env.getProperty("sa-token.token-name", "");
        if (!cookie.matches("prepared-interview-[a-z0-9-]+")) fail("sa-token.token-name");
        String bucket = env.getProperty("aliyun.oss.bucketName", "");
        if (!BUCKET.matcher(bucket).matches()) fail("aliyun.oss.bucketName");
        if (!"false".equals(env.getProperty("dashscope.realtime.enabled", "true"))) {
            fail("dashscope.realtime.enabled");
        }
        if (!"false".equals(env.getProperty("data-retention.enabled", "true"))) {
            fail("data-retention.enabled");
        }
        if (!"stop".equals(env.getProperty("scheduler.start", "start"))) fail("scheduler.start");
        requireApprovedTestBudget(env);
    }

    private static void requireApprovedTestBudget(ConfigurableEnvironment env) {
        try {
            BigDecimal total = new BigDecimal(env.getProperty("prepared.models.test-budget-cny", "100"));
            BigDecimal pause = new BigDecimal(env.getProperty("prepared.models.pause-cny", "80"));
            if (total.signum() < 0 || total.compareTo(new BigDecimal("100")) > 0
                    || pause.signum() < 0 || pause.compareTo(new BigDecimal("80")) > 0 || pause.compareTo(total) > 0) {
                fail("prepared.models.test-budget-cny / pause-cny");
            }
        } catch (NumberFormatException error) {
            fail("prepared.models.test-budget-cny / pause-cny");
        }
    }

    private static void requirePreparedDatabase(String jdbcUrl) {
        try {
            if (!jdbcUrl.startsWith("jdbc:mysql://")) fail("spring.datasource.url");
            URI uri = URI.create(jdbcUrl.substring(5));
            if (uri.getHost() == null || !SCHEMA.matcher(uri.getPath()).matches()) {
                fail("spring.datasource.url");
            }
        } catch (IllegalArgumentException | NullPointerException error) {
            fail("spring.datasource.url");
        }
    }

    private static void fail(String setting) {
        // Never include a URL, credential or host in a public/configuration error.
        throw new IllegalStateException("Prepared deployment requires isolated configuration: " + setting);
    }

    @Override
    public int getOrder() {
        // ConfigData has already resolved profile/environment placeholders; no beans exist yet.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
