package system.assessment.defense.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import system.assessment.defense.application.service.RedisRateLimitCounter;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginAdmissionRedisFailureTest {
    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    private final RedisRateLimitCounter counter = new RedisRateLimitCounter(template);

    @Test void missingSharedReservationReplyIsUncertainFailureNotAPermit() {
        assertThatThrownBy(() -> counter.reserveLogin("owned-test",Duration.ofSeconds(30),2))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void missingRefundReplyMustBeReportedAsUncertainNotSuccessful() {
        assertThatThrownBy(() -> counter.refundLogin("owned-test","server-generation"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void nullAndBlankRefundGenerationsNeverReachRedis() {
        assertThatThrownBy(() -> counter.refundLogin("owned-test",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counter.refundLogin("owned-test"," ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counter.refundLogin(null,"generation")).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(template);
    }

    @Test void invalidIncrementWeightAndWindowFailBeforeSharedMutation() {
        for (int weight : new int[]{0,-1}) assertThatThrownBy(() -> counter.increment("owned-test",weight,Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        for (var window : new Duration[]{Duration.ZERO,Duration.ofMillis(-1)})
            assertThatThrownBy(() -> counter.increment("owned-test",1,window)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counter.increment(null,1,Duration.ofSeconds(30))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> counter.increment("owned-test",1,null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(template);
    }

    @Test void missingIncrementReplyIsAnErrorRatherThanZeroUsage() {
        assertThatThrownBy(() -> counter.increment("owned-test",1,Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void invalidReservationArgumentsFailBeforeSharedMutation() {
        assertThatThrownBy(() -> counter.reserveLogin(null,Duration.ofSeconds(30),1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> counter.reserveLogin("owned-test",null,1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> counter.reserveLogin("owned-test",Duration.ZERO,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counter.reserveLogin("owned-test",Duration.ofSeconds(30),0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(template);
    }
}
