package system.assessment.defense.infrastructure.config;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.Cookie;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.util.WebUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;

/**
 * @USER taoHouChao
 * @DATE 19:40 2025/8/14
 */
@Slf4j
public class DefenseAssessmentHandshakeInterceptor implements HandshakeInterceptor {
    private static final String SESSION_COOKIE_NAME = "Authorization";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        log.info("beforeHandshake");
        String token = sessionToken(request);
        Object loginId = StrUtil.isBlank(token) ? null : StpUtil.getLoginIdByToken(token);
        String studentId = Optional.ofNullable(loginId).map(Object::toString).orElse("");
        if (StrUtil.isNotBlank(studentId) && StpUtil.getRoleList(loginId).contains("STUDENT")) {
            WebSocketSessionAuthentication.bindAuthenticatedSession(attributes, studentId, token);
            log.info("websocket handshake success, studentId:{}", studentId);
            return true;
        }
        HttpStatus status = loginId == null ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        log.warn("websocket handshake rejected, status:{}", status.value());
        response.setStatusCode(status);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
        log.info("afterHandshake");
    }

    private String sessionToken(ServerHttpRequest request) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return null;
        }
        Cookie cookie = WebUtils.getCookie(servletRequest.getServletRequest(), SESSION_COOKIE_NAME);
        return cookie == null ? null : cookie.getValue();
    }
}
