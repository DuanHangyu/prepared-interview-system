package system.assessment.defense.infrastructure.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import system.assessment.defense.application.service.CredentialSessionGuard;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

/**
 * @Author taoHouChao
 * @Date 15:25 2025/6/9
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfig implements WebMvcConfigurer {

    private final SecurityRateLimitInterceptor securityRateLimitInterceptor;
    private final CsrfOriginInterceptor csrfOriginInterceptor;
    private final UserService userService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(csrfOriginInterceptor)
                .addPathPatterns("/**");
        registry.addInterceptor(new SaInterceptor(handle -> {
            StpUtil.checkLogin();
            CredentialSessionGuard.requireCurrentCredential(userService);
        }))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/login",
                        "/actuator/health/**",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/webjars/**"
                );
        registry.addInterceptor(securityRateLimitInterceptor)
                .addPathPatterns("/login", "/gemini/**", "/file/upload", "/backend/prepared-interviews/**", "/backend/prepared-reviews/**", "/backend/prepared-results/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 2. 优化资源映射（添加WebJAR路径）
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");

        registry.addResourceHandler("/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/springdoc/openapi-ui/");
    }
}
