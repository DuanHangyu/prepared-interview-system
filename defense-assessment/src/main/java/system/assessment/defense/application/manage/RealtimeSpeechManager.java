package system.assessment.defense.application.manage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
@Slf4j
public class RealtimeSpeechManager {

    private static final int DEFAULT_MAX_EVENT_CHARS = 1_048_576;
    private static final int DEFAULT_MAX_TRANSCRIPT_CHARS = 20_000;
    private static final long DEFAULT_MAX_RESPONSE_AUDIO_BYTES = 16_777_216L;

    private static final String INTERVIEW_TRANSCRIPTION_INSTRUCTIONS = """
            你是 AI 面试系统的实时语音转写助手。
            只转写学生正在回答面试题或答辩内容的语音，保持原意，不总结、不补充、不回答问题。
            当系统显式请求你生成面试官回复时，你才作为面试官基于刚才的学生回答直接生成简洁追问。
            如果听不清，请尽量转写可确认的内容。
            """;

    private final okhttp3.OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final RealtimeSpeechEventFactory eventFactory;

    private final Map<Integer, RealtimeSpeechSession> sessions = new ConcurrentHashMap<>();

    @Value("${dashscope.api-key}")
    private String apiKey;

    @Value("${dashscope.realtime.enabled:true}")
    private boolean enabled;

    @Value("${dashscope.realtime.url:wss://dashscope.aliyuncs.com/api-ws/v1/realtime}")
    private String realtimeUrl;

    @Value("${dashscope.realtime.model:qwen3.5-omni-flash-realtime}")
    private String realtimeModel;

    @Value("${dashscope.realtime.voice:Ethan}")
    private String realtimeVoice;

    @Value("${dashscope.realtime.transcript-wait-ms:5000}")
    private long transcriptWaitMs;

    @Value("${dashscope.realtime.response-wait-ms:20000}")
    private long responseWaitMs;

    @Value("${dashscope.realtime.max-event-chars:1048576}")
    private int maxEventChars;

    @Value("${dashscope.realtime.max-transcript-chars:20000}")
    private int maxTranscriptChars;

    @Value("${dashscope.realtime.max-response-audio-bytes:16777216}")
    private long maxResponseAudioBytes;

    public void open(Integer studentId, WebSocketSession clientSession) {
        if (!enabled) {
            log.info("Realtime speech disabled, studentId:{}", studentId);
            return;
        }
        if (StringUtils.isBlank(apiKey)) {
            log.warn("Realtime speech skipped because dashscope api key is blank, studentId:{}", studentId);
            return;
        }

        close(studentId);
        RealtimeSpeechSession session = new RealtimeSpeechSession(
                studentId,
                clientSession,
                Math.max(1, maxEventChars),
                Math.max(1, maxTranscriptChars),
                Math.max(1L, maxResponseAudioBytes));
        sessions.put(studentId, session);

        Request request = new Request.Builder()
                .url(realtimeUrl + "?model=" + realtimeModel)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();
        okHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                if (session.isClosed() || sessions.get(studentId) != session) {
                    webSocket.close(1000, "client already closed");
                    return;
                }
                session.upstream = webSocket;
                session.open.set(true);
                webSocket.send(eventFactory.realtimeInterviewSessionUpdate(realtimeVoice, INTERVIEW_TRANSCRIPTION_INSTRUCTIONS));
                log.info("Realtime speech connected, studentId:{}, model:{}", studentId, realtimeModel);
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                handleServerEvent(session, text);
            }

            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                session.open.set(false);
                log.info("Realtime speech closed, studentId:{}, code:{}, reasonLength:{}",
                        studentId, code, reason.length());
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
                session.open.set(false);
                session.completeExceptionally(t);
                String responseMessage = response != null ? response.message() : "";
                log.error("Realtime speech failed, studentId:{}, responseLength:{}, errorType:{}",
                        studentId, responseMessage == null ? 0 : responseMessage.length(),
                        t.getClass().getSimpleName());
            }
        });
    }

    public void startTurn(Integer studentId) {
        RealtimeSpeechSession session = sessions.get(studentId);
        if (session == null) {
            return;
        }
        session.startTurn();
        if (session.isOpen()) {
            session.send(eventFactory.inputAudioClear());
        }
    }

    public boolean appendAudio(Integer studentId, ByteBuffer payload) {
        RealtimeSpeechSession session = sessions.get(studentId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        byte[] bytes = copyRemaining(payload);
        if (bytes.length == 0) {
            return true;
        }
        return session.send(eventFactory.inputAudioAppend(bytes));
    }

    public Optional<String> completeTurnAndAwaitTranscript(Integer studentId) {
        RealtimeSpeechSession session = sessions.get(studentId);
        if (session == null || !session.isOpen()) {
            return Optional.empty();
        }
        session.resetTranscriptFuture();
        session.send(eventFactory.inputAudioCommit());
        try {
            String transcript = session.transcriptFuture.get(transcriptWaitMs, TimeUnit.MILLISECONDS);
            return StringUtils.isBlank(transcript) ? Optional.empty() : Optional.of(transcript.trim());
        } catch (TimeoutException e) {
            log.warn("Realtime transcript timeout, studentId:{}, waitMs:{}", studentId, transcriptWaitMs);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Realtime transcript unavailable, studentId:{}, errorType:{}",
                    studentId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public Optional<RealtimeTurnResponse> completeTurnAndStreamResponse(Integer studentId,
                                                                        String responseInstructions,
                                                                        Consumer<byte[]> audioChunkConsumer,
                                                                        Consumer<String> responseTranscriptDoneConsumer) {
        return completeTurnAndStreamResponse(studentId, ignored -> responseInstructions,
                audioChunkConsumer, responseTranscriptDoneConsumer);
    }

    public Optional<RealtimeTurnResponse> completeTurnAndStreamResponse(Integer studentId,
                                                                        Function<String, String> responseInstructionsFactory,
                                                                        Consumer<byte[]> audioChunkConsumer,
                                                                        Consumer<String> responseTranscriptDoneConsumer) {
        RealtimeSpeechSession session = sessions.get(studentId);
        if (session == null || !session.isOpen()) {
            return Optional.empty();
        }

        session.resetTranscriptFuture();
        session.send(eventFactory.inputAudioCommit());
        String inputTranscript = "";
        try {
            inputTranscript = session.transcriptFuture.get(transcriptWaitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Realtime transcript timeout before response, studentId:{}, waitMs:{}", studentId, transcriptWaitMs);
        } catch (Exception e) {
            log.warn("Realtime transcript unavailable before response, studentId:{}, errorType:{}",
                    studentId, e.getClass().getSimpleName());
        }

        session.startAssistantResponse(inputTranscript, audioChunkConsumer, responseTranscriptDoneConsumer);
        String responseInstructions = responseInstructionsFactory != null
                ? responseInstructionsFactory.apply(inputTranscript)
                : null;
        session.send(eventFactory.responseCreateForAudio(responseInstructions));
        try {
            return Optional.of(session.responseFuture.get(responseWaitMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            log.warn("Realtime response timeout, studentId:{}, waitMs:{}", studentId, responseWaitMs);
            session.notifyResponseTranscriptIfPossible();
            return Optional.of(session.snapshotResponse());
        } catch (Exception e) {
            log.warn("Realtime response unavailable, studentId:{}, errorType:{}",
                    studentId, e.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            session.clearAssistantResponseHandlers();
        }
    }

    public void close(Integer studentId) {
        RealtimeSpeechSession session = sessions.remove(studentId);
        if (session != null) {
            session.close();
        }
    }

    public void close(Integer studentId, WebSocketSession clientSession) {
        RealtimeSpeechSession session = sessions.get(studentId);
        if (session != null && session.belongsTo(clientSession) && sessions.remove(studentId, session)) {
            session.close();
        }
    }

    public Duration transcriptWaitDuration() {
        return Duration.ofMillis(transcriptWaitMs);
    }

    private void handleServerEvent(RealtimeSpeechSession session, String text) {
        if (!session.isOpen() || session.rejectOversizedEvent(text)) {
            return;
        }
        eventFactory.extractCompletedTranscript(text).ifPresent(transcript -> {
            if (session.completeTranscript(transcript)) {
                sendClientTranscript(session.clientSession, transcript);
            }
        });

        try {
            JsonNode root = objectMapper.readTree(text);
            String type = root.path("type").asText("");
            switch (type) {
                case "response.audio.delta" -> {
                    String delta = root.path("delta").asText("");
                    if (StringUtils.isNotBlank(delta)) {
                        session.acceptResponseAudio(Base64.getDecoder().decode(delta));
                    }
                }
                case "response.audio_transcript.delta", "response.text.delta" -> {
                    String delta = root.path("delta").asText("");
                    session.appendResponseTranscript(delta);
                }
                case "response.audio_transcript.done" -> {
                    String transcript = root.path("transcript").asText("");
                    session.finishResponseTranscript(transcript);
                }
                case "response.text.done" -> {
                    String transcript = root.path("text").asText("");
                    session.finishResponseTranscript(transcript);
                }
                case "response.done" -> session.completeResponse();
                case "error" -> session.completeResponseExceptionally(new IllegalStateException(root.path("error").toString()));
                default -> {
                }
            }
        } catch (Exception e) {
            log.warn("Realtime response event parse failed, studentId:{}, errorType:{}",
                    session.studentId, e.getClass().getSimpleName());
        }
    }

    private void sendClientTranscript(WebSocketSession clientSession, String transcript) {
        if (clientSession == null || !clientSession.isOpen()) {
            return;
        }
        try {
            synchronized (clientSession) {
                clientSession.sendMessage(new TextMessage(transcript));
            }
        } catch (IOException e) {
            log.warn("send realtime transcript to client failed, errorType:{}", e.getClass().getSimpleName());
        }
    }

    private byte[] copyRemaining(ByteBuffer buffer) {
        ByteBuffer copy = buffer.asReadOnlyBuffer();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private static class RealtimeSpeechSession {
        private final Integer studentId;
        private final WebSocketSession clientSession;
        private final int maxEventChars;
        private final int maxTranscriptChars;
        private final long maxResponseAudioBytes;
        private final AtomicBoolean open = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile WebSocket upstream;
        private volatile CompletableFuture<String> transcriptFuture = new CompletableFuture<>();
        private volatile CompletableFuture<RealtimeTurnResponse> responseFuture = new CompletableFuture<>();
        private volatile Consumer<byte[]> responseAudioConsumer;
        private volatile Consumer<String> responseTranscriptDoneConsumer;
        private volatile String inputTranscript = "";
        private final StringBuilder responseTranscriptBuilder = new StringBuilder();
        private final AtomicBoolean responseTranscriptNotified = new AtomicBoolean(false);
        private final AtomicBoolean hasResponseAudio = new AtomicBoolean(false);
        private final AtomicLong responseAudioBytes = new AtomicLong();

        private RealtimeSpeechSession(Integer studentId, WebSocketSession clientSession) {
            this(studentId, clientSession,
                    DEFAULT_MAX_EVENT_CHARS,
                    DEFAULT_MAX_TRANSCRIPT_CHARS,
                    DEFAULT_MAX_RESPONSE_AUDIO_BYTES);
        }

        private RealtimeSpeechSession(Integer studentId,
                                      WebSocketSession clientSession,
                                      int maxEventChars,
                                      int maxTranscriptChars,
                                      long maxResponseAudioBytes) {
            this.studentId = studentId;
            this.clientSession = clientSession;
            this.maxEventChars = maxEventChars;
            this.maxTranscriptChars = maxTranscriptChars;
            this.maxResponseAudioBytes = maxResponseAudioBytes;
        }

        private boolean isOpen() {
            return !closed.get() && open.get() && upstream != null;
        }

        private boolean isClosed() {
            return closed.get();
        }

        private boolean belongsTo(WebSocketSession clientSession) {
            return this.clientSession == clientSession;
        }

        private synchronized void startTurn() {
            transcriptFuture = new CompletableFuture<>();
            responseFuture = new CompletableFuture<>();
            inputTranscript = "";
            synchronized (responseTranscriptBuilder) {
                responseTranscriptBuilder.setLength(0);
            }
            responseTranscriptNotified.set(false);
            hasResponseAudio.set(false);
            responseAudioBytes.set(0);
        }

        /**
         * 为新一轮 input_audio_buffer.commit 重置输入转写 future。
         * 修复追问轮次复用上一轮已 complete 的 transcriptFuture（恒得空串、强制退回离线 ASR）的问题。
         * 不动 responseFuture（由 startAssistantResponse 自行管理）。
         */
        private synchronized void resetTranscriptFuture() {
            transcriptFuture = new CompletableFuture<>();
            inputTranscript = "";
        }

        private boolean send(String eventJson) {
            WebSocket webSocket = upstream;
            return webSocket != null && webSocket.send(eventJson);
        }

        private boolean completeTranscript(String transcript) {
            String safeTranscript = StringUtils.defaultString(transcript);
            if (safeTranscript.length() > maxTranscriptChars) {
                failForLimit("input transcript too large");
                return false;
            }
            inputTranscript = safeTranscript;
            transcriptFuture.complete(safeTranscript);
            return true;
        }

        private void completeExceptionally(Throwable t) {
            transcriptFuture.completeExceptionally(t);
            completeResponseExceptionally(t);
        }

        private synchronized void startAssistantResponse(String transcript,
                                                         Consumer<byte[]> audioConsumer,
                                                         Consumer<String> transcriptDoneConsumer) {
            inputTranscript = StringUtils.defaultString(transcript);
            responseFuture = new CompletableFuture<>();
            responseAudioConsumer = audioConsumer;
            responseTranscriptDoneConsumer = transcriptDoneConsumer;
            synchronized (responseTranscriptBuilder) {
                responseTranscriptBuilder.setLength(0);
            }
            responseTranscriptNotified.set(false);
            hasResponseAudio.set(false);
            responseAudioBytes.set(0);
        }

        private void acceptResponseAudio(byte[] audio) {
            if (audio == null || audio.length == 0) {
                return;
            }
            long totalBytes = responseAudioBytes.addAndGet(audio.length);
            if (totalBytes > maxResponseAudioBytes) {
                failForLimit("response audio too large");
                return;
            }
            hasResponseAudio.set(true);
            Consumer<byte[]> consumer = responseAudioConsumer;
            if (consumer != null) {
                consumer.accept(audio);
            }
        }

        private void appendResponseTranscript(String delta) {
            if (StringUtils.isBlank(delta)) {
                return;
            }
            synchronized (responseTranscriptBuilder) {
                if ((long) responseTranscriptBuilder.length() + delta.length() > maxTranscriptChars) {
                    failForLimit("response transcript too large");
                    return;
                }
                responseTranscriptBuilder.append(delta);
            }
        }

        private void finishResponseTranscript(String transcript) {
            if (StringUtils.isNotBlank(transcript)) {
                String normalized = transcript.trim();
                if (normalized.length() > maxTranscriptChars) {
                    failForLimit("response transcript too large");
                    return;
                }
                synchronized (responseTranscriptBuilder) {
                    responseTranscriptBuilder.setLength(0);
                    responseTranscriptBuilder.append(normalized);
                }
            }
            notifyResponseTranscriptIfPossible();
        }

        private void notifyResponseTranscriptIfPossible() {
            String transcript = currentResponseTranscript();
            if (StringUtils.isBlank(transcript) || !responseTranscriptNotified.compareAndSet(false, true)) {
                return;
            }
            Consumer<String> consumer = responseTranscriptDoneConsumer;
            if (consumer != null) {
                consumer.accept(transcript);
            }
        }

        private void completeResponse() {
            notifyResponseTranscriptIfPossible();
            responseFuture.complete(snapshotResponse());
        }

        private void completeResponseExceptionally(Throwable t) {
            responseFuture.completeExceptionally(t);
        }

        private RealtimeTurnResponse snapshotResponse() {
            return new RealtimeTurnResponse(inputTranscript, currentResponseTranscript(), hasResponseAudio.get());
        }

        private String currentResponseTranscript() {
            synchronized (responseTranscriptBuilder) {
                return responseTranscriptBuilder.toString().trim();
            }
        }

        private void clearAssistantResponseHandlers() {
            responseAudioConsumer = null;
            responseTranscriptDoneConsumer = null;
        }

        private boolean rejectOversizedEvent(String text) {
            if (text != null && text.length() <= maxEventChars) {
                return false;
            }
            failForLimit("realtime event too large");
            return true;
        }

        private void failForLimit(String reason) {
            IllegalStateException failure = new IllegalStateException(reason);
            open.set(false);
            transcriptFuture.completeExceptionally(failure);
            responseFuture.completeExceptionally(failure);
            clearAssistantResponseHandlers();
            WebSocket webSocket = upstream;
            if (webSocket != null) {
                webSocket.close(1009, reason);
            }
        }

        private void close() {
            closed.set(true);
            open.set(false);
            WebSocket webSocket = upstream;
            if (webSocket != null) {
                webSocket.close(1000, "client closed");
            }
            transcriptFuture.complete("");
            responseFuture.complete(snapshotResponse());
        }

        @Override
        public String toString() {
            return "RealtimeSpeechSession{studentId=" + studentId + '}';
        }
    }

    public record RealtimeTurnResponse(String inputTranscript, String responseTranscript, boolean hasAudio) {
    }
}
