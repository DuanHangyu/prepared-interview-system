package system.assessment.defense.interfaces.rest.websocket;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import system.assessment.defense.infrastructure.config.WebSocketSessionAuthentication;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefenseAssessmentVoicePlayHandlerTest {

    @Test
    void delegatesProtocolFragmentReassemblyToTheWebSocketContainer() {
        assertThat(new DefenseAssessmentVoicePlayHandler().supportsPartialMessages()).isFalse();
    }

    @Test
    void unknownVoicePlaybackActionReturnsAProtocolError() throws Exception {
        DefenseAssessmentVoicePlayHandler handler = new DefenseAssessmentVoicePlayHandler();
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketSessionAuthentication.STUDENT_ID_ATTRIBUTE, "7");
        attributes.put(WebSocketSessionAuthentication.TOKEN_ATTRIBUTE, "test-token");
        attributes.put(WebSocketSessionAuthentication.VALID_UNTIL_ATTRIBUTE, Long.MAX_VALUE);
        when(session.getAttributes()).thenReturn(attributes);

        handler.handleTextMessage(session, new TextMessage("{\"action\":\"pause\"}"));

        ArgumentCaptor<WebSocketMessage<?>> response =
                ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session).sendMessage(response.capture());
        assertThat(response.getValue().getPayload().toString())
                .contains("消息格式无效")
                .contains("false");
    }
}
