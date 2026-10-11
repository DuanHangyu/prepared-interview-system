package system.assessment.defense.security;

import org.junit.jupiter.api.Test;
import system.assessment.defense.application.service.RedisRateLimitCounter;
import system.assessment.defense.application.service.SecurityRateLimitService;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityRateLimitServiceTest {
    @Test void sharedNonLoginLimitsStillRejectExceededCountersInsteadOfFallingBack() {
        var counter=mock(RedisRateLimitCounter.class);
        when(counter.increment(anyString(),anyInt(),any(Duration.class))).thenReturn(1L,11L);
        var limiter=sharedLimiter(new MutableClock(),counter);
        assertThatCode(()->limiter.checkAiHttp("campus",null,0)).doesNotThrowAnyException();
        assertThatThrownBy(()->limiter.checkAiHttp("campus",null,0)).isInstanceOf(BusinessException.class);
    }

    @Test void unavailableSharedNonLoginCountersKeepBoundedFallbackAndWindowRecovery() {
        var counter=mock(RedisRateLimitCounter.class);
        when(counter.increment(anyString(),anyInt(),any(Duration.class))).thenThrow(new IllegalStateException("test dependency unavailable"));
        var clock=new MutableClock();var limiter=sharedLimiter(clock,counter);
        for(int n=0;n<10;n++)limiter.checkAiHttp("campus",null,0);
        assertThatThrownBy(()->limiter.checkAiHttp("campus",null,0)).isInstanceOf(BusinessException.class);
        clock.advance(Duration.ofMinutes(1));
        assertThatCode(()->limiter.checkAiHttp("campus",null,0)).doesNotThrowAnyException();
    }

    @Test void oversizedModelCostAndAnonymousFileTrafficRemainProtected() {
        var limiter=limiter(new MutableClock());
        assertThatThrownBy(()->limiter.checkAiHttp("campus",17,6)).isInstanceOf(BusinessException.class);
        assertThatCode(()->limiter.checkAiHttp("other-campus",null,0)).doesNotThrowAnyException();
        var defaults=new SecurityRateLimitService();
        for(int n=0;n<100;n++)defaults.checkFileUpload("campus",null);
        assertThatThrownBy(()->defaults.checkFileUpload("campus",null)).isInstanceOf(BusinessException.class);
    }

    @Test void teacherReviewHasIndependentAccountReadWriteLimitsWithoutConsumingStudentRecordingAllowance(){
        var clock=new MutableClock();var limiter=limiter(clock);
        for(int actor=1;actor<=120;actor++){
            for(int n=0;n<120;n++)limiter.checkPreparedReview(actor,false);
            for(int n=0;n<30;n++)limiter.checkPreparedReview(actor,true);
            limiter.checkPreparedUpload(actor,true);limiter.checkPreparedInterview(actor,true);
        }
        assertThatThrownBy(()->limiter.checkPreparedReview(1,false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->limiter.checkPreparedReview(1,true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->limiter.checkPreparedReview(null,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->limiter.checkPreparedReview(0,false)).isInstanceOf(IllegalArgumentException.class);
        clock.advance(Duration.ofMinutes(1));assertThatCode(()->limiter.checkPreparedReview(1,true)).doesNotThrowAnyException();
    }
    @Test void chunkTrafficIsPerUserAndDoesNotConsumeQuestionCommandOrCampusQuota() {
        MutableClock clock=new MutableClock();var limiter=limiter(clock);
        for(int student=1;student<=120;student++) {
            int currentStudent=student;
            for(int write=0;write<120;write++)limiter.checkPreparedUpload(student,true);
            for(int read=0;read<30;read++)limiter.checkPreparedUpload(student,false);
            assertThatCode(()->limiter.checkPreparedInterview(currentStudent,true)).doesNotThrowAnyException();
        }
        assertThatThrownBy(()->limiter.checkPreparedUpload(1,true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->limiter.checkPreparedUpload(1,false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->limiter.checkPreparedUpload(null,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->limiter.checkPreparedUpload(0,false)).isInstanceOf(IllegalArgumentException.class);
        clock.advance(Duration.ofMinutes(1));assertThatCode(()->limiter.checkPreparedUpload(1,true)).doesNotThrowAnyException();
    }

    @Test void preparedInterviewReadsAndWritesHaveSeparatePerStudentBudgetsWithoutCampusIpQuota() {
        MutableClock clock=new MutableClock();var limiter=limiter(clock);
        for(int student=1;student<=120;student++) {
            for(int read=0;read<120;read++)limiter.checkPreparedInterview(student,false);
            for(int write=0;write<60;write++)limiter.checkPreparedInterview(student,true);
        }
        assertThatThrownBy(()->limiter.checkPreparedInterview(1,false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->limiter.checkPreparedInterview(1,true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->limiter.checkPreparedInterview(null,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->limiter.checkPreparedInterview(0,false)).isInstanceOf(IllegalArgumentException.class);
        clock.advance(Duration.ofMinutes(1));assertThatCode(()->limiter.checkPreparedInterview(1,true)).doesNotThrowAnyException();
    }


    @Test
    void loginIsLimitedPerRemoteAddressAndRecoversAfterWindow() {
        MutableClock clock = new MutableClock();
        SecurityRateLimitService limiter = limiter(clock);

        assertThatCode(() -> {
            limiter.checkLoginAttempt("127.0.0.1");
            limiter.checkLoginAttempt("127.0.0.1");
        }).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkLoginAttempt("127.0.0.1"))
                .isInstanceOf(BusinessException.class);

        clock.advance(Duration.ofMinutes(6));
        assertThatCode(() -> limiter.checkLoginAttempt("127.0.0.1"))
                .doesNotThrowAnyException();
    }

    @Test
    void aiHttpChecksIpUserAndCostBeforeInvocation() {
        SecurityRateLimitService limiter = limiter(new MutableClock());

        limiter.checkAiHttp("10.0.0.1", 7, 3);
        assertThatThrownBy(() -> limiter.checkAiHttp("10.0.0.1", 7, 3))
                .isInstanceOf(BusinessException.class);

        assertThatCode(() -> limiter.checkAiHttp("10.0.0.2", 8, 1))
                .doesNotThrowAnyException();
    }

    @Test
    void websocketAiBudgetIsIsolatedPerUser() {
        SecurityRateLimitService limiter = limiter(new MutableClock());

        limiter.checkAiWebSocket(12, 4);
        assertThatThrownBy(() -> limiter.checkAiWebSocket(12, 2))
                .isInstanceOf(BusinessException.class);
        assertThatCode(() -> limiter.checkAiWebSocket(13, 4))
                .doesNotThrowAnyException();
    }

    @Test
    void fileUploadsAreBoundedPerAuthenticatedUser() {
        SecurityRateLimitService limiter = new SecurityRateLimitService();

        for (int attempt = 0; attempt < 20; attempt++) {
            limiter.checkFileUpload("10.0.0.1", 7);
        }
        assertThatThrownBy(() -> limiter.checkFileUpload("10.0.0.1", 7))
                .isInstanceOf(BusinessException.class);
        assertThatCode(() -> limiter.checkFileUpload("10.0.0.1", 8))
                .doesNotThrowAnyException();
    }

    @Test
    void redisCounterSharesLimitsAcrossApplicationInstances() {
        RedisRateLimitCounter counter = mock(RedisRateLimitCounter.class);
        AtomicLong sharedUsage = new AtomicLong();
        when(counter.reserveLogin(anyString(), any(Duration.class), anyInt()))
                .thenAnswer(invocation -> {
                    int limit = invocation.getArgument(2);
                    return sharedUsage.incrementAndGet() <= limit ? "shared-window" : "";
                });
        MutableClock clock = new MutableClock();
        SecurityRateLimitService firstInstance = sharedLimiter(clock, counter);
        SecurityRateLimitService secondInstance = sharedLimiter(clock, counter);

        firstInstance.checkLoginAttempt("10.0.0.1");
        secondInstance.checkLoginAttempt("10.0.0.1");

        assertThatThrownBy(() -> firstInstance.checkLoginAttempt("10.0.0.1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void redisFailureFallsBackToLocalLimits() {
        RedisRateLimitCounter counter = mock(RedisRateLimitCounter.class);
        when(counter.reserveLogin(anyString(), any(Duration.class), anyInt()))
                .thenThrow(new IllegalStateException("redis unavailable"));
        SecurityRateLimitService limiter = sharedLimiter(new MutableClock(), counter);

        limiter.checkLoginAttempt("10.0.0.1");
        limiter.checkLoginAttempt("10.0.0.1");

        assertThatThrownBy(() -> limiter.checkLoginAttempt("10.0.0.1"))
                .isInstanceOf(BusinessException.class);
    }

    private SecurityRateLimitService limiter(Clock clock) {
        return new SecurityRateLimitService(clock, testPolicy());
    }

    private SecurityRateLimitService sharedLimiter(Clock clock, RedisRateLimitCounter counter) {
        return new SecurityRateLimitService(clock, testPolicy(), counter);
    }

    private SecurityRateLimitService.Policy testPolicy() {
        return new SecurityRateLimitService.Policy(
                2, Duration.ofMinutes(5),
                10, 10, Duration.ofMinutes(1),
                5, Duration.ofMinutes(10),
                10, Duration.ofMinutes(1),
                5, Duration.ofMinutes(10));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-10-02T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
