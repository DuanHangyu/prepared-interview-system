package system.assessment.defense.application.manage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import system.assessment.defense.application.dto.OmniAudioResponseDTO;
import system.assessment.defense.infrastructure.repository.dao.mapper.TokenConsumerMapper;
import system.assessment.defense.infrastructure.repository.dao.po.TokenConsumerPO;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiManager {

    private static final int MAX_JSON_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TEXT_STREAM_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_AUDIO_STREAM_RESPONSE_BYTES = 32 * 1024 * 1024;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TokenConsumerMapper tokenConsumerMapper;
    private final ThreadPoolExecutor threadPoolExecutor;

    @Value("${dashscope.base-url}")
    private String baseUrl;

    @Value("${dashscope.api-key}")
    private String apiKey;

    @Value("${dashscope.models.text}")
    private String textModel;

    @Value("${dashscope.models.structured:qwen3.7-plus}")
    private String structuredModel;

    @Value("${dashscope.models.omni}")
    private String omniModel;

    @Value("${dashscope.models.doc:qwen-long}")
    private String docModel;

    @Value("${dashscope.enable-thinking:false}")
    private boolean enableThinking;

    public String getTextModel() {
        return textModel;
    }

    public String getOmniModel() {
        return omniModel;
    }

    public String getStructuredModel() {
        return structuredModel;
    }

    /**
     * 纯文本对话
     */
    public String chatCompletion(String model, String systemPrompt, String userPrompt) {
        return chatCompletion(model, systemPrompt, userPrompt, null, null, null);
    }

    /**
     * 带 JSON Schema 约束的对话
     */
    public String chatCompletionWithSchema(String model, String systemPrompt, String userPrompt,
                                            String schemaName, String schemaJson) {
        return chatCompletion(model, systemPrompt, userPrompt, schemaName, schemaJson, null);
    }

    /**
     * 带 JSON Schema 约束且具有单次调用超时的对话。
     * 适用于需要快速降级的交互式请求，避免沿用全局长超时阻塞用户界面。
     */
    public String chatCompletionWithSchema(String model, String systemPrompt, String userPrompt,
                                            String schemaName, String schemaJson,
                                            long callTimeoutMillis) {
        if (callTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Call timeout must be positive");
        }
        Map<String, Object> message = Map.of("role", "user", "content",
                List.of(Map.of("type", "text", "text", userPrompt)));
        List<Map<String, Object>> messages = buildMessages(systemPrompt, message);
        return doCall(model, messages, schemaName, schemaJson, null, callTimeoutMillis);
    }

    /**
     * 带文件（PDF 等）的对话
     */
    public String chatCompletionWithFiles(String model, String systemPrompt, String userPrompt,
                                           List<FileInput> files) {
        return chatCompletion(model, systemPrompt, userPrompt, null, null, files);
    }

    /**
     * 上传文件到 DashScope（用于 qwen-long 文档理解）
     */
    public String uploadFile(byte[] data, String filename, String purpose) {
        try {
            MultipartBody multipartBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("purpose", purpose)
                    .addFormDataPart("file", filename,
                            RequestBody.create(data, MediaType.parse("application/octet-stream")))
                    .build();

            Request request = new Request.Builder()
                    .url(baseUrl + "/files")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(multipartBody)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = readBoundedResponseBody(response.body());
                if (!response.isSuccessful()) {
                    log.error("upload file failed, status:{}, responseBytes:{}",
                            response.code(), responseBody.length());
                    throw new RuntimeException("File upload failed with status " + response.code());
                }
                JsonNode root = objectMapper.readTree(responseBody);
                String fileId = root.path("id").asText("");
                log.info("upload file success, fileIdLength:{}, responseBytes:{}",
                        fileId.length(), responseBody.length());
                return fileId;
            }
        } catch (IOException e) {
            log.error("upload file error", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 使用 qwen-long 模型处理文档（PDF 提取等）
     */
    public String chatCompletionWithDocument(String systemPrompt, String fileId, String userPrompt,
                                              String schemaName, String schemaJson) {
        log.info("chatCompletionWithDocument fileIdLength:{}, systemPromptLength:{}",
                fileId == null ? 0 : fileId.length(), systemPrompt != null ? systemPrompt.length() : 0);
        String rolePrompt = (systemPrompt != null && !systemPrompt.isBlank()) ? systemPrompt : "You are a helpful assistant.";
        return doCall(docModel, List.of(
                Map.of("role", "system", "content", rolePrompt),
                Map.of("role", "system", "content", "fileid://" + fileId),
                Map.of("role", "user", "content", userPrompt)
        ), schemaName, schemaJson, "文档处理");
    }

    /**
     * 带音频的对话（用于语音转写，Qwen-Omni 要求流式输出）
     */
    public String chatCompletionWithAudio(String model, String systemPrompt, byte[] audioBytes, String audioFormat, String userPrompt) {
        String base64Audio = Base64.getEncoder().encodeToString(audioBytes);

        Map<String, Object> audioContent = Map.of(
                "type", "input_audio",
                "input_audio", Map.of(
                        "data", "data:;base64," + base64Audio,
                        "format", audioFormat
                )
        );

        Map<String, Object> textContent = Map.of(
                "type", "text",
                "text", userPrompt != null ? userPrompt : "请获取语音的转写内容"
        );

        List<Map<String, Object>> contentParts = new ArrayList<>();
        contentParts.add(audioContent);
        contentParts.add(textContent);

        Map<String, Object> message = Map.of(
                "role", "user",
                "content", contentParts
        );

        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content",
                    List.of(Map.of("type", "text", "text", systemPrompt))));
        }
        messages.add(message);

        return doStreamingCall(model, messages, "语音转写");
    }

    public OmniAudioResponseDTO chatCompletionWithAudioOutput(String model, String systemPrompt, String userPrompt,
                                                              String voice, String format) {
        return chatCompletionWithAudioOutput(model, systemPrompt, userPrompt, voice, format, null);
    }

    public OmniAudioResponseDTO chatCompletionWithAudioOutput(String model, String systemPrompt, String userPrompt,
                                                              String voice, String format,
                                                              Consumer<byte[]> audioChunkConsumer) {
        Map<String, Object> message = Map.of("role", "user", "content", userPrompt);
        List<Map<String, Object>> messages = buildMessages(systemPrompt, message);
        return doAudioStreamingCall(model, messages, voice, format, "Omni语音生成", audioChunkConsumer);
    }

    /**
     * 统一调用方法
     */
    private String chatCompletion(String model, String systemPrompt, String userPrompt,
                                   String schemaName, String schemaJson, List<FileInput> files) {
        if (files != null && !files.isEmpty()) {
            boolean hasPdf = files.stream().anyMatch(f -> f.mimeType().contains("pdf"));
            if (hasPdf) {
                FileInput pdfFile = files.stream()
                        .filter(f -> f.mimeType().contains("pdf"))
                        .findFirst()
                        .orElseThrow();
                String fileId = uploadFile(pdfFile.data(), "document.pdf", "file-extract");
                return chatCompletionWithDocument(systemPrompt, fileId, userPrompt, schemaName, schemaJson);
            }
        }

        List<Map<String, Object>> contentParts = new ArrayList<>();
        contentParts.add(Map.of("type", "text", "text", userPrompt));

        if (files != null && !files.isEmpty()) {
            for (FileInput file : files) {
                String base64Data = Base64.getEncoder().encodeToString(file.data());
                String mimeType = file.mimeType();
                contentParts.add(Map.of(
                        "type", "image_url",
                        "image_url", Map.of("url",
                                "data:" + mimeType + ";base64," + base64Data)
                ));
            }
        }

        Map<String, Object> message = Map.of("role", "user", "content", contentParts);
        List<Map<String, Object>> messages = buildMessages(systemPrompt, message);
        return doCall(model, messages, schemaName, schemaJson, null);
    }

    /**
     * 多轮对话（用于 GeminiController.lastQuestion 替换）
     */
    public String chatCompletionMultiTurn(String model, List<Map<String, Object>> messages) {
        return doCall(model, messages, null, null, null);
    }

    private List<Map<String, Object>> buildMessages(String systemPrompt, Map<String, Object> userMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(userMessage);
        return messages;
    }

    private void applyThinkingMode(Map<String, Object> body) {
        body.put("enable_thinking", enableThinking);
    }

    private String doCall(String model, List<Map<String, Object>> messages,
                           String schemaName, String schemaJson, String logLabel) {
        return doCall(model, messages, schemaName, schemaJson, logLabel, 0);
    }

    private String doCall(String model, List<Map<String, Object>> messages,
                          String schemaName, String schemaJson, String logLabel,
                          long callTimeoutMillis) {
        long start = System.currentTimeMillis();
        String uuid = UUID.randomUUID().toString();
        String label = logLabel != null ? logLabel : "chatCompletion";
        log.info("start {} request, uuid:{}, model:{}, time:{}", label, uuid, model, start);

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            applyThinkingMode(body);

            if (schemaName != null && schemaJson != null) {
                body.put("enable_thinking", false);
                body.put("response_format", buildStructuredResponseFormat(objectMapper, schemaName, schemaJson));
            }

            String jsonBody = objectMapper.writeValueAsString(body);
            log.info("{} uuid:{}, model:{}, body length:{}", label, uuid, model, jsonBody.length());

            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            Call call = httpClient.newCall(request);
            if (callTimeoutMillis > 0) {
                call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS);
            }
            try (Response response = call.execute()) {
                String responseBody = readBoundedResponseBody(response.body());
                if (!response.isSuccessful()) {
                    log.error("{} uuid:{} failed, status:{}, responseBytes:{}",
                            label, uuid, response.code(), responseBody.length());
                    throw new RuntimeException("API call failed with status " + response.code());
                }

                JsonNode root = objectMapper.readTree(responseBody);
                String content = root.path("choices").path(0).path("message").path("content").asText("");

                JsonNode usage = root.path("usage");
                int promptTokens = usage.path("prompt_tokens").asInt(0);
                int completionTokens = usage.path("completion_tokens").asInt(0);
                int totalTokens = usage.path("total_tokens").asInt(0);

                log.info("{} uuid:{}, prompt_tokens:{}, completion_tokens:{}, total_tokens:{}, time:{}",
                        label, uuid, promptTokens, completionTokens, totalTokens,
                        System.currentTimeMillis() - start);
                log.info("{} uuid:{}, response length:{}, time:{}", label, uuid,
                        content.length(), System.currentTimeMillis() - start);

                persistAuditAsync(buildAuditRecord(label, model, jsonBody.length(),
                        content.length(), 0, promptTokens, completionTokens, totalTokens,
                        System.currentTimeMillis() - start, schemaName != null));

                return content;
            }
        } catch (IOException e) {
            log.error("{} uuid:{} error", label, uuid, e);
            throw new RuntimeException(e);
        }
    }

    static Map<String, Object> buildStructuredResponseFormat(ObjectMapper objectMapper,
                                                              String schemaName,
                                                              String schemaJson) {
        try {
            JsonNode schema = objectMapper.readTree(schemaJson);
            if (!schema.isObject()) {
                throw new IllegalArgumentException("JSON Schema root must be an object");
            }
            return Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", schemaName,
                            "strict", true,
                            "schema", schema
                    )
            );
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON Schema", e);
        }
    }

    static String readBoundedResponseBody(ResponseBody responseBody) throws IOException {
        if (responseBody == null) {
            return "";
        }
        long declaredLength = responseBody.contentLength();
        if (declaredLength > MAX_JSON_RESPONSE_BYTES) {
            throw new IOException("AI response exceeds the configured size limit");
        }
        try (InputStream input = responseBody.byteStream()) {
            byte[] bytes = input.readNBytes(MAX_JSON_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_JSON_RESPONSE_BYTES) {
                throw new IOException("AI response exceeds the configured size limit");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static InputStream boundedResponseStream(InputStream input, long maximumBytes) {
        if (input == null) {
            throw new IllegalArgumentException("Response stream is required");
        }
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("Response size limit must not be negative");
        }
        return new FilterInputStream(input) {
            private long bytesRead;

            @Override
            public int read() throws IOException {
                int value = super.read();
                if (value >= 0) {
                    accountFor(1);
                }
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = super.read(bytes, offset, length);
                if (count > 0) {
                    accountFor(count);
                }
                return count;
            }

            private void accountFor(int count) throws IOException {
                bytesRead += count;
                if (bytesRead > maximumBytes) {
                    throw new IOException("AI streaming response exceeds the configured size limit");
                }
            }
        };
    }

    /**
     * 流式调用（Qwen-Omni 要求 stream:true）
     */
    private String doStreamingCall(String model, List<Map<String, Object>> messages, String logLabel) {
        long start = System.currentTimeMillis();
        String uuid = UUID.randomUUID().toString();
        String label = logLabel != null ? logLabel : "streamingChat";
        log.info("start {} request, uuid:{}, model:{}, time:{}", label, uuid, model, start);

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));
            body.put("modalities", List.of("text"));
            applyThinkingMode(body);

            String jsonBody = objectMapper.writeValueAsString(body);
            log.info("{} uuid:{}, model:{}, body length:{}", label, uuid, model, jsonBody.length());

            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            StringBuilder contentBuilder = new StringBuilder();
            int[] tokens = {0, 0, 0};

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = readBoundedResponseBody(response.body());
                    log.error("{} uuid:{} failed, status:{}, responseBytes:{}",
                            label, uuid, response.code(), errorBody.length());
                    throw new RuntimeException("API call failed with status " + response.code());
                }

                String line;
                ResponseBody responseBody = requireResponseBody(response.body());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        boundedResponseStream(responseBody.byteStream(), MAX_TEXT_STREAM_RESPONSE_BYTES),
                        StandardCharsets.UTF_8))) {
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if ("[DONE]".equals(data)) {
                                break;
                            }
                            JsonNode chunk = objectMapper.readTree(data);
                            JsonNode delta = chunk.path("choices").path(0).path("delta");
                            String text = delta.path("content").asText("");
                            if (!text.isEmpty()) {
                                contentBuilder.append(text);
                            }

                            JsonNode usage = chunk.path("usage");
                            if (!usage.isMissingNode()) {
                                tokens[0] = usage.path("prompt_tokens").asInt(0);
                                tokens[1] = usage.path("completion_tokens").asInt(0);
                                tokens[2] = usage.path("total_tokens").asInt(0);
                            }
                        }
                    }
                }
            }

            String content = contentBuilder.toString();
            log.info("{} uuid:{}, prompt_tokens:{}, completion_tokens:{}, total_tokens:{}, time:{}",
                    label, uuid, tokens[0], tokens[1], tokens[2],
                    System.currentTimeMillis() - start);

            final int fPromptTokens = tokens[0];
            final int fCompletionTokens = tokens[1];
            final int fTotalTokens = tokens[2];
            persistAuditAsync(buildAuditRecord(label, model, jsonBody.length(),
                    content.length(), 0, fPromptTokens, fCompletionTokens, fTotalTokens,
                    System.currentTimeMillis() - start, false));

            return content;
        } catch (IOException e) {
            log.error("{} uuid:{} error", label, uuid, e);
            throw new RuntimeException(e);
        }
    }

    private OmniAudioResponseDTO doAudioStreamingCall(String model, List<Map<String, Object>> messages,
                                                      String voice, String format, String logLabel) {
        return doAudioStreamingCall(model, messages, voice, format, logLabel, null);
    }

    private OmniAudioResponseDTO doAudioStreamingCall(String model, List<Map<String, Object>> messages,
                                                      String voice, String format, String logLabel,
                                                      Consumer<byte[]> audioChunkConsumer) {
        long start = System.currentTimeMillis();
        String uuid = UUID.randomUUID().toString();
        String label = logLabel != null ? logLabel : "audioStreamingChat";
        log.info("start {} request, uuid:{}, model:{}, time:{}", label, uuid, model, start);

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));
            body.put("modalities", List.of("text", "audio"));
            body.put("audio", Map.of("voice", voice, "format", format));
            applyThinkingMode(body);

            String jsonBody = objectMapper.writeValueAsString(body);
            log.info("{} uuid:{}, model:{}, body length:{}", label, uuid, model, jsonBody.length());

            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = readBoundedResponseBody(response.body());
                    log.error("{} uuid:{} failed, status:{}, responseBytes:{}",
                            label, uuid, response.code(), errorBody.length());
                    throw new RuntimeException("API call failed with status " + response.code());
                }

                StringBuilder textBuilder = new StringBuilder();
                // Streaming callers already consume decoded chunks. Do not also
                // retain the full base64 payload, which would duplicate the
                // provider response and decoded audio in heap.
                StringBuilder audioBuilder = audioChunkConsumer == null ? new StringBuilder() : null;
                StreamingBase64AudioDecoder audioDecoder = audioChunkConsumer != null
                        ? new StreamingBase64AudioDecoder(audioChunkConsumer)
                        : null;
                ResponseBody responseBody = requireResponseBody(response.body());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        boundedResponseStream(responseBody.byteStream(), MAX_AUDIO_STREAM_RESPONSE_BYTES),
                        StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        parseOmniAudioStreamingLine(line, objectMapper, textBuilder, audioBuilder, audioDecoder);
                    }
                }
                if (audioDecoder != null) {
                    audioDecoder.flush();
                }

                OmniAudioResponseDTO result = new OmniAudioResponseDTO();
                result.setText(textBuilder.toString());
                result.setAudioBase64(audioBuilder == null ? "" : audioBuilder.toString());
                int audioBytes = audioDecoder == null
                        ? result.getAudioBytes().length
                        : audioDecoder.getDecodedByteCount();

                log.info("{} uuid:{}, text length:{}, audio bytes:{}, time:{}",
                        label, uuid, result.getText().length(), audioBytes,
                        System.currentTimeMillis() - start);

                persistAuditAsync(buildAuditRecord(label, model, jsonBody.length(),
                        result.getText().length(), audioBytes,
                        0, 0, 0, System.currentTimeMillis() - start, false));
                return result;
            }
        } catch (IOException e) {
            log.error("{} uuid:{} error", label, uuid, e);
            throw new RuntimeException(e);
        }
    }

    private static ResponseBody requireResponseBody(ResponseBody responseBody) throws IOException {
        if (responseBody == null) {
            throw new IOException("AI response body is missing");
        }
        return responseBody;
    }

    static OmniAudioResponseDTO parseOmniAudioStreamingLines(List<String> lines) {
        return parseOmniAudioStreamingLines(lines, null);
    }

    static OmniAudioResponseDTO parseOmniAudioStreamingLines(List<String> lines,
                                                              Consumer<byte[]> audioChunkConsumer) {
        StringBuilder textBuilder = new StringBuilder();
        StringBuilder audioBuilder = audioChunkConsumer == null ? new StringBuilder() : null;
        StreamingBase64AudioDecoder audioDecoder = audioChunkConsumer == null
                ? null
                : new StreamingBase64AudioDecoder(audioChunkConsumer);
        ObjectMapper mapper = new ObjectMapper();

        for (String line : lines) {
            parseOmniAudioStreamingLine(line, mapper, textBuilder, audioBuilder, audioDecoder);
        }
        if (audioDecoder != null) {
            audioDecoder.flush();
        }

        OmniAudioResponseDTO response = new OmniAudioResponseDTO();
        response.setText(textBuilder.toString());
        response.setAudioBase64(audioBuilder == null ? "" : audioBuilder.toString());
        return response;
    }

    private void persistAuditAsync(TokenConsumerPO auditRecord) {
        try {
            threadPoolExecutor.execute(() -> {
                try {
                    tokenConsumerMapper.insert(auditRecord);
                } catch (Exception exception) {
                    log.error("token log error", exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            // Usage telemetry is best-effort and must never turn a successful,
            // billable model response into a failed business operation.
            log.warn("token log skipped because audit queue is full");
        }
    }

    private static void parseOmniAudioStreamingLine(String line, ObjectMapper mapper,
                                                    StringBuilder textBuilder,
                                                    StringBuilder audioBuilder,
                                                    StreamingBase64AudioDecoder audioDecoder) {
        if (line == null || !line.startsWith("data: ")) {
            return;
        }
        String data = line.substring(6).trim();
        if (data.isBlank() || "[DONE]".equals(data)) {
            return;
        }
        try {
            JsonNode chunk = mapper.readTree(data);
            JsonNode delta = chunk.path("choices").path(0).path("delta");
            String text = delta.path("content").asText("");
            if (!text.isEmpty()) {
                textBuilder.append(text);
            }
            String audio = delta.path("audio").path("data").asText("");
            if (!audio.isEmpty()) {
                if (audioBuilder != null) {
                    audioBuilder.append(audio);
                }
                if (audioDecoder != null) {
                    audioDecoder.accept(audio);
                }
            }
        } catch (Exception e) {
            log.warn("skip malformed omni streaming line, lineLength:{}, error:{}",
                    line.length(), e.getClass().getSimpleName());
        }
    }

    static TokenConsumerPO buildAuditRecord(String method, String model, int requestChars,
                                            int outputChars, int audioBytes,
                                            int promptTokens, int completionTokens, int totalTokens,
                                            long costTime, boolean structured) {
        return TokenConsumerPO.builder()
                .method(method)
                .param("model=" + safeModelName(model)
                        + ",requestChars=" + Math.max(requestChars, 0)
                        + ",structured=" + structured)
                .result("outputChars=" + Math.max(outputChars, 0)
                        + ",audioBytes=" + Math.max(audioBytes, 0))
                .promptTokenCount(Math.max(promptTokens, 0))
                .candidatesTokenCount(Math.max(completionTokens, 0))
                .totalTokenCount(Math.max(totalTokens, 0))
                .cachedContentTokenCount(0)
                .costTime(Math.max(costTime, 0))
                .createTime(LocalDateTime.now())
                .build();
    }

    private static String safeModelName(String model) {
        if (model == null || !model.matches("[A-Za-z0-9._-]{1,100}")) {
            return "unknown";
        }
        return model;
    }

    private static class StreamingBase64AudioDecoder {

        private final Consumer<byte[]> audioChunkConsumer;

        private final StringBuilder pending = new StringBuilder();

        private int decodedByteCount;

        StreamingBase64AudioDecoder(Consumer<byte[]> audioChunkConsumer) {
            this.audioChunkConsumer = audioChunkConsumer;
        }

        void accept(String base64Chunk) {
            if (base64Chunk == null || base64Chunk.isBlank()) {
                return;
            }
            pending.append(base64Chunk.replaceAll("\\s+", ""));
            int paddingIndex = pending.indexOf("=");
            int decodeLength = paddingIndex >= 0
                    ? pending.length()
                    : pending.length() - pending.length() % 4;
            decodeAvailableBytes(decodeLength);
        }

        void flush() {
            int decodeLength = pending.length();
            int remainder = decodeLength % 4;
            if (remainder != 0) {
                pending.append("=".repeat(4 - remainder));
                decodeLength = pending.length();
            }
            decodeAvailableBytes(decodeLength);
        }

        private void decodeAvailableBytes(int decodeLength) {
            if (decodeLength <= 0) {
                return;
            }
            String chunk = pending.substring(0, decodeLength);
            byte[] decodedBytes = Base64.getDecoder().decode(chunk);
            if (decodedBytes.length > 0) {
                audioChunkConsumer.accept(decodedBytes);
                decodedByteCount += decodedBytes.length;
            }
            pending.delete(0, decodeLength);
        }

        int getDecodedByteCount() {
            return decodedByteCount;
        }
    }

    /**
     * 文件输入 DTO
     */
    public record FileInput(byte[] data, String mimeType) {}
}
