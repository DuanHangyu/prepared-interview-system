package system.assessment.defense.application.manage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.infrastructure.repository.dao.mapper.TokenConsumerMapper;
import system.assessment.defense.infrastructure.repository.dao.po.TokenConsumerPO;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OpenAiManagerStructuredOutputTest {

    @Test
    void auditRecordContainsOnlyMetricsAndNeverPromptOrModelOutput() {
        TokenConsumerPO record = OpenAiManager.buildAuditRecord(
                "structured-score", "qwen3.7-plus", 1234, 321, 0,
                100, 20, 120, 250L, true);

        assertThat(record.getParam())
                .contains("model=qwen3.7-plus")
                .contains("requestChars=1234")
                .contains("structured=true")
                .doesNotContain("prompt", "student", "answer");
        assertThat(record.getResult())
                .contains("outputChars=321")
                .contains("audioBytes=0")
                .doesNotContain("model-original-output");
        assertThat(record.getCreateTime()).isNotNull();
    }

    @Test
    void buildsStrictJsonSchemaResponseFormatInsteadOfPlainJsonObject() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String schema = """
                {
                  "type":"object",
                  "required":["question"],
                  "additionalProperties":false,
                  "properties":{"question":{"type":"string"}}
                }
                """;

        Map<String, Object> responseFormat = OpenAiManager.buildStructuredResponseFormat(
                objectMapper, "live_questions", schema);

        assertThat(responseFormat.get("type")).isEqualTo("json_schema");
        JsonNode jsonSchema = objectMapper.valueToTree(responseFormat.get("json_schema"));
        assertThat(jsonSchema.path("name").asText()).isEqualTo("live_questions");
        assertThat(jsonSchema.path("strict").asBoolean()).isTrue();
        assertThat(jsonSchema.path("schema").path("additionalProperties").asBoolean()).isFalse();
        assertThat(jsonSchema.path("schema").path("required").get(0).asText()).isEqualTo("question");
    }

    @Test
    void rejectsOversizedModelResponsesBeforeParsingThem() {
        ResponseBody body = ResponseBody.create(
                "x".repeat(2 * 1024 * 1024 + 1),
                MediaType.parse("application/json"));

        assertThatThrownBy(() -> OpenAiManager.readBoundedResponseBody(body))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void rejectsStreamingResponsesThatCrossTheTransportLimit() throws Exception {
        byte[] response = "123456789".getBytes(StandardCharsets.UTF_8);

        try (var bounded = OpenAiManager.boundedResponseStream(
                new ByteArrayInputStream(response), 8)) {
            assertThatThrownBy(bounded::readAllBytes)
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("size limit");
        }

        try (var bounded = OpenAiManager.boundedResponseStream(
                new ByteArrayInputStream(response), response.length)) {
            assertThat(bounded.readAllBytes()).isEqualTo(response);
        }
    }

    @Test
    void abortsAnOversizedTextStreamBeforeParsingTheSsePayload() {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(
                                "x".repeat(4 * 1024 * 1024 + 1),
                                MediaType.parse("text/event-stream")))
                        .build())
                .build();
        OpenAiManager manager = new OpenAiManager(
                httpClient,
                new ObjectMapper(),
                mock(TokenConsumerMapper.class),
                mock(ThreadPoolExecutor.class));
        ReflectionTestUtils.setField(manager, "baseUrl", "http://model.test/v1");
        ReflectionTestUtils.setField(manager, "apiKey", "test-key");

        assertThatThrownBy(() -> manager.chatCompletionWithAudio(
                "qwen-omni", "system", new byte[]{1}, "wav", "transcribe"))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(java.io.IOException.class)
                .hasStackTraceContaining("size limit");
    }

    @Test
    void appliesPerCallTimeoutToInteractiveStructuredRequests() {
        AtomicLong timeoutMillis = new AtomicLong();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    timeoutMillis.set(chain.call().timeout().timeoutNanos() / 1_000_000);
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(
                                    "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}",
                                    MediaType.parse("application/json")))
                            .build();
                })
                .callTimeout(10, TimeUnit.MINUTES)
                .build();
        OpenAiManager manager = new OpenAiManager(
                httpClient,
                new ObjectMapper(),
                mock(TokenConsumerMapper.class),
                mock(ThreadPoolExecutor.class));
        ReflectionTestUtils.setField(manager, "baseUrl", "http://model.test/v1");
        ReflectionTestUtils.setField(manager, "apiKey", "test-key");

        manager.chatCompletionWithSchema(
                "qwen", "system", "user", "result", "{\"type\":\"object\"}", 1_234L);

        assertThat(timeoutMillis.get()).isEqualTo(1_234L);
    }
}
