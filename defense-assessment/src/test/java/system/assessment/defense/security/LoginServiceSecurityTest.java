package system.assessment.defense.security;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import system.assessment.defense.application.dto.ChangePasswordDTO;
import system.assessment.defense.application.dto.LoginDTO;
import system.assessment.defense.application.service.LoginService;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.application.service.SecurityRateLimitService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginServiceSecurityTest {

    private UserService userService;
    private PasswordSecurityService passwordSecurityService;
    private SecurityRateLimitService securityRateLimitService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        passwordSecurityService = new PasswordSecurityService();
        securityRateLimitService = mock(SecurityRateLimitService.class);
    }

    @Test
    void successfulLegacyLoginMigratesPlaintextPassword() {
        UserPO user = UserPO.builder()
                .id(7)
                .account("student-7")
                .password("legacy-pass")
                .type(0)
                .build();
        when(userService.findByAccount("student-7")).thenReturn(Optional.of(user));
        when(userService.findById(7)).thenReturn(Optional.of(user));
        when(userService.compareAndSetPassword(eq(7), eq("legacy-pass"), anyString())).thenReturn(true);

        LoginDTO command = new LoginDTO();
        command.setAccount("student-7");
        command.setPassword("legacy-pass");

        SaTokenInfo tokenInfo = mock(SaTokenInfo.class);
        when(tokenInfo.getTokenValue()).thenReturn("token-value");
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getTokenInfo).thenReturn(tokenInfo);
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);
            stp.when(StpUtil::getTokenSession).thenReturn(new UnitTokenSession("fixture-legacy-login"));

            new LoginService(userService, passwordSecurityService, securityRateLimitService).login(command);

            assertThat(user.getPassword()).startsWith("$2");
            assertThat(passwordSecurityService.verify("legacy-pass", user.getPassword()).matched()).isTrue();
            verify(userService).compareAndSetPassword(7, "legacy-pass", user.getPassword());
            verify(userService, never()).updateById(any(UserPO.class));
            stp.verify(() -> StpUtil.login(7));
            stp.verify(StpUtil::getTokenInfo, never());
        }
    }

    @Test
    void failedLegacyPasswordMigrationDoesNotCreateAuthenticatedSession() {
        UserPO user = UserPO.builder()
                .id(7)
                .account("student-7")
                .password("legacy-pass")
                .type(0)
                .build();
        when(userService.findByAccount("student-7")).thenReturn(Optional.of(user));
        when(userService.compareAndSetPassword(eq(7), eq("legacy-pass"), anyString())).thenReturn(false);

        LoginDTO command = new LoginDTO();
        command.setAccount("student-7");
        command.setPassword("legacy-pass");

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> new LoginService(userService, passwordSecurityService, securityRateLimitService).login(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("密码升级失败");

            verify(userService).compareAndSetPassword(eq(7), eq("legacy-pass"), anyString());
            assertThat(user.getPassword()).isEqualTo("legacy-pass");
            verify(userService, never()).updateById(any(UserPO.class));
            stp.verifyNoInteractions();
        }
    }

    @Test
    void wrongPasswordIsRejectedForHashedAccount() {
        UserPO user = UserPO.builder()
                .id(7)
                .account("student-7")
                .password(passwordSecurityService.encode("right-pass"))
                .build();
        when(userService.findByAccount("student-7")).thenReturn(Optional.of(user));
        LoginDTO command = new LoginDTO();
        command.setAccount("student-7");
        command.setPassword("wrong-pass");

        assertThatThrownBy(() -> new LoginService(userService, passwordSecurityService, securityRateLimitService).login(command))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void invalidStoredRoleIsRejectedBeforeRenderingUserDetails() {
        UserPO user = UserPO.builder()
                .id(7)
                .name("Legacy user")
                .account("legacy-user")
                .type(9)
                .build();
        when(userService.findById(7)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> new LoginService(userService, passwordSecurityService, securityRateLimitService).userDetail(7))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(125);
    }

    @Test
    void invalidStoredRoleCannotCreateAnAuthenticatedSession() {
        UserPO user = UserPO.builder()
                .id(7)
                .account("corrupt-user")
                .password(passwordSecurityService.encode("right-pass"))
                .type(9)
                .build();
        when(userService.findByAccount("corrupt-user")).thenReturn(Optional.of(user));
        LoginDTO command = new LoginDTO();
        command.setAccount("corrupt-user");
        command.setPassword("right-pass");

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> new LoginService(userService, passwordSecurityService, securityRateLimitService).login(command))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(125);

            verify(userService, never()).updateById(any(UserPO.class));
            stp.verifyNoInteractions();
        }
    }

    @Test
    void changingPasswordStoresHashAndRevokesExistingSessions() {
        UserPO user = UserPO.builder()
                .id(7)
                .password(passwordSecurityService.encode("old-pass"))
                .build();
        when(userService.findById(7)).thenReturn(Optional.of(user));
        String originalHash = user.getPassword();
        when(userService.compareAndSetPassword(eq(7), eq(originalHash), anyString())).thenReturn(true);
        ChangePasswordDTO command = new ChangePasswordDTO();
        command.setCurrentPassword("old-pass");
        command.setNewPassword("new-pass");

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            boolean changed = new LoginService(userService, passwordSecurityService, securityRateLimitService)
                    .changePassword(7, command);

            assertThat(changed).isTrue();
            assertThat(user.getPassword()).startsWith("$2");
            assertThat(passwordSecurityService.verify("new-pass", user.getPassword()).matched()).isTrue();
            verify(userService).compareAndSetPassword(7, originalHash, user.getPassword());
            verify(userService, never()).updateById(any(UserPO.class));
            stp.verify(() -> StpUtil.logout(7));
        }
    }

    @Test
    void changingToCurrentPasswordIsRejectedEvenWhenStoredAsHash() {
        UserPO user = UserPO.builder()
                .id(7)
                .password(passwordSecurityService.encode("same-pass"))
                .build();
        when(userService.findById(7)).thenReturn(Optional.of(user));
        ChangePasswordDTO command = new ChangePasswordDTO();
        command.setCurrentPassword("same-pass");
        command.setNewPassword("same-pass");

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> new LoginService(userService, passwordSecurityService, securityRateLimitService)
                    .changePassword(7, command))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(117);

            verify(userService, never()).updateById(any(UserPO.class));
            stp.verifyNoInteractions();
        }
    }

    @Test
    void wrongCurrentPasswordDoesNotChangePasswordOrRevokeSessions() {
        String storedHash = passwordSecurityService.encode("right-current-pass");
        UserPO user = UserPO.builder().id(7).password(storedHash).build();
        when(userService.findById(7)).thenReturn(Optional.of(user));
        ChangePasswordDTO command = new ChangePasswordDTO();
        command.setCurrentPassword("wrong-current-pass");
        command.setNewPassword("new-pass");

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> new LoginService(userService, passwordSecurityService, securityRateLimitService)
                    .changePassword(7, command))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(106);

            assertThat(user.getPassword()).isEqualTo(storedHash);
            verify(userService, never()).updateById(any(UserPO.class));
            stp.verifyNoInteractions();
        }
    }

    @Test
    void requestWithOnlyNewPasswordIsRejectedWithoutSideEffects() {
        String storedHash = passwordSecurityService.encode("right-current-pass");
        UserPO user = UserPO.builder().id(7).password(storedHash).build();
        when(userService.findById(7)).thenReturn(Optional.of(user));
        ChangePasswordDTO command = new ChangePasswordDTO();
        command.setNewPassword("new-pass");

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> new LoginService(userService, passwordSecurityService, securityRateLimitService)
                    .changePassword(7, command))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(106);

            assertThat(user.getPassword()).isEqualTo(storedHash);
            verify(userService, never()).updateById(any(UserPO.class));
            stp.verifyNoInteractions();
        }
    }

    @Test
    void legacyOldPasswordJsonFieldMapsOnlyToCurrentPassword() throws Exception {
        ChangePasswordDTO command = new ObjectMapper().readValue(
                "{\"oldPassword\":\"old-pass\",\"newPassword\":\"new-pass\"}",
                ChangePasswordDTO.class);

        assertThat(command.getCurrentPassword()).isEqualTo("old-pass");
        assertThat(command.getNewPassword()).isEqualTo("new-pass");
    }
}
