package system.assessment.defense.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import system.assessment.defense.application.dto.LoginDTO;
import system.assessment.defense.application.service.LoginService;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.application.service.SecurityRateLimitService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginFailureContractTest {

    private UserService userService;
    private PasswordSecurityService passwordSecurityService;
    private LoginService loginService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        passwordSecurityService = spy(new PasswordSecurityService());
        loginService = new LoginService(userService, passwordSecurityService, mock(SecurityRateLimitService.class));
    }

    @Test
    void unknownAccountAndWrongPasswordHaveTheSamePublicFailure() {
        LoginDTO unknown = login("missing", "any-password");
        when(userService.findByAccount("missing")).thenReturn(Optional.empty());
        Throwable unknownFailure = catchThrowable(() -> loginService.login(unknown));

        LoginDTO wrong = login("known", "wrong-password");
        UserPO known = UserPO.builder()
                .id(7)
                .account("known")
                .password(passwordSecurityService.encode("right-password"))
                .build();
        when(userService.findByAccount("known")).thenReturn(Optional.of(known));
        Throwable wrongFailure = catchThrowable(() -> loginService.login(wrong));

        assertThat(unknownFailure).isInstanceOf(BusinessException.class);
        assertThat(wrongFailure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) unknownFailure).getCode()).isEqualTo(401);
        assertThat(((BusinessException) wrongFailure).getCode()).isEqualTo(401);
        assertThat(unknownFailure.getMessage()).isEqualTo("账号或密码错误");
        assertThat(wrongFailure.getMessage()).isEqualTo("账号或密码错误");
        verify(passwordSecurityService).verifyAgainstDummyHash("any-password");
    }

    private LoginDTO login(String account, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setAccount(account);
        dto.setPassword(password);
        return dto;
    }
}
