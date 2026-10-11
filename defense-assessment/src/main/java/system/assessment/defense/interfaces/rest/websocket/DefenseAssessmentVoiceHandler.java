package system.assessment.defense.interfaces.rest.websocket;

import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import system.assessment.defense.application.dto.AudioTurnAckDTO;
import system.assessment.defense.application.dto.AudioTurnMessageDTO;
import system.assessment.defense.application.dto.WebSocketResponse;
import system.assessment.defense.application.manage.RealtimeSpeechManager;
import system.assessment.defense.application.manage.WebsocketManager;
import system.assessment.defense.application.service.InterviewSessionStore;
import system.assessment.defense.infrastructure.config.WebSocketSessionAuthentication;
import system.assessment.defense.infrastructure.emuns.WebsocketActionEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * @USER taoHouChao
 * @DATE 22:38 2025/8/14
 */
@AllArgsConstructor
@Slf4j
public class DefenseAssessmentVoiceHandler extends AbstractWebSocketHandler {

    private static final String AUDIO_TURN_ATTRIBUTE = "audioTurnId";
    private static final Pattern AUDIO_TURN_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");

    private final InterviewSessionStore sessionStore;
    private final RealtimeSpeechManager realtimeSpeechManager;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String studentIdStr = session.getAttributes().get("studentId").toString();
        Integer studentId = Integer.parseInt(studentIdStr);
        WebsocketManager.addVoiceSession(studentId, session);
        realtimeSpeechManager.open(studentId, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("语音连接关闭, sessionId:{}, code:{}, reason:{}",
                session.getId(), status.getCode(), status.getReason());
        String studentIdStr = session.getAttributes().get("studentId").toString();
        Integer studentId = Integer.parseInt(studentIdStr);
        WebsocketManager.removeVoiceSession(studentId, session);
        realtimeSpeechManager.close(studentId, session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, @NotNull Throwable exception) {
        log.error("websocket连接出错, sessionId:{}, errorType:{}",
                session.getId(), exception.getClass().getSimpleName());
        String userId = session.getAttributes().get("studentId").toString();
        Integer studentId = Integer.parseInt(userId);
        WebsocketManager.removeVoiceSession(studentId, session);
        realtimeSpeechManager.close(studentId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (!WebSocketSessionAuthentication.isAuthenticatedStudent(session)) {
            rejectExpiredSession(session);
            return;
        }
        AudioTurnMessageDTO messageDTO;
        try {
            messageDTO = JSONUtil.toBean(message.getPayload(), AudioTurnMessageDTO.class);
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
        if ((!messageDTO.start() && !messageDTO.flush())
                || messageDTO.getTurnId() == null
                || !AUDIO_TURN_ID.matcher(messageDTO.getTurnId()).matches()) {
            sendMessage(session, WebSocketResponse.ofError("消息格式无效，请刷新页面后重试"));
            return;
        }
        String studentIdStr = session.getAttributes().get("studentId").toString();
        Integer studentId = Integer.parseInt(studentIdStr);
        if (messageDTO.start()) {
            boolean accepted = sessionStore.isAudioRecording(studentId, messageDTO.getTurnId());
            if (accepted) {
                session.getAttributes().put(AUDIO_TURN_ATTRIBUTE, messageDTO.getTurnId());
            }
            sendMessage(session, AudioTurnAckDTO.start(
                    messageDTO.getTurnId(), accepted,
                    accepted ? "音频轮次已就绪" : "音频轮次不存在或已结束"));
            return;
        }
        if (messageDTO.flush()) {
            Object boundTurn = session.getAttributes().get(AUDIO_TURN_ATTRIBUTE);
            boolean accepted = messageDTO.getTurnId() != null
                    && messageDTO.getTurnId().equals(boundTurn)
                    && sessionStore.flushAudio(studentId, messageDTO.getTurnId());
            sendMessage(session, AudioTurnAckDTO.flush(
                    messageDTO.getTurnId(), accepted,
                    accepted ? "音频已确认" : "音频轮次不匹配或不存在"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        if (!WebSocketSessionAuthentication.isAuthenticatedStudent(session)) {
            rejectExpiredSession(session);
            return;
        }
        String studentIdStr = session.getAttributes().get("studentId").toString();
        Integer studentId = Integer.parseInt(studentIdStr);
        Object turnId = session.getAttributes().get(AUDIO_TURN_ATTRIBUTE);
        if (!(turnId instanceof String currentTurnId)) {
            log.debug("忽略未绑定或已结束轮次的音频帧, studentId:{}", studentId);
            return;
        }
        try {
            if (!sessionStore.appendAudio(studentId, currentTurnId,
                    message.getPayload().asReadOnlyBuffer())) {
                log.debug("忽略未绑定或已结束轮次的音频帧, studentId:{}", studentId);
                return;
            }
        } catch (BusinessException exception) {
            log.warn("拒绝超过限制的音频帧, studentId:{}, turnId:{}, payloadBytes:{}",
                    studentId, currentTurnId, message.getPayloadLength());
            sendMessage(session, WebSocketResponse.ofError(exception.getMessage()));
            session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        realtimeSpeechManager.appendAudio(studentId, message.getPayload().asReadOnlyBuffer());
    }

    private void sendMessage(WebSocketSession session, Object message) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(JSONUtil.toJsonStr(message)));
            }
        } catch (IOException e) {
            log.error("发送消息失败：{}", e.getMessage());
        }
    }

    private void rejectExpiredSession(WebSocketSession session) {
        sendMessage(session, WebSocketResponse.ofError("登录已过期，请重新登录"));
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException exception) {
            log.debug("关闭失效的语音连接失败, sessionId:{}", session.getId());
        }
    }

}
