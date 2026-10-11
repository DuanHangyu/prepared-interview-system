package system.assessment.defense.security;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import system.assessment.defense.application.service.RedisRateLimitCounter;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class LoginAdmissionRedisIntegrationTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate template;
    private RedisRateLimitCounter first, second;
    private String key, redisKey;

    @BeforeEach void setup() {
        String host = System.getenv("REDIS_INTEGRATION_HOST");
        Assumptions.assumeTrue(host != null && !host.isBlank(), "Enable real Redis integration");
        connection = new LettuceConnectionFactory(host,
                Integer.parseInt(System.getenv().getOrDefault("REDIS_INTEGRATION_PORT", "6379")));
        connection.afterPropertiesSet(); connection.start();
        template = new StringRedisTemplate(connection); template.afterPropertiesSet();
        first = new RedisRateLimitCounter(template); second = new RedisRateLimitCounter(template);
        key = "login-admission-test:" + UUID.randomUUID();
        redisKey = "defense:security:rate-limit:" + key;
    }

    @AfterEach void cleanup() {
        if (template != null) template.delete(redisKey); // Only this newly generated fixture key.
        if (connection != null) connection.destroy();
    }

    @Test void realSharedReservationIsAtomicAndBoundedAcrossInstances() {
        String generation = first.reserveLogin(key, Duration.ofMinutes(5), 2);
        assertThat(UUID.fromString(generation)).isNotNull();
        assertThat(second.reserveLogin(key, Duration.ofMinutes(5), 2)).isEqualTo(generation);
        assertThat(first.reserveLogin(key, Duration.ofMinutes(5), 2)).isEmpty();
        assertThat(template.opsForHash().get(redisKey, "used")).isEqualTo("2");
        second.refundLogin(key, generation);
        assertThat(first.reserveLogin(key, Duration.ofMinutes(5), 2)).isEqualTo(generation);
        assertThat(second.reserveLogin(key, Duration.ofMinutes(5), 2)).isEmpty();
    }

    @Test void staleGenerationCannotEraseNewFailuresAndRefundNeverRearmsOrExtendsTheWindow() {
        String old = first.reserveLogin(key, Duration.ofMinutes(5), 1);
        assertThat(template.getExpire(redisKey)).isBetween(1L, 300L);
        template.delete(redisKey); // Simulate expiry/eviction of this exact owned generation.
        String current = second.reserveLogin(key, Duration.ofSeconds(30), 1);
        assertThat(current).isNotEqualTo(old);
        first.refundLogin(key, old);
        assertThat(first.reserveLogin(key, Duration.ofMinutes(5), 1)).isEmpty();
        second.refundLogin(key, current);
        first.refundLogin(key, current); // No negative balance.
        assertThat(template.opsForHash().get(redisKey, "used")).isEqualTo("0");
        assertThat(first.reserveLogin(key, Duration.ofMinutes(5), 1)).isEqualTo(current);
        assertThat(template.getExpire(redisKey)).isBetween(1L, 30L);
        template.delete(redisKey);
        first.refundLogin(key, current);
        assertThat(template.hasKey(redisKey)).isFalse();
    }

    @Test void invalidReservationsCannotCreateKeys() {
        assertThatThrownBy(() -> first.reserveLogin(key, Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> first.reserveLogin(key, Duration.ofSeconds(1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(template.hasKey(redisKey)).isFalse();
    }
}
