package system.assessment.defense.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 固定窗口限流器。生产环境使用 Redis 共享计数，Redis 故障时降级为本机计数。
 */
@Service
public class SecurityRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(SecurityRateLimitService.class);
    private static final int MAX_TRACKED_IDENTITIES = 100_000;
    private static final int FILE_UPLOAD_USER_LIMIT = 20;
    private static final int FILE_UPLOAD_IP_LIMIT = 100;
    private static final Duration FILE_UPLOAD_WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final Policy policy;
    private final RedisRateLimitCounter redisCounter;
    private final Cache<String, WindowCounter> counters;
    private final AtomicBoolean redisFailureLogged = new AtomicBoolean();

    public SecurityRateLimitService() {
        this(Clock.systemUTC(), Policy.productionDefaults(), null);
    }

    public SecurityRateLimitService(Clock clock, Policy policy) {
        this(clock, policy, null);
    }

    @Autowired
    public SecurityRateLimitService(RedisRateLimitCounter redisCounter) {
        this(Clock.systemUTC(), Policy.productionDefaults(), redisCounter);
    }

    public SecurityRateLimitService(Clock clock, Policy policy, RedisRateLimitCounter redisCounter) {
        this.clock = clock;
        this.policy = policy;
        this.redisCounter = redisCounter;
        this.counters = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_IDENTITIES)
                .expireAfterAccess(Duration.ofHours(1))
                .build();
    }

    /** Failed and unfinished authentication consume the IP budget; proven success refunds only itself. */
    public LoginAttempt checkLoginAttempt(String remoteAddress) {
        String key = "login:admission:ip:" + normalizeAddress(remoteAddress);
        if (redisCounter != null) {
            String generation = null;
            try {
                generation = redisCounter.reserveLogin(key, policy.loginWindow(), policy.loginLimit());
                if (generation == null) throw new IllegalStateException("Missing login admission result");
                redisFailureLogged.set(false);
            } catch (RuntimeException error) {
                if (redisFailureLogged.compareAndSet(false, true))
                    log.warn("Shared login admission unavailable; using bounded per-instance fallback");
            }
            // A real denial must not be mistaken for Redis failure and bypassed through fallback.
            if (generation != null) {
                if (generation.isEmpty()) throw new BusinessException(ErrorCodeEnums.RATE_LIMIT_EXCEEDED);
                String reservedGeneration = generation;
                return new LoginAttempt(() -> {
                    try { redisCounter.refundLogin(key, reservedGeneration); }
                    catch (RuntimeException error) {
                        // An uncertain shared refund stays charged. Never refund against local/new state.
                        if (redisFailureLogged.compareAndSet(false, true))
                            log.warn("Shared login refund unavailable; reservation retained until expiry");
                    }
                });
            }
        }
        Instant now = clock.instant();
        WindowCounter counter = counters.get(key, ignored -> new WindowCounter(now.plus(policy.loginWindow())));
        synchronized (counter) {
            if (!now.isBefore(counter.expiresAt)) {
                counter.used = 0;
                counter.expiresAt = now.plus(policy.loginWindow());
                counter.generation = UUID.randomUUID();
            }
            if (counter.used >= policy.loginLimit()) throw new BusinessException(ErrorCodeEnums.RATE_LIMIT_EXCEEDED);
            counter.used++;
            UUID generation = counter.generation;
            return new LoginAttempt(() -> {
                synchronized (counter) {
                    if (generation.equals(counter.generation) && clock.instant().isBefore(counter.expiresAt)
                            && counter.used > 0) counter.used--;
                }
            });
        }
    }

    /** Server-local single-use capability, never serialized or accepted from a client. */
    public static final class LoginAttempt implements AutoCloseable {
        private final Runnable refund;
        private final AtomicBoolean finished = new AtomicBoolean();
        private LoginAttempt(Runnable refund) { this.refund = refund; }
        public void authenticated() { if (finished.compareAndSet(false, true)) refund.run(); }
        @Override public void close() { finished.compareAndSet(false, true); }
    }

    /** Protect a target account across network addresses; never expose its name in Redis keys. */
    public void checkLoginAccount(String account) {
        String normalized = account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
            consume("login:account:" + digest, 1, 10, Duration.ofMinutes(5));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("账号访问保护不可用");
        }
    }

    public void checkLoginIdentity(int userId) {
        if (userId <= 0) throw new IllegalArgumentException("登录身份无效");
        consume("login:user:" + userId, 1, 10, Duration.ofMinutes(5));
    }

    public void checkAiHttp(String remoteAddress, Integer userId, int estimatedCostUnits) {
        consume("ai:http:ip:" + normalizeAddress(remoteAddress), 1,
                policy.aiIpLimit(), policy.aiRequestWindow());
        if (userId == null) {
            return;
        }
        consume("ai:http:user:" + userId, 1,
                policy.aiUserLimit(), policy.aiRequestWindow());
        if (estimatedCostUnits > 0) {
            consume("ai:cost:user:" + userId, estimatedCostUnits,
                    policy.aiCostLimit(), policy.aiCostWindow());
        }
    }

    public void checkAiWebSocket(Integer userId, int estimatedCostUnits) {
        consume("ai:ws:user:" + userId, 1,
                policy.websocketActionLimit(), policy.websocketActionWindow());
        if (estimatedCostUnits > 0) {
            consume("ai:cost:user:" + userId, estimatedCostUnits,
                    policy.websocketCostLimit(), policy.websocketCostWindow());
        }
    }

    public void checkFileUpload(String remoteAddress, Integer userId) {
        consume("file:upload:ip:" + normalizeAddress(remoteAddress), 1,
                FILE_UPLOAD_IP_LIMIT, FILE_UPLOAD_WINDOW);
        if (userId != null) {
            consume("file:upload:user:" + userId, 1,
                    FILE_UPLOAD_USER_LIMIT, FILE_UPLOAD_WINDOW);
        }
    }

    /** Exam commands do not share an IP quota: a campus exit can legitimately serve all students. */
    public void checkPreparedInterview(Integer userId, boolean writing) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("学生身份无效");
        consume("prepared:interview:" + (writing ? "write:" : "read:") + userId, 1,
                writing ? 60 : 120, Duration.ofMinutes(1));
    }

    /** Audio retry traffic must never exhaust a student's start/next/end command allowance. */
    public void checkPreparedUpload(Integer userId, boolean writing) {
        if(userId==null||userId<=0)throw new IllegalArgumentException("学生身份无效");
        consume("prepared:upload:"+(writing?"write:":"read:")+userId,1,writing?120:30,Duration.ofMinutes(1));
    }

    /** Teacher review is isolated from original recording traffic and a school's shared exit IP. */
    public void checkPreparedReview(Integer userId,boolean writing) {
        if(userId==null||userId<=0)throw new IllegalArgumentException("评阅身份无效");
        consume("prepared:review:"+(writing?"write:":"read:")+userId,1,writing?30:120,Duration.ofMinutes(1));
    }

    private void consume(String key, int weight, int limit, Duration window) {
        if (weight > limit) {
            throw new BusinessException(ErrorCodeEnums.RATE_LIMIT_EXCEEDED);
        }
        if (redisCounter != null) {
            Long used = incrementSharedCounter(key, weight, window);
            if (used != null) {
                if (used > limit) {
                    throw new BusinessException(ErrorCodeEnums.RATE_LIMIT_EXCEEDED);
                }
                return;
            }
        }
        consumeLocal(key, weight, limit, window);
    }

    private Long incrementSharedCounter(String key, int weight, Duration window) {
        try {
            long used = redisCounter.increment(key, weight, window);
            redisFailureLogged.set(false);
            return used;
        } catch (RuntimeException exception) {
            if (redisFailureLogged.compareAndSet(false, true)) {
                log.warn("Redis rate-limit counter unavailable; using per-instance fallback: {}",
                        exception.toString());
            }
            return null;
        }
    }

    private void consumeLocal(String key, int weight, int limit, Duration window) {
        Instant now = clock.instant();
        WindowCounter counter = counters.get(key, ignored -> new WindowCounter(now.plus(window)));
        synchronized (counter) {
            if (!now.isBefore(counter.expiresAt)) {
                counter.used = 0;
                counter.expiresAt = now.plus(window);
            }
            if (counter.used > limit - weight) {
                throw new BusinessException(ErrorCodeEnums.RATE_LIMIT_EXCEEDED);
            }
            counter.used += weight;
        }
    }

    private String normalizeAddress(String remoteAddress) {
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    private static final class WindowCounter {
        private int used;
        private Instant expiresAt;
        private UUID generation = UUID.randomUUID();

        private WindowCounter(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }
    }

    public record Policy(
            int loginLimit,
            Duration loginWindow,
            int aiIpLimit,
            int aiUserLimit,
            Duration aiRequestWindow,
            int aiCostLimit,
            Duration aiCostWindow,
            int websocketActionLimit,
            Duration websocketActionWindow,
            int websocketCostLimit,
            Duration websocketCostWindow) {

        public static Policy productionDefaults() {
            return new Policy(
                    300, Duration.ofMinutes(5),
                    30, 20, Duration.ofMinutes(1),
                    100, Duration.ofMinutes(10),
                    60, Duration.ofMinutes(1),
                    100, Duration.ofMinutes(10));
        }
    }
}
