package system.assessment.defense.interfaces.rest.websocket;

import cn.hutool.json.JSONUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import system.assessment.defense.application.dto.WebSocketResponse;
import system.assessment.defense.application.dto.WebsocketMessageDTO;
import system.assessment.defense.application.manage.WebsocketManager;
import system.assessment.defense.application.service.InterviewSessionStore;
import system.assessment.defense.application.service.StudentAssessmentService;
import system.assessment.defense.infrastructure.config.WebSocketSessionAuthentication;
import system.assessment.defense.infrastructure.emuns.WebsocketActionEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.io.IOException;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/**
 * @USER taoHouChao
 * @DATE 19:37 2025/8/14
 */
@AllArgsConstructor
@Slf4j
public class DefenseAssessmentHandler extends AbstractWebSocketHandler {

    private final StudentAssessmentService studentAssessmentService;
    private final InterviewSessionStore sessionStore;

    private static final Cache<String, Boolean> DEBOUNCE_CACHE = CacheBuilder.newBuilder()
            .expireAfterWrite(3, TimeUnit.SECONDS)
            .maximumSize(10000)
            .build();

    private static final int MAX_ASSESSMENT_ID_LENGTH = 10;
    private static final Pattern AUDIO_TURN_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String studentIdStr = session.getAttributes().get("studentId").toString();
        Integer studentId = Integer.parseInt(studentIdStr);
        WebsocketManager.addTextSession(studentId, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("控制连接关闭, sessionId:{}, code:{}, reason:{}",
                session.getId(), status.getCode(), status.getReason());
        String studentIdStr = session.getAttributes().get("studentId").toString();
        Integer studentId = Integer.parseInt(studentIdStr);
        WebsocketManager.removeTextSession(studentId, session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, @NotNull Throwable exception) {
        log.error("websocket连接出错, sessionId:{}, errorType:{}",
                session.getId(), exception.getClass().getSimpleName());
        String userId = session.getAttributes().get("studentId").toString();
        Integer studentId = Integer.parseInt(userId);
        WebsocketManager.removeTextSession(studentId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!WebSocketSessionAuthentication.isAuthenticatedStudent(session)) {
            rejectExpiredSession(session);
            return;
        }
        WebsocketMessageDTO messageDTO;
        try {
            messageDTO = JSONUtil.toBean(message.getPayload(), WebsocketMessageDTO.class);
        } catch (RuntimeException exception) {
            sendMessage(session, WebSocketResponse.ofError("消息格式无效，请刷新页面后重试"));
            return;
        }
        if (messageDTO == null) {
            sendMessage(session, WebSocketResponse.ofError("消息格式无效，请刷新页面后重试"));
            return;
        }
        if (WebsocketActionEnums.isPing(messageDTO.getAction())) {
            log.debug("收到 ping 消息");
            return;
        }
        if (!isValidAssessmentMessage(messageDTO)) {
            sendMessage(session, WebSocketResponse.ofError("消息格式无效，请刷新页面后重试"));
            return;
        }
        String userId = session.getAttributes().get("studentId").toString();
        Integer studentId = Integer.parseInt(userId);
        log.info("收到 websocket 消息, action:{}, payloadLength:{}, time:{}",
                messageDTO.getAction(), message.getPayloadLength(), System.currentTimeMillis());
        if (requiresAudioTurn(messageDTO) && isBlank(messageDTO.getTurnId())) {
            sendMessage(session, WebSocketResponse.ofError("缺少音频轮次标识，请重新开始本轮录音"));
            return;
        }
        // 防抖防刷
        String key = String.format("%d:%s:%s:%s", studentId, messageDTO.getAction(),
                messageDTO.getMessage(), messageDTO.getTurnId());
        if (DEBOUNCE_CACHE.getIfPresent(key) != null) {
            log.info("消息去抖处理, studentId:{}, action:{}", studentId, messageDTO.getAction());
            return;
        }
        if ((messageDTO.endAnswer() || messageDTO.endDefense())
                && !sessionStore.isAudioFlushed(studentId, messageDTO.getTurnId())) {
            sendMessage(session, WebSocketResponse.ofError("音频尚未确认，请等待录音上传完成后重试"));
            return;
        }
        Boolean exist = DEBOUNCE_CACHE.asMap().putIfAbsent(key, true);
        if (exist != null) {
            log.info("消息去抖处理, studentId:{}, action:{}", studentId, messageDTO.getAction());
            return;
        }
        try {
            studentAssessmentService.handleWebsocketMessage(studentId, messageDTO, content -> sendMessage(session, content));
        } catch (BusinessException exception) {
            DEBOUNCE_CACHE.invalidate(key);
            releaseFailedAudioTurn(studentId, messageDTO);
            log.warn("WebSocket业务请求被拒绝, studentId:{}, action:{}, code:{}",
                    studentId, messageDTO.getAction(), exception.getCode());
            sendMessage(session, WebSocketResponse.ofError(exception.getMessage()));
        } catch (Exception e) {
            DEBOUNCE_CACHE.invalidate(key);
            releaseFailedAudioTurn(studentId, messageDTO);
            log.error("处理WebSocket消息异常, studentId:{}, action:{}", studentId, messageDTO.getAction(), e);
            sendMessage(session, WebSocketResponse.ofError(retryableFailureMessage(messageDTO)));
        }
    }

    private void releaseFailedAudioTurn(Integer studentId, WebsocketMessageDTO message) {
        if (requiresAudioTurn(message) && !isBlank(message.getTurnId())) {
            sessionStore.clearAudio(studentId, message.getTurnId());
        }
    }

    private String retryableFailureMessage(WebsocketMessageDTO message) {
        if (message.startAnswer() || message.endAnswer()) {
            return "答题处理失败，请重新回答";
        }
        if (message.startDefense() || message.endDefense()) {
            return "答辩处理失败，请重新答辩";
        }
        return "处理消息失败，请稍后重试";
    }

    private void sendMessage(WebSocketSession session, WebSocketResponse message) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(JSONUtil.toJsonStr(message)));
            }
        } catch (IOException | IllegalStateException e) {
            // Async answer/defense processing may finish after the student has
            // reloaded or left the page. Delivery failure must never turn a
            // successfully persisted answer into a business failure/rollback.
            log.warn("发送消息失败，连接可能已关闭, sessionId:{}, errorType:{}",
                    session.getId(), e.getClass().getSimpleName());
        }
    }

    private void rejectExpiredSession(WebSocketSession session) throws IOException {
        sendMessage(session, WebSocketResponse.ofError("登录已过期，请重新登录"));
        session.close(CloseStatus.POLICY_VIOLATION);
    }

    private boolean requiresAudioTurn(WebsocketMessageDTO message) {
        return message.startAnswer() || message.endAnswer()
                || message.startDefense() || message.endDefense();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isValidAssessmentMessage(WebsocketMessageDTO message) {
        if (!WebsocketActionEnums.isAssessmentAction(message.getAction())
                || !isPositiveAssessmentId(message.getMessage())) {
            return false;
        }
        String turnId = message.getTurnId();
        if (turnId != null && !AUDIO_TURN_ID.matcher(turnId).matches()) {
            return false;
        }
        return !requiresAudioTurn(message) || turnId != null;
    }

    private boolean isPositiveAssessmentId(String assessmentId) {
        if (assessmentId == null || assessmentId.isBlank()
                || assessmentId.length() > MAX_ASSESSMENT_ID_LENGTH) {
            return false;
        }
        try {
            return Integer.parseInt(assessmentId) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
