package system.assessment.defense.prepared;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.infrastructure.config.CsrfOriginInterceptor;
import system.assessment.defense.infrastructure.config.WebSocketOriginPolicy;
import system.assessment.defense.infrastructure.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.*;

class PreparedCookieCsrfTest {
    @Test void configuredPreparedCookieCannotBypassOriginValidation() {
        var interceptor = new CsrfOriginInterceptor(new WebSocketOriginPolicy("http://127.0.0.1:13015"));
        ReflectionTestUtils.setField(interceptor, "sessionCookieName", "prepared-interview-session");
        var request = new MockHttpServletRequest("PUT", "/backend/prepared-papers/1");
        request.setCookies(new Cookie("prepared-interview-session", "non-secret-unit-test"));
        assertThrows(BusinessException.class, () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        request.addHeader("Origin", "http://127.0.0.1:13015");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }
}
