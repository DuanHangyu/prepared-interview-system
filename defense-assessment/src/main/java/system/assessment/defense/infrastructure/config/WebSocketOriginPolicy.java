package system.assessment.defense.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;

/**
 * WebSocket 跨站握手白名单。只接受明确的 HTTP(S) Origin，禁止通配符。
 */
@Component
public class WebSocketOriginPolicy {

    private final String[] allowedOrigins;
    private final Set<String> allowedOriginSet;

    public WebSocketOriginPolicy(@Value("${security.websocket.allowed-origins}") String configuredOrigins) {
        this.allowedOrigins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .map(this::validateOrigin)
                .distinct()
                .toArray(String[]::new);
        if (allowedOrigins.length == 0) {
            throw new IllegalArgumentException("At least one WebSocket origin must be configured");
        }
        this.allowedOriginSet = Set.copyOf(Arrays.asList(allowedOrigins));
    }

    public String[] allowedOrigins() {
        return allowedOrigins.clone();
    }

    public boolean isAllowed(String origin) {
        if (origin == null) {
            return false;
        }
        String normalized = origin.endsWith("/")
                ? origin.substring(0, origin.length() - 1)
                : origin;
        return allowedOriginSet.contains(normalized);
    }

    private String validateOrigin(String origin) {
        if (origin.contains("*")) {
            throw new IllegalArgumentException("Wildcard WebSocket origins are forbidden");
        }
        URI uri = URI.create(origin);
        boolean validScheme = "http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme());
        boolean originOnly = (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                && uri.getQuery() == null
                && uri.getFragment() == null
                && uri.getUserInfo() == null;
        if (!validScheme || uri.getHost() == null || !originOnly) {
            throw new IllegalArgumentException("Invalid WebSocket origin: " + origin);
        }
        return origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }
}
