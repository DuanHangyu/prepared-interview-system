package system.assessment.defense.security;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import system.assessment.defense.infrastructure.config.DefenseAssessmentHandshakeInterceptor;
import system.assessment.defense.infrastructure.config.WebSocketSessionAuthentication;
import system.assessment.defense.infrastructure.config.WebSocketOriginPolicy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WebSocketSecurityTest {

    @Test
    void allowedOriginsMustBeExplicitAndNeverWildcard() {
        WebSocketOriginPolicy policy = new WebSocketOriginPolicy(
                "https://defense.example.com, http://localhost:8080");

        assertThat(policy.allowedOrigins())
                .containsExactly("https://defense.example.com", "http://localhost:8080");
        assertThatThrownBy(() -> new WebSocketOriginPolicy("*"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateConfiguredOriginsAreNormalizedAndDeduplicated() {
        WebSocketOriginPolicy policy = new WebSocketOriginPolicy(
                "https://defense.example.com/, https://defense.example.com");

        assertThat(policy.allowedOrigins()).containsExactly("https://defense.example.com");
        assertThat(policy.isAllowed("https://defense.example.com/")).isTrue();
    }

    @Test
    void handshakeAllowsOnlyAuthenticatedStudents() throws Exception {
        DefenseAssessmentHandshakeInterceptor interceptor = new DefenseAssessmentHandshakeInterceptor();
        WebSocketHandler handler = mock(WebSocketHandler.class);

        try (MockedStatic<StpUtil> stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.getLoginIdByToken("student-token")).thenReturn(12);
            stp.when(() -> StpUtil.getRoleList(12)).thenReturn(List.of("STUDENT"));
            ServerHttpResponse studentResponse = response();
            Map<String, Object> studentAttributes = new HashMap<>();
            assertThat(interceptor.beforeHandshake(
                    request("student-token"), studentResponse, handler, studentAttributes))
                    .isTrue();
            assertThat(studentAttributes)
                    .containsEntry("studentId", "12")
                    .containsEntry("authenticationToken", "student-token");

            stp.when(() -> StpUtil.getLoginIdByToken("teacher-token")).thenReturn(7);
            stp.when(() -> StpUtil.getRoleList(7)).thenReturn(List.of("TEACHER"));
            MockHttpServletResponse rawTeacherResponse = new MockHttpServletResponse();
            ServerHttpResponse teacherResponse = new ServletServerHttpResponse(rawTeacherResponse);
            assertThat(interceptor.beforeHandshake(
                    request("teacher-token"), teacherResponse, handler, new java.util.HashMap<>()))
                    .isFalse();
            assertThat(rawTeacherResponse.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        }
    }

    @Test
    void establishedSessionsAreRevalidatedAfterTheirShortCacheExpires() {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketSessionAuthentication.STUDENT_ID_ATTRIBUTE, "12");
        attributes.put(WebSocketSessionAuthentication.TOKEN_ATTRIBUTE, "student-token");
        attributes.put(WebSocketSessionAuthentication.VALID_UNTIL_ATTRIBUTE, 0L);
        org.mockito.Mockito.when(session.getAttributes()).thenReturn(attributes);

        try (MockedStatic<StpUtil> stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.getLoginIdByToken("student-token")).thenReturn(null);
            assertThat(WebSocketSessionAuthentication.isAuthenticatedStudent(session)).isFalse();

            stp.when(() -> StpUtil.getLoginIdByToken("student-token")).thenReturn(12);
            stp.when(() -> StpUtil.getRoleList(12)).thenReturn(List.of("STUDENT"));
            assertThat(WebSocketSessionAuthentication.isAuthenticatedStudent(session)).isTrue();
        }

        assertThat(attributes.get(WebSocketSessionAuthentication.VALID_UNTIL_ATTRIBUTE))
                .isInstanceOf(Long.class);
    }

    private ServerHttpRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/defense-assessment");
        request.setCookies(new jakarta.servlet.http.Cookie("Authorization", token));
        return new ServletServerHttpRequest(request);
    }

    private ServerHttpResponse response() {
        return new ServletServerHttpResponse(new MockHttpServletResponse());
    }
}
