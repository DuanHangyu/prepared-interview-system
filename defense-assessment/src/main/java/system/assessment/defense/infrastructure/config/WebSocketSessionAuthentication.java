package system.assessment.defense.infrastructure.config;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Revalidates the token behind an established WebSocket connection.
 *
 * <p>Short-lived per-session caching keeps audio-frame processing cheap while
 * ensuring password changes, logouts, account deletion, and token expiry take
 * effect for existing connections within a bounded interval.</p>
 */
public final class WebSocketSessionAuthentication {

    public static final String STUDENT_ID_ATTRIBUTE = "studentId";
    public static final String TOKEN_ATTRIBUTE = "authenticationToken";
    public static final String VALID_UNTIL_ATTRIBUTE = "authenticationValidatedUntil";

    private static final long REVALIDATION_MILLIS = Duration.ofSeconds(5).toMillis();

    private WebSocketSessionAuthentication() {
    }

    public static Optional<String> authenticateStudentToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId == null || !StpUtil.getRoleList(loginId).contains("STUDENT")) {
                return Optional.empty();
            }
            return Optional.of(loginId.toString());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public static void bindAuthenticatedSession(Map<String, Object> attributes,
                                                String studentId,
                                                String token) {
        attributes.put(STUDENT_ID_ATTRIBUTE, studentId);
        attributes.put(TOKEN_ATTRIBUTE, token);
        attributes.put(VALID_UNTIL_ATTRIBUTE, System.currentTimeMillis() + REVALIDATION_MILLIS);
    }

    public static boolean isAuthenticatedStudent(WebSocketSession session) {
        if (session == null) {
            return false;
        }
        Map<String, Object> attributes = session.getAttributes();
        String studentId = attributeString(attributes, STUDENT_ID_ATTRIBUTE);
        String token = attributeString(attributes, TOKEN_ATTRIBUTE);
        if (studentId == null || token == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (isCached(attributes, now)) {
            return true;
        }
        synchronized (session) {
            now = System.currentTimeMillis();
            if (isCached(attributes, now)) {
                return true;
            }
            boolean authenticated = authenticateStudentToken(token)
                    .filter(studentId::equals)
                    .isPresent();
            if (authenticated) {
                attributes.put(VALID_UNTIL_ATTRIBUTE, now + REVALIDATION_MILLIS);
            } else {
                attributes.remove(VALID_UNTIL_ATTRIBUTE);
            }
            return authenticated;
        }
    }

    private static boolean isCached(Map<String, Object> attributes, long now) {
        Object validUntil = attributes.get(VALID_UNTIL_ATTRIBUTE);
        return validUntil instanceof Number number && now < number.longValue();
    }

    private static String attributeString(Map<String, Object> attributes, String name) {
        Object value = attributes == null ? null : attributes.get(name);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
    }
}
