package system.assessment.defense.security;

import org.junit.jupiter.api.Test;
import system.assessment.defense.application.service.RedisRateLimitCounter;
import system.assessment.defense.application.service.SecurityRateLimitService;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.time.*;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginAdmissionLeaseTest {
    @Test void repeatedSuccessfulStudentsDoNotExhaustTheirSharedCampusExit() {
        var limiter = new SecurityRateLimitService();
        for (int student = 1; student <= 600; student++) {
            try (var ticket = limiter.checkLoginAttempt("campus")) {
                limiter.checkLoginIdentity(student);
                ticket.authenticated();
            }
        }
        for (int failure = 0; failure < 300; failure++) limiter.checkLoginAttempt("campus").close();
        assertLimited(limiter, "campus");
    }

    @Test void successesNeverEraseFailedAuthenticationBudget() {
        var limiter = new SecurityRateLimitService();
        for (int failure = 0; failure < 299; failure++) limiter.checkLoginAttempt("campus").close();
        for (int success = 0; success < 120; success++) limiter.checkLoginAttempt("campus").authenticated();
        limiter.checkLoginAttempt("campus").close();
        assertLimited(limiter, "campus");
    }

    @Test void unfinishedRequestsAreBoundedBeforeAnyPasswordVerification() {
        var limiter = new SecurityRateLimitService();
        var tickets = new ArrayList<SecurityRateLimitService.LoginAttempt>();
        for (int request = 0; request < 300; request++) tickets.add(limiter.checkLoginAttempt("campus"));
        assertLimited(limiter, "campus");
        tickets.getFirst().authenticated();
        limiter.checkLoginAttempt("campus").close();
        assertLimited(limiter, "campus");
    }

    @Test void ticketCompletionIsSingleUseAndLateSuccessCannotRefundFailure() {
        var limiter = new SecurityRateLimitService();
        var success = limiter.checkLoginAttempt("campus");
        success.authenticated(); success.authenticated(); success.close();
        var failed = limiter.checkLoginAttempt("campus");
        failed.close(); failed.authenticated(); failed.close();
        for (int request = 1; request < 300; request++) limiter.checkLoginAttempt("campus").close();
        assertLimited(limiter, "campus");
    }

    @Test void oldWindowSuccessCannotRefundANewWindowRequest() {
        var clock = new MutableClock();
        var limiter = new SecurityRateLimitService(clock, SecurityRateLimitService.Policy.productionDefaults());
        var old = limiter.checkLoginAttempt("campus");
        clock.advance(Duration.ofMinutes(5));
        for (int request = 0; request < 300; request++) limiter.checkLoginAttempt("campus").close();
        old.authenticated();
        assertLimited(limiter, "campus");
        clock.advance(Duration.ofMinutes(5));
        assertThatCode(() -> limiter.checkLoginAttempt("campus")).doesNotThrowAnyException();
    }

    @Test void distinctExitsAreIndependentAndMissingAddressesShareOneProtectedScope() {
        var limiter = new SecurityRateLimitService();
        for (int request = 0; request < 300; request++) limiter.checkLoginAttempt(null).close();
        assertLimited(limiter, " ");
        assertThatCode(() -> limiter.checkLoginAttempt("other-campus")).doesNotThrowAnyException();
    }

    @Test void sharedRefundUsesTheReservedGenerationExactlyOnce() {
        var redis = mock(RedisRateLimitCounter.class);
        when(redis.reserveLogin(anyString(), any(Duration.class), anyInt())).thenReturn("window-generation");
        var limiter = new SecurityRateLimitService(Clock.systemUTC(), SecurityRateLimitService.Policy.productionDefaults(), redis);
        var ticket = limiter.checkLoginAttempt("campus");
        ticket.authenticated(); ticket.authenticated(); ticket.close();
        verify(redis, times(1)).refundLogin("login:admission:ip:campus", "window-generation");
        verify(redis, never()).increment(anyString(), anyInt(), any(Duration.class));
    }

    @Test void aDeniedSharedReservationMustNotFallBackToAnEmptyLocalCounter() {
        var redis = mock(RedisRateLimitCounter.class);
        when(redis.reserveLogin(anyString(), any(Duration.class), anyInt())).thenReturn("");
        var limiter = new SecurityRateLimitService(Clock.systemUTC(), SecurityRateLimitService.Policy.productionDefaults(), redis);
        assertLimited(limiter, "campus");
        verify(redis, never()).refundLogin(anyString(), anyString());
    }

    @Test void unavailableRedisKeepsBoundedLocalReservationAndGenerationProtection() {
        var redis = mock(RedisRateLimitCounter.class);
        when(redis.reserveLogin(anyString(), any(Duration.class), anyInt())).thenThrow(new IllegalStateException("unavailable"));
        var clock = new MutableClock();
        var limiter = new SecurityRateLimitService(clock, SecurityRateLimitService.Policy.productionDefaults(), redis);
        var old = limiter.checkLoginAttempt("campus");
        clock.advance(Duration.ofMinutes(5));
        for (int request = 0; request < 300; request++) limiter.checkLoginAttempt("campus").close();
        old.authenticated(); assertLimited(limiter, "campus");
        verify(redis, never()).refundLogin(anyString(), anyString());
    }

    @Test void refundFailureNeverFailsASuccessfulLoginOrReturnsASharedPermitLocally() {
        var redis = mock(RedisRateLimitCounter.class);
        when(redis.reserveLogin(anyString(), any(Duration.class), anyInt())).thenReturn("generation", "");
        doThrow(new IllegalStateException("unavailable")).when(redis).refundLogin(anyString(), anyString());
        var limiter = new SecurityRateLimitService(Clock.systemUTC(), SecurityRateLimitService.Policy.productionDefaults(), redis);
        assertThatCode(() -> limiter.checkLoginAttempt("campus").authenticated()).doesNotThrowAnyException();
        assertLimited(limiter, "campus");
    }

    private static void assertLimited(SecurityRateLimitService limiter, String address) {
        assertThatThrownBy(() -> limiter.checkLoginAttempt(address)).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(429);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-06T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
