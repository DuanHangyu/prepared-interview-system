package system.assessment.defense.security;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import system.assessment.defense.application.dto.LoginDTO;
import system.assessment.defense.application.service.LoginService;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.application.service.SecurityRateLimitService;
import system.assessment.defense.infrastructure.config.CsrfOriginInterceptor;
import system.assessment.defense.infrastructure.config.SaTokenConfig;
import system.assessment.defense.infrastructure.config.SecurityRateLimitInterceptor;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real BCrypt12, LoginService and registered HTTP auth callback. Persistence,
 * Sa-Token context and revocation are controlled doubles, not live HTTP/Redis. */
class CredentialSessionGuardTest {
    private static final String PASSWORD = "Fixture-guard-password!";
    private static final String ORIGINAL = new PasswordSecurityService().encode(PASSWORD);
    private static final String REPLACEMENT = new PasswordSecurityService().encode("Fixture-replacement-password!");
    private static final String KEY = "prepared.verifiedCredential.v1";
    private final UserService users = mock(UserService.class);
    private final LoginService login = new LoginService(users, new PasswordSecurityService(), mock(SecurityRateLimitService.class));
    private final SaSession token = new UnitTokenSession("fixture-guard-token");
    private final AtomicReference<UserPO> persisted = new AtomicReference<>(user(7, ORIGINAL, 0));
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean entered = new AtomicBoolean();
    private int tokenUserId = 7;

    private static UserPO user(int id, String hash, Integer role) {
        return UserPO.builder().id(id).account("fixture-guard-student").password(hash).type(role).build();
    }

    private MockedStatic<StpUtil> context() {
        when(users.findByAccount("fixture-guard-student")).thenAnswer(call -> Optional.of(user(7, ORIGINAL, 0)));
        when(users.findById(anyInt())).thenAnswer(call -> Optional.ofNullable(persisted.get()));
        var context = mockStatic(StpUtil.class);
        context.when(() -> StpUtil.login(7)).thenAnswer(call -> { active.set(true); return null; });
        context.when(StpUtil::getLoginIdAsInt).thenAnswer(call -> tokenUserId);
        context.when(StpUtil::getTokenSession).thenReturn(token);
        context.when(StpUtil::logout).thenAnswer(call -> { active.set(false); return null; });
        return context;
    }

    private void login() {
        var command = new LoginDTO(); command.setAccount("fixture-guard-student"); command.setPassword(PASSWORD);
        login.login(command);
    }

    @SuppressWarnings("unchecked")
    private void protectedCallback() {
        var registry = new InterceptorRegistry();
        new SaTokenConfig(mock(SecurityRateLimitInterceptor.class), mock(CsrfOriginInterceptor.class), users).addInterceptors(registry);
        var registrations = (List<InterceptorRegistration>) ReflectionTestUtils.getField(registry, "registrations");
        var guard = registrations.stream().map(r -> ReflectionTestUtils.getField(r, "interceptor"))
                .filter(SaInterceptor.class::isInstance).map(SaInterceptor.class::cast).findFirst().orElseThrow();
        guard.auth.run(null);
        entered.set(true);
    }

    private void rejected() {
        assertThatThrownBy(this::protectedCallback).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(401);
        assertThat(entered).isFalse();
    }

    @RepeatedTest(3)
    void replacementAndRevocationDuringTokenIssueCannotBlessOldVerification() {
        try (var context = context()) {
            context.when(() -> StpUtil.login(7)).thenAnswer(call -> {
                active.set(true);
                persisted.set(user(7, REPLACEMENT, 0));
                return null;
            });
            assertThatThrownBy(this::login).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(401);
            assertThat(active).isFalse();
            rejected();
        }
    }

    @RepeatedTest(3)
    void committedPasswordChangeRejectsAnOtherwisePresentTokenBeforeProtectedCode() {
        try (var context = context()) {
            login(); persisted.set(user(7, REPLACEMENT, 0)); rejected();
            assertThat(active).isFalse();
        }
    }

    @RepeatedTest(3)
    void failedRedisRevocationCannotMakeTheStaleTokenPassASecondRequest() {
        try (var context = context()) {
            login(); persisted.set(user(7, REPLACEMENT, 0));
            context.when(StpUtil::logout).thenThrow(new IllegalStateException("fixture-revocation-unavailable"));
            // Mockito re-stubbing invokes the previous answer while recording.
            // Restore the intended still-present token AFTER fault setup.
            active.set(true);
            rejected(); rejected();
            assertThat(active).isTrue(); // Prove rejection was not delegated to the failed revocation double.
        }
    }

    @Test void currentCredentialStillPassesTheConfiguredGuard() {
        try (var context = context()) {
            login(); protectedCallback(); assertThat(entered).isTrue();
            context.verify(StpUtil::checkLogin);
            context.verify(StpUtil::logout, never());
        }
    }

    @Test void anUnboundLegacyTokenIsRejectedInsteadOfAdoptingTheCurrentCredential() {
        try (var context = context()) { active.set(true); rejected(); assertThat(token.get(KEY)).isNull(); }
    }

    @Test void malformedBindingIsRejected() {
        try (var context = context()) { login(); token.set(KEY, ORIGINAL); rejected(); }
    }

    @Test void bindingCannotBeMovedToAnotherIdentityEvenWithTheSamePasswordHash() {
        try (var context = context()) { login(); tokenUserId=8; persisted.set(user(8, ORIGINAL, 0)); rejected(); }
    }

    @Test void deletingTheAccountRejectsItsPresentToken() {
        try (var context = context()) { login(); persisted.set(null); rejected(); }
    }

    @Test void anInvalidStoredRoleIsRejected() {
        try (var context = context()) { login(); persisted.set(user(7, ORIGINAL, 9)); rejected(); }
    }

    @Test void aNullStoredRoleIsRejected() {
        try (var context = context()) { login(); persisted.set(user(7, ORIGINAL, null)); rejected(); }
    }

    @Test void anUnavailableAuthoritativeDatabaseNeverEntersProtectedCode() {
        try (var context = context()) {
            login(); when(users.findById(7)).thenThrow(new IllegalStateException("fixture-database-unavailable"));
            assertThatThrownBy(this::protectedCallback).isInstanceOf(IllegalStateException.class);
            assertThat(entered).isFalse();
            context.verify(StpUtil::logout, never());
        }
    }

    @Test void sessionBindingContainsNeitherThePasswordNorItsStoredBcryptValue() {
        try (var context = context()) {
            login(); assertThat(token.get(KEY)).isInstanceOf(String.class);
            assertThat((String) token.get(KEY)).matches("[a-f0-9]{64}").doesNotContain(PASSWORD, ORIGINAL);
        }
    }
}
