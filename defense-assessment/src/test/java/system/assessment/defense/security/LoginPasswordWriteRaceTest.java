package system.assessment.defense.security;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.RepeatedTest;
import org.mockito.MockedStatic;
import system.assessment.defense.application.dto.ChangePasswordDTO;
import system.assessment.defense.application.dto.LoginDTO;
import system.assessment.defense.application.service.LoginService;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.application.service.SecurityRateLimitService;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Controlled interleavings at the persistence boundary, not live database or device acceptance. */
class LoginPasswordWriteRaceTest {
    private final UserService users = mock(UserService.class);
    private final PasswordSecurityService passwords = mock(PasswordSecurityService.class);
    private final SecurityRateLimitService limits = mock(SecurityRateLimitService.class);
    private final LoginService login = new LoginService(users, passwords, limits);

    private UserPO readBeforeConcurrentWrite() {
        return UserPO.builder().id(7).account("fixture-account").password("original-credential")
                .name("original-name").type(0).build();
    }

    private AtomicReference<UserPO> concurrentReplacement() {
        var persisted = new AtomicReference<>(UserPO.builder().id(7).account("fixture-account")
                .password("concurrent-replacement").name("updated-name").type(1).build());
        // Model the original unconditional whole-row UPDATE after the read has become stale.
        when(users.updateById(any(UserPO.class))).thenAnswer(call -> {
            persisted.set(call.getArgument(0));
            return true;
        });
        return persisted;
    }

    @RepeatedTest(3)
    void staleLegacyUpgradeCannotRestoreAnOldCredentialOrCreateASession() {
        var stale = readBeforeConcurrentWrite();
        var persisted = concurrentReplacement();
        when(users.findByAccount("fixture-account")).thenReturn(Optional.of(stale));
        when(passwords.verify("original-credential", "original-credential"))
                .thenReturn(new PasswordSecurityService.PasswordVerification(true, "upgraded-original"));
        var command = new LoginDTO();
        command.setAccount("fixture-account");
        command.setPassword("original-credential");

        try (MockedStatic<StpUtil> session = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> login.login(command)).isInstanceOf(RuntimeException.class);
            assertThat(persisted.get().getPassword()).isEqualTo("concurrent-replacement");
            assertThat(persisted.get().getName()).isEqualTo("updated-name");
            assertThat(persisted.get().getType()).isEqualTo(1);
            verify(users, never()).updateById(any(UserPO.class));
            session.verifyNoInteractions();
        }
    }

    @RepeatedTest(3)
    void stalePasswordChangeCannotOverwriteTheWinnerOrRevokeItsSessions() {
        var stale = readBeforeConcurrentWrite();
        var persisted = concurrentReplacement();
        when(users.findById(7)).thenReturn(Optional.of(stale));
        when(passwords.verify("original-credential", "original-credential"))
                .thenReturn(new PasswordSecurityService.PasswordVerification(true, null));
        when(passwords.verify("requested-replacement", "original-credential"))
                .thenReturn(new PasswordSecurityService.PasswordVerification(false, null));
        when(passwords.encode("requested-replacement")).thenReturn("encoded-requested-replacement");
        var command = new ChangePasswordDTO();
        command.setCurrentPassword("original-credential");
        command.setNewPassword("requested-replacement");

        try (MockedStatic<StpUtil> session = mockStatic(StpUtil.class)) {
            assertThat(login.changePassword(7, command)).isFalse();
            assertThat(persisted.get().getPassword()).isEqualTo("concurrent-replacement");
            assertThat(persisted.get().getName()).isEqualTo("updated-name");
            assertThat(persisted.get().getType()).isEqualTo(1);
            verify(users, never()).updateById(any(UserPO.class));
            session.verifyNoInteractions();
        }
    }
}
