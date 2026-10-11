package system.assessment.defense.interfaces.rest.websocket;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import system.assessment.defense.application.manage.RealtimeSpeechManager;
import system.assessment.defense.application.manage.WebsocketManager;
import system.assessment.defense.application.service.InterviewSessionStore;
import system.assessment.defense.infrastructure.config.WebSocketSessionAuthentication;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class DefenseAssessmentVoiceHandlerTurnProtocolTest {

    @Test
    void delegatesProtocolFragmentReassemblyToTheWebSocketContainer() {
        DefenseAssessmentVoiceHandler handler = new DefenseAssessmentVoiceHandler(
                store(), mock(RealtimeSpeechManager.class));

        assertThat(handler.supportsPartialMessages()).isFalse();
    }

    @Test
    void flushAckIsSentOnlyAfterAllEarlierFramesAreStoredAndLateFramesAreRejected() throws Exception {
        InterviewSessionStore store = store();
        RealtimeSpeechManager realtimeSpeechManager = mock(RealtimeSpeechManager.class);
        DefenseAssessmentVoiceHandler handler =
                new DefenseAssessmentVoiceHandler(store, realtimeSpeechManager);
        WebSocketSession session = session(7);
        store.startAudio(7, "turn-1", 8);

        handler.handleTextMessage(session,
                new TextMessage("{\"action\":\"audio-start\",\"turnId\":\"turn-1\"}"));
        handler.handleBinaryMessage(session, new BinaryMessage(new byte[]{1, 2}));
        handler.handleBinaryMessage(session, new BinaryMessage(new byte[]{3, 4}));
        handler.handleTextMessage(session,
                new TextMessage("{\"action\":\"audio-flush\",\"turnId\":\"turn-1\"}"));

        assertThat(store.isAudioFlushed(7, "turn-1")).isTrue();
        assertThat(store.audioBytes(7, "turn-1")).containsExactly(1, 2, 3, 4);
        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage<?>> acknowledgements =
                ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session, times(2)).sendMessage(acknowledgements.capture());
        assertThat(acknowledgements.getAllValues().getLast().getPayload().toString())
                .contains("audio-flush-ack")
                .contains("turn-1")
                .contains("true");

        handler.handleBinaryMessage(session, new BinaryMessage(new byte[]{9}));

        assertThat(store.audioBytes(7, "turn-1")).containsExactly(1, 2, 3, 4);
    }

    @Test
    void binaryFrameBeforeAudioStartIsIgnoredAndDoesNotCreateABuffer() throws Exception {
        InterviewSessionStore store = store();
        RealtimeSpeechManager realtimeSpeechManager = mock(RealtimeSpeechManager.class);
        DefenseAssessmentVoiceHandler handler =
                new DefenseAssessmentVoiceHandler(store, realtimeSpeechManager);
        WebSocketSession session = session(7);

        handler.handleBinaryMessage(session, new BinaryMessage(new byte[]{1, 2, 3}));

        assertThat(store.hasAudio(7)).isFalse();
        verify(realtimeSpeechManager, never()).appendAudio(eq(7), any());
    }

    @Test
    void oversizedAudioClosesWithMessageTooBigInsteadOfAnInternalError() throws Exception {
        InterviewSessionStore store = store();
        RealtimeSpeechManager realtimeSpeechManager = mock(RealtimeSpeechManager.class);
        DefenseAssessmentVoiceHandler handler =
                new DefenseAssessmentVoiceHandler(store, realtimeSpeechManager);
        WebSocketSession session = session(7);
        store.startAudio(7, "turn-1", 8);
        handler.handleTextMessage(session,
                new TextMessage("{\"action\":\"audio-start\",\"turnId\":\"turn-1\"}"));

        handler.handleBinaryMessage(session, new BinaryMessage(new byte[33]));

        assertThat(store.hasAudio(7)).isFalse();
        assertThat(store.totalAudioBytes()).isZero();
        verify(session).close(CloseStatus.TOO_BIG_TO_PROCESS);
        verify(realtimeSpeechManager, never()).appendAudio(eq(7), any());
        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage<?>> messages =
                ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session, times(2)).sendMessage(messages.capture());
        assertThat(messages.getAllValues().getLast().getPayload().toString())
                .contains("语音数据超过允许限制")
                .contains("false");
    }

    @Test
    void connectionClosePreservesAFlushedTurnUntilTheControlCommandConsumesIt() throws Exception {
        InterviewSessionStore store = store();
        RealtimeSpeechManager realtimeSpeechManager = mock(RealtimeSpeechManager.class);
        DefenseAssessmentVoiceHandler handler =
                new DefenseAssessmentVoiceHandler(store, realtimeSpeechManager);
        WebSocketSession session = session(7);
        store.startAudio(7, "turn-1", 8);

        handler.handleTextMessage(session,
                new TextMessage("{\"action\":\"audio-start\",\"turnId\":\"turn-1\"}"));
        handler.handleBinaryMessage(session, new BinaryMessage(new byte[]{1, 2, 3, 4}));
        handler.handleTextMessage(session,
                new TextMessage("{\"action\":\"audio-flush\",\"turnId\":\"turn-1\"}"));
        assertThat(store.totalAudioBytes()).isEqualTo(4);
        WebsocketManager.addVoiceSession(7, session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(store.hasAudio(7)).isTrue();
        assertThat(store.totalAudioBytes()).isEqualTo(4);
        verify(realtimeSpeechManager).close(7, session);
        store.clearAudio(7);
    }

    @Test
    void transientConnectionClosePreservesARecordingTurnForReconnect() throws Exception {
        InterviewSessionStore store = store();
        RealtimeSpeechManager realtimeSpeechManager = mock(RealtimeSpeechManager.class);
        DefenseAssessmentVoiceHandler handler =
                new DefenseAssessmentVoiceHandler(store, realtimeSpeechManager);
        WebSocketSession session = session(7);
        store.startAudio(7, "turn-1", 8);

        handler.handleTextMessage(session,
                new TextMessage("{\"action\":\"audio-start\",\"turnId\":\"turn-1\"}"));
        handler.handleBinaryMessage(session, new BinaryMessage(new byte[]{1, 2, 3, 4}));
        WebsocketManager.addVoiceSession(7, session);

        handler.afterConnectionClosed(session, CloseStatus.SESSION_NOT_RELIABLE);

        assertThat(store.isAudioRecording(7, "turn-1")).isTrue();
        assertThat(store.totalAudioBytes()).isEqualTo(4);
        verify(realtimeSpeechManager).close(7, session);
    }

    @Test
    void staleConnectionCloseCannotReleaseTheReplacementConnectionsTurn() throws Exception {
        InterviewSessionStore store = store();
        RealtimeSpeechManager realtimeSpeechManager = mock(RealtimeSpeechManager.class);
        DefenseAssessmentVoiceHandler handler =
                new DefenseAssessmentVoiceHandler(store, realtimeSpeechManager);
        WebSocketSession staleSession = session(7);
        WebSocketSession replacementSession = session(7);
        store.startAudio(7, "turn-1", 8);
        handler.handleTextMessage(staleSession,
                new TextMessage("{\"action\":\"audio-start\",\"turnId\":\"turn-1\"}"));
        store.appendAudio(7, "turn-1", java.nio.ByteBuffer.wrap(new byte[]{1, 2}));
        WebsocketManager.addVoiceSession(7, staleSession);
        WebsocketManager.addVoiceSession(7, replacementSession);

        handler.afterConnectionClosed(staleSession, CloseStatus.NORMAL);

        assertThat(store.hasAudio(7)).isTrue();
        assertThat(store.totalAudioBytes()).isEqualTo(2);
        WebsocketManager.removeVoiceSession(7, replacementSession);
        store.clearAudio(7);
    }

    @Test
    void malformedVoiceControlJsonIsRejectedWithoutEscapingTheHandler() throws Exception {
        InterviewSessionStore store = store();
        RealtimeSpeechManager realtimeSpeechManager = mock(RealtimeSpeechManager.class);
        DefenseAssessmentVoiceHandler handler =
                new DefenseAssessmentVoiceHandler(store, realtimeSpeechManager);
        WebSocketSession session = session(7);

        handler.handleTextMessage(session, new TextMessage("{not-json"));

        assertThat(store.hasAudio(7)).isFalse();
        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage<?>> response =
                ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session).sendMessage(response.capture());
        assertThat(response.getValue().getPayload().toString())
                .contains("消息格式无效")
                .contains("false");
    }

    @Test
    void unknownActionsAndOversizedTurnIdsAreRejected() throws Exception {
        InterviewSessionStore store = store();
        RealtimeSpeechManager realtimeSpeechManager = mock(RealtimeSpeechManager.class);
        DefenseAssessmentVoiceHandler handler =
                new DefenseAssessmentVoiceHandler(store, realtimeSpeechManager);
        WebSocketSession session = session(7);

        handler.handleTextMessage(session,
                new TextMessage("{\"action\":\"audio-delete\",\"turnId\":\"turn-1\"}"));
        handler.handleTextMessage(session,
                new TextMessage("{\"action\":\"audio-start\",\"turnId\":\"%s\"}"
                        .formatted("x".repeat(129))));

        assertThat(store.hasAudio(7)).isFalse();
        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage<?>> responses =
                ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session, times(2)).sendMessage(responses.capture());
        assertThat(responses.getAllValues())
                .allSatisfy(response -> assertThat(response.getPayload().toString())
                        .contains("消息格式无效")
                        .contains("false"));
    }

    private InterviewSessionStore store() {
        return new InterviewSessionStore(32, 64, 16, 1_024, Duration.ofMinutes(5));
    }

    private WebSocketSession session(int studentId) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketSessionAuthentication.STUDENT_ID_ATTRIBUTE, String.valueOf(studentId));
        attributes.put(WebSocketSessionAuthentication.TOKEN_ATTRIBUTE, "test-token");
        attributes.put(WebSocketSessionAuthentication.VALID_UNTIL_ATTRIBUTE, Long.MAX_VALUE);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
