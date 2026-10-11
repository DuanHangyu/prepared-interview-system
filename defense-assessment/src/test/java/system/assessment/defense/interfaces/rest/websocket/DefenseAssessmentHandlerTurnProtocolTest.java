package system.assessment.defense.interfaces.rest.websocket;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import system.assessment.defense.application.service.InterviewSessionStore;
import system.assessment.defense.application.service.StudentAssessmentService;
import system.assessment.defense.application.manage.WebsocketManager;
import system.assessment.defense.application.dto.WebSocketResponse;
import system.assessment.defense.infrastructure.config.WebSocketSessionAuthentication;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefenseAssessmentHandlerTurnProtocolTest {

    @Test
    void asynchronousCompletionIgnoresAControlSocketThatClosedAfterSubmission() throws Exception {
        StudentAssessmentService assessmentService = mock(StudentAssessmentService.class);
        InterviewSessionStore sessionStore = mock(InterviewSessionStore.class);
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(assessmentService, sessionStore);
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicReference<Consumer<WebSocketResponse>> responseConsumer = new AtomicReference<>();
        when(session.getAttributes()).thenReturn(authenticatedAttributes());
        org.mockito.Mockito.doAnswer(invocation -> {
            responseConsumer.set(invocation.getArgument(2));
            return null;
        }).when(assessmentService).handleWebsocketMessage(eq(7), any(), any());

        handler.handleTextMessage(session, new TextMessage("""
                {"action":"start-assessment","message":"11"}
                """));
        doThrow(new IllegalStateException("socket already closed"))
                .when(session).sendMessage(any());

        assertThat(responseConsumer.get()).isNotNull();
        assertThatCode(() -> responseConsumer.get().accept(WebSocketResponse.ofLast("处理完成")))
                .doesNotThrowAnyException();
    }

    @Test
    void delegatesProtocolFragmentReassemblyToTheWebSocketContainer() {
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(
                mock(StudentAssessmentService.class), mock(InterviewSessionStore.class));

        assertThat(handler.supportsPartialMessages()).isFalse();
    }

    @Test
    void connectionClosePreservesLocalStateForReconnect() throws Exception {
        StudentAssessmentService assessmentService = mock(StudentAssessmentService.class);
        InterviewSessionStore sessionStore = mock(InterviewSessionStore.class);
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(assessmentService, sessionStore);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(authenticatedAttributes());
        WebsocketManager.addTextSession(7, session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(sessionStore, never()).clearStudent(7);
    }

    @Test
    void staleConnectionCloseCannotClearReplacementConnectionsState() throws Exception {
        StudentAssessmentService assessmentService = mock(StudentAssessmentService.class);
        InterviewSessionStore sessionStore = mock(InterviewSessionStore.class);
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(assessmentService, sessionStore);
        WebSocketSession staleSession = mock(WebSocketSession.class);
        WebSocketSession replacementSession = mock(WebSocketSession.class);
        when(staleSession.getAttributes()).thenReturn(authenticatedAttributes());
        when(replacementSession.getAttributes()).thenReturn(authenticatedAttributes());
        WebsocketManager.addTextSession(7, staleSession);
        WebsocketManager.addTextSession(7, replacementSession);

        handler.afterConnectionClosed(staleSession, CloseStatus.NORMAL);

        verify(sessionStore, never()).clearStudent(7);
        WebsocketManager.removeTextSession(7, replacementSession);
    }

    @Test
    void replacementConnectionPreservesInterviewStateForResume() throws Exception {
        StudentAssessmentService assessmentService = mock(StudentAssessmentService.class);
        InterviewSessionStore sessionStore = mock(InterviewSessionStore.class);
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(assessmentService, sessionStore);
        WebSocketSession staleSession = mock(WebSocketSession.class);
        WebSocketSession replacementSession = mock(WebSocketSession.class);
        when(staleSession.isOpen()).thenReturn(true);
        when(replacementSession.getAttributes()).thenReturn(authenticatedAttributes());
        WebsocketManager.addTextSession(7, staleSession);

        handler.afterConnectionEstablished(replacementSession);

        verify(sessionStore, never()).clearStudent(7);
        verify(staleSession).close();
        WebsocketManager.removeTextSession(7, replacementSession);
    }

    @Test
    void replacementControlConnectionPreservesAnInflightAudioTurn() throws Exception {
        StudentAssessmentService assessmentService = mock(StudentAssessmentService.class);
        InterviewSessionStore sessionStore = new InterviewSessionStore(
                32, 64, 16, 1_024, java.time.Duration.ofMinutes(5));
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(assessmentService, sessionStore);
        WebSocketSession staleSession = mock(WebSocketSession.class);
        WebSocketSession replacementSession = mock(WebSocketSession.class);
        when(staleSession.isOpen()).thenReturn(true);
        when(replacementSession.getAttributes()).thenReturn(authenticatedAttributes());
        WebsocketManager.addTextSession(7, staleSession);
        sessionStore.startAudio(7, "turn-1", 8);

        handler.afterConnectionEstablished(replacementSession);

        assertThat(sessionStore.isAudioRecording(7, "turn-1")).isTrue();
        WebsocketManager.removeTextSession(7, replacementSession);
        sessionStore.clearAudio(7);
    }

    @Test
    void endAnswerIsRejectedUntilTheSameTurnHasBeenFlushedAndAcknowledged() throws Exception {
        StudentAssessmentService assessmentService = mock(StudentAssessmentService.class);
        InterviewSessionStore sessionStore = mock(InterviewSessionStore.class);
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(assessmentService, sessionStore);
        WebSocketSession session = mock(WebSocketSession.class);
        String turnId = UUID.randomUUID().toString();
        when(session.getAttributes()).thenReturn(authenticatedAttributes());
        when(sessionStore.isAudioFlushed(7, turnId)).thenReturn(false);

        handler.handleTextMessage(session, new TextMessage("""
                {"action":"end-answer","message":"11","turnId":"%s"}
                """.formatted(turnId)));

        verify(assessmentService, never()).handleWebsocketMessage(eq(7), any(), any());
        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage<?>> response =
                ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session).sendMessage(response.capture());
        assertThat(response.getValue().getPayload().toString())
                .contains("音频尚未确认")
                .contains("false");
    }

    @Test
    void unknownActionsAreRejectedBeforeTheyReachTheAssessmentService() throws Exception {
        StudentAssessmentService assessmentService = mock(StudentAssessmentService.class);
        InterviewSessionStore sessionStore = mock(InterviewSessionStore.class);
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(assessmentService, sessionStore);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(authenticatedAttributes());

        handler.handleTextMessage(session, new TextMessage("""
                {"action":"delete-everything","message":"11"}
                """));

        verify(assessmentService, never()).handleWebsocketMessage(eq(7), any(), any());
        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage<?>> response =
                ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session).sendMessage(response.capture());
        assertThat(response.getValue().getPayload().toString())
                .contains("消息格式无效")
                .contains("false");
    }

    @Test
    void malformedJsonIsRejectedWithoutEscapingTheHandler() throws Exception {
        StudentAssessmentService assessmentService = mock(StudentAssessmentService.class);
        InterviewSessionStore sessionStore = mock(InterviewSessionStore.class);
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(assessmentService, sessionStore);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(authenticatedAttributes());

        handler.handleTextMessage(session, new TextMessage("{not-json"));

        verify(assessmentService, never()).handleWebsocketMessage(eq(7), any(), any());
        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage<?>> response =
                ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session).sendMessage(response.capture());
        assertThat(response.getValue().getPayload().toString())
                .contains("消息格式无效")
                .contains("false");
    }

    @Test
    void invalidAssessmentIdsAndOversizedTurnIdsAreRejectedAtTheSocketBoundary() throws Exception {
        StudentAssessmentService assessmentService = mock(StudentAssessmentService.class);
        InterviewSessionStore sessionStore = mock(InterviewSessionStore.class);
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(assessmentService, sessionStore);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(authenticatedAttributes());

        handler.handleTextMessage(session, new TextMessage("""
                {"action":"start-assessment","message":"not-an-id"}
                """));
        handler.handleTextMessage(session, new TextMessage("""
                {"action":"start-defense","message":"11","turnId":"%s"}
                """.formatted("x".repeat(129))));

        verify(assessmentService, never()).handleWebsocketMessage(eq(7), any(), any());
        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage<?>> responses =
                ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session, org.mockito.Mockito.times(2)).sendMessage(responses.capture());
        assertThat(responses.getAllValues())
                .allSatisfy(response -> assertThat(response.getPayload().toString())
                        .contains("消息格式无效")
                        .contains("false"));
    }

    @Test
    void synchronousBusinessFailuresCanBeRetriedImmediately() throws Exception {
        StudentAssessmentService assessmentService = mock(StudentAssessmentService.class);
        InterviewSessionStore sessionStore = mock(InterviewSessionStore.class);
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(assessmentService, sessionStore);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(authenticatedAttributes());
        doThrow(new IllegalStateException("temporary failure"))
                .when(assessmentService).handleWebsocketMessage(eq(7), any(), any());
        TextMessage request = new TextMessage("""
                {"action":"start-assessment","message":"987654321"}
                """);

        handler.handleTextMessage(session, request);
        handler.handleTextMessage(session, request);

        verify(assessmentService, times(2)).handleWebsocketMessage(eq(7), any(), any());
    }

    @Test
    void endAnswerFailureReleasesTheFlushedAudioTurnForARealRetry() throws Exception {
        StudentAssessmentService assessmentService = mock(StudentAssessmentService.class);
        InterviewSessionStore sessionStore = mock(InterviewSessionStore.class);
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(assessmentService, sessionStore);
        WebSocketSession session = mock(WebSocketSession.class);
        String turnId = UUID.randomUUID().toString();
        when(session.getAttributes()).thenReturn(authenticatedAttributes());
        when(sessionStore.isAudioFlushed(7, turnId)).thenReturn(true);
        doThrow(new IllegalStateException("temporary failure"))
                .when(assessmentService).handleWebsocketMessage(eq(7), any(), any());

        handler.handleTextMessage(session, new TextMessage("""
                {"action":"end-answer","message":"11","turnId":"%s"}
                """.formatted(turnId)));

        verify(sessionStore).clearAudio(7, turnId);
        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage<?>> response =
                ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session).sendMessage(response.capture());
        assertThat(response.getValue().getPayload().toString())
                .contains("答题处理失败，请重新回答")
                .contains("false");
    }

    @Test
    void endDefenseFailureReleasesTheFlushedAudioTurnForARealRetry() throws Exception {
        StudentAssessmentService assessmentService = mock(StudentAssessmentService.class);
        InterviewSessionStore sessionStore = mock(InterviewSessionStore.class);
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(assessmentService, sessionStore);
        WebSocketSession session = mock(WebSocketSession.class);
        String turnId = UUID.randomUUID().toString();
        when(session.getAttributes()).thenReturn(authenticatedAttributes());
        when(sessionStore.isAudioFlushed(7, turnId)).thenReturn(true);
        doThrow(new IllegalStateException("temporary failure"))
                .when(assessmentService).handleWebsocketMessage(eq(7), any(), any());

        handler.handleTextMessage(session, new TextMessage("""
                {"action":"end-defense","message":"11","turnId":"%s"}
                """.formatted(turnId)));

        verify(sessionStore).clearAudio(7, turnId);
        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage<?>> response =
                ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session).sendMessage(response.capture());
        assertThat(response.getValue().getPayload().toString())
                .contains("答辩处理失败，请重新答辩")
                .contains("false");
    }

    @Test
    void revokedTokensCloseTheSocketBeforeBusinessHandling() throws Exception {
        StudentAssessmentService assessmentService = mock(StudentAssessmentService.class);
        InterviewSessionStore sessionStore = mock(InterviewSessionStore.class);
        DefenseAssessmentHandler handler = new DefenseAssessmentHandler(assessmentService, sessionStore);
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new java.util.HashMap<>();
        attributes.put(WebSocketSessionAuthentication.STUDENT_ID_ATTRIBUTE, "7");
        attributes.put(WebSocketSessionAuthentication.TOKEN_ATTRIBUTE, "revoked-token");
        attributes.put(WebSocketSessionAuthentication.VALID_UNTIL_ATTRIBUTE, 0L);
        when(session.getAttributes()).thenReturn(attributes);

        try (MockedStatic<StpUtil> stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.getLoginIdByToken("revoked-token")).thenReturn(null);

            handler.handleTextMessage(session, new TextMessage("""
                    {"action":"start-assessment","message":"11"}
                    """));
        }

        verify(assessmentService, never()).handleWebsocketMessage(eq(7), any(), any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        ArgumentCaptor<org.springframework.web.socket.WebSocketMessage<?>> response =
                ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session).sendMessage(response.capture());
        assertThat(response.getValue().getPayload().toString())
                .contains("登录已过期")
                .contains("false");
    }

    private Map<String, Object> authenticatedAttributes() {
        return Map.of(
                WebSocketSessionAuthentication.STUDENT_ID_ATTRIBUTE, "7",
                WebSocketSessionAuthentication.TOKEN_ATTRIBUTE, "test-token",
                WebSocketSessionAuthentication.VALID_UNTIL_ATTRIBUTE, Long.MAX_VALUE);
    }
}
