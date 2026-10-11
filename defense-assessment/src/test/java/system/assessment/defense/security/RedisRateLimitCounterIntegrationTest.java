package system.assessment.defense.security;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import system.assessment.defense.application.service.RedisRateLimitCounter;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RedisRateLimitCounterIntegrationTest {

    @Test
    void counterIsSharedAndExpiresInRealRedis() {
        String host = System.getenv("REDIS_INTEGRATION_HOST");
        Assumptions.assumeTrue(host != null && !host.isBlank(),
                "Set REDIS_INTEGRATION_HOST to run the Redis integration test");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_INTEGRATION_PORT", "6379"));
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        String key = "integration:" + UUID.randomUUID();
        String redisKey = "defense:security:rate-limit:" + key;

        try {
            RedisRateLimitCounter firstInstance = new RedisRateLimitCounter(template);
            RedisRateLimitCounter secondInstance = new RedisRateLimitCounter(template);

            assertThat(firstInstance.increment(key, 1, Duration.ofSeconds(30))).isEqualTo(1);
            assertThat(secondInstance.increment(key, 2, Duration.ofSeconds(30))).isEqualTo(3);
            assertThat(template.getExpire(redisKey)).isBetween(1L, 30L);
        } finally {
            template.delete(redisKey);
            connectionFactory.destroy();
        }
    }
}
