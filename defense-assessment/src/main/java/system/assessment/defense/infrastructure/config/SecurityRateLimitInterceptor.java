package system.assessment.defense.infrastructure.config;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;
import system.assessment.defense.application.service.SecurityRateLimitService;

/**
 * 在进入控制器、读取大文件或调用模型之前拒绝超额 HTTP 请求。
 */
@Component
@RequiredArgsConstructor
public class SecurityRateLimitInterceptor implements HandlerInterceptor {

    private final SecurityRateLimitService rateLimitService;
    private static final String LOGIN_RESERVATION = SecurityRateLimitInterceptor.class.getName() + ".loginReservation";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if(path.startsWith("/backend/prepared-results/")) {
            rateLimitService.checkPreparedInterview(StpUtil.getLoginIdAsInt(),false);
            return true;
        }
        if(path.startsWith("/backend/prepared-reviews/")) {
            rateLimitService.checkPreparedReview(StpUtil.getLoginIdAsInt(),!"GET".equalsIgnoreCase(request.getMethod())&&!"HEAD".equalsIgnoreCase(request.getMethod()));
            return true;
        }
        if (path.startsWith("/backend/prepared-interviews/")) {
            boolean writing=!"GET".equalsIgnoreCase(request.getMethod())&&!"HEAD".equalsIgnoreCase(request.getMethod());
            if(system.assessment.defense.prepared.attempt.PreparedStudentBodyLimitFilter.isChunkRoute(path))
                rateLimitService.checkPreparedUpload(StpUtil.getLoginIdAsInt(),writing);
            else rateLimitService.checkPreparedInterview(StpUtil.getLoginIdAsInt(),writing);
            return true;
        }
        if ("/login".equals(path) && "POST".equalsIgnoreCase(request.getMethod())) {
            request.setAttribute(LOGIN_RESERVATION, rateLimitService.checkLoginAttempt(request.getRemoteAddr()));
            return true;
        }
        if ("/file/upload".equals(path) && "POST".equalsIgnoreCase(request.getMethod())) {
            Integer userId = StpUtil.isLogin() ? StpUtil.getLoginIdAsInt() : null;
            rateLimitService.checkFileUpload(request.getRemoteAddr(), userId);
            return true;
        }
        if (path.startsWith("/gemini/")) {
            Integer userId = StpUtil.isLogin() ? StpUtil.getLoginIdAsInt() : null;
            rateLimitService.checkAiHttp(request.getRemoteAddr(), userId, estimateAiCost(path));
        }
        return true;
    }

    /** Only the login controller calls this, after LoginService has established the validated session. */
    public static void authenticationSucceeded(HttpServletRequest request) {
        if (request.getAttribute(LOGIN_RESERVATION) instanceof SecurityRateLimitService.LoginAttempt ticket)
            ticket.authenticated();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception error) {
        if (request.getAttribute(LOGIN_RESERVATION) instanceof SecurityRateLimitService.LoginAttempt ticket) {
            ticket.close();
            request.removeAttribute(LOGIN_RESERVATION);
        }
    }

    private int estimateAiCost(String path) {
        if (path.endsWith("/pickUpFileContent")) {
            return 10;
        }
        if (path.endsWith("/pickUpVoice")) {
            return 5;
        }
        if (path.endsWith("/generate-voice")) {
            return 3;
        }
        return 5;
    }
}
