package system.assessment.defense.infrastructure.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.util.Set;

/**
 * Rejects cross-origin browser writes now that authentication is cookie based.
 */
@Component
@RequiredArgsConstructor
public class CsrfOriginInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final WebSocketOriginPolicy originPolicy;

    @Value("${sa-token.token-name:Authorization}")
    private String sessionCookieName = "Authorization";

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return true;
        }

        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null) {
            if (!originPolicy.isAllowed(origin)) {
                reject();
            }
            return true;
        }

        if (hasSessionCookie(request)) {
            reject();
        }
        return true;
    }

    private boolean hasSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (sessionCookieName.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }

    private void reject() {
        throw new BusinessException(403, "请求来源不受信任");
    }
}
