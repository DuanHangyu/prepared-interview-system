package system.assessment.defense.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Prevent authenticated API data and error payloads from being persisted by
 * browsers or intermediary caches.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NoStoreResponseFilter extends OncePerRequestFilter {

    private static final String CACHE_CONTROL =
            "no-store, no-cache, must-revalidate, max-age=0";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", CACHE_CONTROL);
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        filterChain.doFilter(request, response);
    }
}
