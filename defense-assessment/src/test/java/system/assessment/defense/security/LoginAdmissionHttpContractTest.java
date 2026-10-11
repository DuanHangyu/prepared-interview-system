package system.assessment.defense.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.application.dto.LoginDTO;
import system.assessment.defense.application.service.LoginService;
import system.assessment.defense.application.service.SecurityRateLimitService;
import system.assessment.defense.infrastructure.config.SecurityRateLimitInterceptor;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.interfaces.rest.LoginController;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginAdmissionHttpContractTest {
    private final SecurityRateLimitService limiter = new SecurityRateLimitService();
    private final SecurityRateLimitInterceptor interceptor = new SecurityRateLimitInterceptor(limiter);
    private final LoginService login = mock(LoginService.class);
    private final LoginController controller = new LoginController();

    LoginAdmissionHttpContractTest() { ReflectionTestUtils.setField(controller, "loginService", login); }

    @Test void onlySuccessfulServerAuthenticationRefundsTheRequestReservation() throws Exception {
        for (int i = 0; i < 600; i++) send();
        verify(login, times(600)).login(any(LoginDTO.class));
    }

    @Test void failedCredentialsCannotRefundTheirReservationEvenWithForgedSuccessHeaders() {
        doThrow(new BusinessException(401, "账号或密码错误")).when(login).login(any(LoginDTO.class));
        for (int i = 0; i < 300; i++) assertThatThrownBy(this::send).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(401);
        assertThatThrownBy(this::send).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(429);
        verify(login, times(300)).login(any(LoginDTO.class));
    }

    @Test void validationFailureOrEarlyControllerFailureDoesNotRefundBasedOnHttpStatus() throws Exception {
        for (int i = 0; i < 300; i++) {
            var request = request(); var response = new MockHttpServletResponse();
            interceptor.preHandle(request, response, null);
            response.setStatus(200);
            interceptor.afterCompletion(request, response, null, new IllegalArgumentException("invalid input"));
        }
        assertThatThrownBy(this::send).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(429);
        verifyNoInteractions(login);
    }

    @Test void invalidRoleOrSessionCreationFailureRetainsTheReservation() {
        doThrow(new BusinessException(403, "无权限")).when(login).login(any(LoginDTO.class));
        for (int i = 0; i < 300; i++) assertThatThrownBy(this::send).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(403);
        assertThatThrownBy(this::send).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(429);
    }

    private void send() throws Exception {
        var request = request(); var response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, null);
        try { controller.login(new LoginDTO(), request); }
        finally { interceptor.afterCompletion(request, response, null, null); }
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr("campus");
        request.addHeader("X-Login-Success", "true");
        request.addHeader("X-Forwarded-For", "other-campus");
        return request;
    }
}
