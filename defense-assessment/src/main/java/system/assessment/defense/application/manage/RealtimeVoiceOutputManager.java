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

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class RealtimeVoiceOutputManager {

    private static final String QUESTION_VOICE_INSTRUCTIONS = """
            你是面试现场的语音播报器。
            你的唯一任务是把题目文本原样朗读成语音。
            禁止回答题目，禁止解释题目，禁止扩写、改写或补充任何内容。
            语速自然，语气清晰、专业、平稳。
            """ + AiPromptBoundary.SYSTEM_GUARD;

    private final okhttp3.OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final RealtimeSpeechEventFactory eventFactory;

    @Value("${dashscope.api-key}")
    private String apiKey;

    @Value("${dashscope.realtime.voice-output-enabled:true}")
    private boolean enabled;

    @Value("${dashscope.realtime.url:wss://dashscope.aliyuncs.com/api-ws/v1/realtime}")
    private String realtimeUrl;

    @Value("${dashscope.realtime.model:qwen3.5-omni-flash-realtime}")
    private String realtimeModel;

    @Value("${dashscope.realtime.voice:Ethan}")
    private String realtimeVoice;

    @Value("${dashscope.realtime.voice-output-wait-ms:12000}")
    private long voiceOutputWaitMs;

    @Value("${dashscope.realtime.max-event-chars:1048576}")
    private int maxEventChars;

    @Value("${dashscope.realtime.max-response-audio-bytes:16777216}")
    private long maxResponseAudioBytes;

    public boolean streamQuestionVoice(String questionText, Consumer<byte[]> audioChunkConsumer) {
        if (!enabled || StringUtils.isBlank(apiKey) || StringUtils.isBlank(questionText)) {
            return false;
        }

        CompletableFuture<Boolean> done = new CompletableFuture<>();
        AtomicBoolean hasAudio = new AtomicBoolean(false);
        AtomicBoolean responseRequested = new AtomicBoolean(false);
        AtomicLong audioBytes = new AtomicLong();
        AtomicReference<WebSocket> upstream = new AtomicReference<>();
        Request request = new Request.Builder()
                .url(realtimeUrl + "?model=" + realtimeModel)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        okHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                upstream.set(webSocket);
                webSocket.send(eventFactory.audioOutputSessionUpdate(realtimeVoice, QUESTION_VOICE_INSTRUCTIONS));
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                handleServerEvent(webSocket, text, questionText, audioChunkConsumer,
                        hasAudio, responseRequested, audioBytes, done);
            }

            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                done.complete(hasAudio.get());
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
                log.warn("Realtime voice output failed, model:{}, errorType:{}",
                        realtimeModel, t.getClass().getSimpleName());
                done.complete(hasAudio.get());
            }
        });

        try {
            return done.get(voiceOutputWaitMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Realtime voice output timeout or unavailable, model:{}, waitMs:{}, errorType:{}",
                    realtimeModel, voiceOutputWaitMs, e.getClass().getSimpleName());
            WebSocket webSocket = upstream.get();
            if (webSocket != null) {
                webSocket.close(1000, "voice timeout");
            }
            return hasAudio.get();
        }
    }

    void handleServerEvent(WebSocket webSocket,
                           String text,
                           String questionText,
                           Consumer<byte[]> audioChunkConsumer,
                           AtomicBoolean hasAudio,
                           AtomicBoolean responseRequested,
                           AtomicLong audioBytes,
                           CompletableFuture<Boolean> done) {
        if (done.isDone()) {
            return;
        }
        if (text == null || text.length() > Math.max(1, maxEventChars)) {
            log.warn("Realtime voice output event exceeded limit, eventChars:{}",
                    text == null ? 0 : text.length());
            done.complete(hasAudio.get());
            webSocket.close(1009, "voice event too large");
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(text);
            String type = root.path("type").asText("");
            switch (type) {
                case "session.updated" -> requestAudioResponse(webSocket, questionText, responseRequested);
                case "response.audio.delta" -> {
                    String delta = root.path("delta").asText("");
                    if (StringUtils.isNotBlank(delta)) {
                        byte[] decodedAudio = Base64.getDecoder().decode(delta);
                        long totalBytes = audioBytes.addAndGet(decodedAudio.length);
                        if (totalBytes > Math.max(1L, maxResponseAudioBytes)) {
                            log.warn("Realtime voice output exceeded audio limit, audioBytes:{}", totalBytes);
                            done.complete(hasAudio.get());
                            webSocket.close(1009, "voice audio too large");
                            return;
                        }
                        hasAudio.set(true);
                        audioChunkConsumer.accept(decodedAudio);
                    }
                }
                case "response.audio.done", "response.done" -> {
                    done.complete(hasAudio.get());
                    webSocket.close(1000, "voice done");
                }
                case "error" -> {
                    log.warn("Realtime voice output error event received, errorType:{}",
                            root.path("error").path("type").asText("unknown"));
                    done.complete(hasAudio.get());
                    webSocket.close(1000, "voice error");
                }
                default -> {
                }
            }
        } catch (Exception e) {
            log.warn("Realtime voice output event parse failed, errorType:{}", e.getClass().getSimpleName());
        }
    }

    private void requestAudioResponse(WebSocket webSocket, String questionText, AtomicBoolean responseRequested) {
        if (!responseRequested.compareAndSet(false, true)) {
            return;
        }
        webSocket.send(eventFactory.inputAudioClear());
        webSocket.send(eventFactory.inputAudioAppend(silentPcm16kMono(300)));
        webSocket.send(eventFactory.inputAudioCommit());
        webSocket.send(eventFactory.responseCreateForAudio(buildReadQuestionPrompt(questionText)));
    }

    private byte[] silentPcm16kMono(int millis) {
        int sampleRate = 16000;
        int bytesPerSample = 2;
        int length = Math.max(1, sampleRate * bytesPerSample * millis / 1000);
        return new byte[length];
    }

    private String buildReadQuestionPrompt(String questionText) {
        return """
                请把下面的题目文本原样朗读成语音。
                输出内容只能是题目文本本身，禁止回答、解释、扩写或改写。

                %s
                """.formatted(AiPromptBoundary.wrapUntrustedData(Map.of(
                        "questionText", StringUtils.defaultString(questionText))));
    }
}
