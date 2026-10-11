package system.assessment.defense.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import system.assessment.defense.infrastructure.config.CsrfOriginInterceptor;
import system.assessment.defense.infrastructure.config.WebSocketOriginPolicy;
import system.assessment.defense.infrastructure.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsrfOriginInterceptorTest {

    private final CsrfOriginInterceptor interceptor = new CsrfOriginInterceptor(
            new WebSocketOriginPolicy("https://interview.example.com,http://localhost:3002"));

    @Test
    void authenticatedBrowserWritesRequireAnAllowedOrigin() {
        MockHttpServletRequest allowed = postRequest();
        allowed.setCookies(new Cookie("Authorization", "session-token"));
        allowed.addHeader("Origin", "https://interview.example.com");

        assertThat(interceptor.preHandle(
                allowed, new MockHttpServletResponse(), new Object())).isTrue();

        MockHttpServletRequest crossSite = postRequest();
        crossSite.setCookies(new Cookie("Authorization", "session-token"));
        crossSite.addHeader("Origin", "https://evil.example");
        assertForbidden(crossSite);

        MockHttpServletRequest missingOrigin = postRequest();
        missingOrigin.setCookies(new Cookie("Authorization", "session-token"));
        assertForbidden(missingOrigin);
    }

    @Test
    void safeReadsAndCredentiallessCommandLineLoginRemainAvailable() {
        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/user-detail");
        read.setCookies(new Cookie("Authorization", "session-token"));
        assertThat(interceptor.preHandle(read, new MockHttpServletResponse(), new Object())).isTrue();

        assertThat(interceptor.preHandle(
                postRequest(), new MockHttpServletResponse(), new Object())).isTrue();
    }

    private MockHttpServletRequest postRequest() {
        return new MockHttpServletRequest("POST", "/assessment-settings/create");
    }

    private void assertForbidden(MockHttpServletRequest request) {
        assertThatThrownBy(() -> interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(403);
    }
}
