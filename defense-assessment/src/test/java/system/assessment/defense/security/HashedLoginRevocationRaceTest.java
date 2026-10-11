package system.assessment.defense.security;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.RepeatedTest;
import org.mockito.MockedStatic;
import system.assessment.defense.application.dto.LoginDTO;
import system.assessment.defense.application.service.LoginService;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.application.service.SecurityRateLimitService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual BCrypt12 and LoginService. Database/revocation/session are controlled doubles,
 * not live HTTP, Redis, concurrent threads or complete authentication acceptance. */
class HashedLoginRevocationRaceTest {
    private static final int USER_ID = 7;
    private static final String ACCOUNT = "fixture-revocation-student";
    private static final String ORIGINAL_PASSWORD = "Fixture-old-password!";
    private static final String REPLACEMENT_PASSWORD = "Fixture-new-password!";

    private final UserService users = mock(UserService.class);
    private final PasswordSecurityService passwords = spy(new PasswordSecurityService());
    private final SecurityRateLimitService limits = mock(SecurityRateLimitService.class);
    private final LoginService login = new LoginService(users, passwords, limits);
    private final AtomicBoolean activeSession = new AtomicBoolean();
    private final AtomicReference<UserPO> persisted = new AtomicReference<>();

    private UserPO snapshot(UserPO user) {
        return UserPO.builder().id(user.getId()).account(user.getAccount())
                .password(user.getPassword()).name("fixture-profile").type(0).build();
    }

    private String configureAccount() {
        String hash = passwords.encode(ORIGINAL_PASSWORD);
        persisted.set(UserPO.builder().id(USER_ID).account(ACCOUNT).password(hash).type(0).build());
        when(users.findByAccount(ACCOUNT)).thenAnswer(call -> Optional.of(snapshot(persisted.get())));
        when(users.findById(USER_ID)).thenAnswer(call -> Optional.of(snapshot(persisted.get())));
        return hash;
    }

    private MockedStatic<StpUtil> controlledSessions() {
        var session = mockStatic(StpUtil.class);
        var tokenSession = new UnitTokenSession("fixture-revocation-token-session");
        session.when(() -> StpUtil.login(USER_ID)).thenAnswer(call -> {
            activeSession.set(true);
            return null;
        });
        session.when(() -> StpUtil.logout(USER_ID)).thenAnswer(call -> {
            activeSession.set(false);
            return null;
        });
        session.when(StpUtil::logout).thenAnswer(call -> {
            activeSession.set(false);
            return null;
        });
        session.when(StpUtil::getTokenSession).thenReturn(tokenSession);
        session.when(StpUtil::getTokenValue).thenReturn("fixture-revocation-token");
        session.when(StpUtil::getLoginIdAsInt).thenReturn(USER_ID);
        session.when(StpUtil::getLoginId).thenReturn(USER_ID);
        return session;
    }

    private LoginDTO command(String password) {
        var command = new LoginDTO();
        command.setAccount(ACCOUNT);
        command.setPassword(password);
        return command;
    }

    @RepeatedTest(3)
    void completedPasswordWriteAndRevocationCannotBeFollowedByAnOldPasswordSession() {
        String readBeforeWrite = configureAccount();
        String replacement = passwords.encode(REPLACEMENT_PASSWORD);
        doAnswer(call -> {
            var verified = (PasswordSecurityService.PasswordVerification) call.callRealMethod();
            assertThat(verified.matched()).isTrue();
            assertThat(verified.upgradedHash()).isNull();
            // The original snapshot already passed BCrypt. Model a completed
            // independent password write + existing logout(id) BEFORE token issue.
            persisted.set(UserPO.builder().id(USER_ID).account(ACCOUNT).password(replacement).type(0).build());
            StpUtil.logout(USER_ID);
            return verified;
        }).when(passwords).verify(ORIGINAL_PASSWORD, readBeforeWrite);

        try (var session = controlledSessions()) {
            assertThatThrownBy(() -> login.login(command(ORIGINAL_PASSWORD)))
                    .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(401);
            assertThat(activeSession).isFalse();
            assertThat(persisted.get().getPassword()).isEqualTo(replacement);
            verify(limits).checkLoginIdentity(USER_ID);
            verify(users, never()).compareAndSetPassword(anyInt(), anyString(), anyString());
            verify(users, never()).updateById(any(UserPO.class));
        }
    }

    @RepeatedTest(3)
    void anUnchangedCurrentHashedCredentialCanStillLogIn() {
        String current = configureAccount();
        try (var session = controlledSessions()) {
            login.login(command(ORIGINAL_PASSWORD));
            assertThat(activeSession).isTrue();
            assertThat(persisted.get().getPassword()).isEqualTo(current);
            verify(limits).checkLoginIdentity(USER_ID);
            verify(users, never()).compareAndSetPassword(anyInt(), anyString(), anyString());
            session.verify(StpUtil::logout, never());
            session.verify(() -> StpUtil.logout(USER_ID), never());
        }
    }

    @RepeatedTest(3)
    void anIncorrectPasswordStillFailsWithoutCreatingASession() {
        configureAccount();
        try (var session = controlledSessions()) {
            assertThatThrownBy(() -> login.login(command("Fixture-wrong-password!")))
                    .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(401);
            assertThat(activeSession).isFalse();
            verify(limits).checkLoginIdentity(USER_ID);
            session.verifyNoInteractions();
        }
    }
}
