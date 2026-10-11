package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import system.assessment.defense.application.dto.LoginDTO;
import system.assessment.defense.application.service.LoginService;
import system.assessment.defense.application.service.SecurityRateLimitService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedLoginRateLimitTest {
    @Test void oneHundredDistinctStudentsCanShareACampusExitWhileNetworkBurstRemainsBounded() {
        var limiter = new SecurityRateLimitService();
        for (int index = 0; index < 100; index++) {
            limiter.checkLoginAttempt("shared-campus-exit");
            limiter.checkLoginAccount("student-" + index);
        }
        for (int index = 100; index < 300; index++) limiter.checkLoginAttempt("shared-campus-exit");
        assertThatThrownBy(() -> limiter.checkLoginAttempt("shared-campus-exit")).isInstanceOf(BusinessException.class);
    }

    @Test void accountProtectionIsCaseInsensitiveAndIndependentOfTheRemoteAddress() {
        var limiter = new SecurityRateLimitService();
        for (int index = 0; index < 10; index++) limiter.checkLoginAccount(index % 2 == 0 ? "Student001" : " student001 ");
        assertThatThrownBy(() -> limiter.checkLoginAccount("STUDENT001")).isInstanceOf(BusinessException.class);
        assertThatCode(() -> limiter.checkLoginAccount("student002")).doesNotThrowAnyException();
    }

    @Test void databaseEquivalentAccountAliasesShareTheRealUserLimitBeforePasswordVerification() {
        var users = mock(UserService.class);
        var passwords = mock(PasswordSecurityService.class);
        var limiter = new SecurityRateLimitService();
        var login = new LoginService(users, passwords, limiter);
        UserPO user = UserPO.builder().id(17).account("student001").password("unit-test-hash").type(0).build();
        when(users.findByAccount(anyString())).thenReturn(Optional.of(user));
        when(passwords.verify(anyString(), anyString())).thenReturn(new PasswordSecurityService.PasswordVerification(false, null));
        var input = new LoginDTO();
        input.setPassword("only-local-test-password");
        for (int index = 0; index < 10; index++) {
            input.setAccount(index % 2 == 0 ? "student001" : "studént001");
            assertThatThrownBy(() -> login.login(input)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(401);
        }
        input.setAccount("STUDENT001");
        assertThatThrownBy(() -> login.login(input)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(429);
        verify(passwords, times(10)).verify(anyString(), anyString());
    }
}
