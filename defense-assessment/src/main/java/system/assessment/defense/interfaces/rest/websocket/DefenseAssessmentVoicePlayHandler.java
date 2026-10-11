package system.assessment.defense.interfaces.rest.websocket;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import system.assessment.defense.application.dto.WebsocketMessageDTO;
import system.assessment.defense.application.dto.WebSocketResponse;
import system.assessment.defense.application.manage.WebsocketManager;
import system.assessment.defense.infrastructure.config.WebSocketSessionAuthentication;
import system.assessment.defense.infrastructure.emuns.WebsocketActionEnums;

import java.io.IOException;

/**
 * @USER taoHouChao
 * @DATE 22:38 2025/8/14
 */
@Slf4j
public class DefenseAssessmentVoicePlayHandler extends AbstractWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String studentIdStr = session.getAttributes().get("studentId").toString();
        Integer studentId = Integer.parseInt(studentIdStr);
        WebsocketManager.addVoicePlaySession(studentId, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("voice关闭连接");
        String studentIdStr = session.getAttributes().get("studentId").toString();
        Integer studentId = Integer.parseInt(studentIdStr);
        WebsocketManager.removeVoicePayload(studentId, session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, @NotNull Throwable exception) {
        log.error("websocket连接出错, sessionId:{}, errorType:{}",
                session.getId(), exception.getClass().getSimpleName());
        String userId = session.getAttributes().get("studentId").toString();
        Integer studentId = Integer.parseInt(userId);
        WebsocketManager.removeVoicePayload(studentId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
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
        if (messageDTO.stop()) {
            String userId = session.getAttributes().get("studentId").toString();
            Integer studentId = Integer.parseInt(userId);
            WebsocketManager.interruptVoiceSending(studentId);
            return;
        }
        sendMessage(session, WebSocketResponse.ofError("消息格式无效，请刷新页面后重试"));
    }

    private void rejectExpiredSession(WebSocketSession session) {
        sendMessage(session, WebSocketResponse.ofError("登录已过期，请重新登录"));
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException exception) {
            log.debug("关闭失效的语音播放连接失败, sessionId:{}", session.getId());
        }
    }

    private void sendMessage(WebSocketSession session, WebSocketResponse response) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(JSONUtil.toJsonStr(response)));
            }
        } catch (IOException exception) {
            log.debug("发送连接状态消息失败, sessionId:{}", session.getId());
        }
    }

}
