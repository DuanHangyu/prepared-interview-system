package system.assessment.defense.application.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Redis-backed fixed-window counter shared by every application instance.
 */
@Component
public class RedisRateLimitCounter {

    private static final String KEY_PREFIX = "defense:security:rate-limit:";
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCRBY', KEYS[1], ARGV[1])
            if current == tonumber(ARGV[1]) then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return current
            """, Long.class);

    // One key, one atomic window. Success refunds neither extend TTL nor reset failure history.
    private static final DefaultRedisScript<String> LOGIN_RESERVE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                redis.call('HSET', KEYS[1], 'generation', ARGV[3], 'used', 0)
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            if tonumber(redis.call('HGET', KEYS[1], 'used')) >= tonumber(ARGV[2]) then
                return ''
            end
            redis.call('HINCRBY', KEYS[1], 'used', 1)
            return redis.call('HGET', KEYS[1], 'generation')
            """, String.class);
    private static final DefaultRedisScript<Long> LOGIN_REFUND_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1
                and redis.call('HGET', KEYS[1], 'generation') == ARGV[1]
                and tonumber(redis.call('HGET', KEYS[1], 'used')) > 0 then
                redis.call('HINCRBY', KEYS[1], 'used', -1)
                return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimitCounter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public long increment(String key, int weight, Duration window) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(window, "window");
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be positive");
        }
        long windowMillis = window.toMillis();
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("window must be positive");
        }

        Long value = redisTemplate.execute(
                INCREMENT_SCRIPT,
                List.of(KEY_PREFIX + key),
                Integer.toString(weight),
                Long.toString(windowMillis));
        if (value == null) {
            throw new IllegalStateException("Redis rate-limit script returned no result");
        }
        return value;
    }

    /** Empty means denied; a generation is a reservation, not an authentication result. */
    public String reserveLogin(String key, Duration window, int limit) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(window, "window");
        if (window.toMillis() <= 0 || limit <= 0) throw new IllegalArgumentException("Invalid login window");
        String generation = redisTemplate.execute(LOGIN_RESERVE_SCRIPT, List.of(KEY_PREFIX + key),
                Long.toString(window.toMillis()), Integer.toString(limit), UUID.randomUUID().toString());
        if (generation == null) throw new IllegalStateException("No login reservation result");
        return generation;
    }

    /** Called once by the server-owned ticket after credentials, role and session creation succeed. */
    public void refundLogin(String key, String generation) {
        Objects.requireNonNull(key, "key");
        if (generation == null || generation.isBlank()) throw new IllegalArgumentException("Missing generation");
        Long result = redisTemplate.execute(LOGIN_REFUND_SCRIPT, List.of(KEY_PREFIX + key), generation);
        if (result == null) throw new IllegalStateException("No login refund result");
    }
}
