package system.assessment.defense.application.manage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RealtimeSpeechEventFactory {

    private static final String TRANSCRIPTION_MODEL = "qwen3-asr-flash-realtime";
    private static final String TRANSCRIPTION_COMPLETED = "conversation.item.input_audio_transcription.completed";

    private final ObjectMapper objectMapper;

    public String sessionUpdate(String voice, String instructions) {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("modalities", List.of("text"));
        session.put("voice", StringUtils.defaultIfBlank(voice, "Ethan"));
        session.put("input_audio_format", "pcm");
        session.put("output_audio_format", "pcm");
        session.put("instructions", StringUtils.defaultIfBlank(instructions, "请准确转写学生在面试中的语音回答。"));
        session.put("turn_detection", null);
        session.put("input_audio_transcription", Map.of("model", TRANSCRIPTION_MODEL));

        return toJson(Map.of(
                "event_id", eventId(),
                "type", "session.update",
                "session", session
        ));
    }

    public String realtimeInterviewSessionUpdate(String voice, String instructions) {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("modalities", List.of("text", "audio"));
        session.put("voice", StringUtils.defaultIfBlank(voice, "Ethan"));
        session.put("input_audio_format", "pcm");
        session.put("output_audio_format", "pcm");
        session.put("instructions", StringUtils.defaultIfBlank(instructions, "你是AI面试官，请根据学生回答继续追问。"));
        session.put("turn_detection", null);
        session.put("input_audio_transcription", Map.of("model", TRANSCRIPTION_MODEL));

        return toJson(Map.of(
                "event_id", eventId(),
                "type", "session.update",
                "session", session
        ));
    }

    public String audioOutputSessionUpdate(String voice, String instructions) {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("modalities", List.of("text", "audio"));
        session.put("voice", StringUtils.defaultIfBlank(voice, "Ethan"));
        session.put("input_audio_format", "pcm");
        session.put("output_audio_format", "pcm");
        session.put("instructions", StringUtils.defaultIfBlank(instructions, "请把用户提供的内容原样朗读成语音。"));
        session.put("turn_detection", null);

        return toJson(Map.of(
                "event_id", eventId(),
                "type", "session.update",
                "session", session
        ));
    }

    public String responseCreateForAudio(String instructions) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("modalities", List.of("text", "audio"));
        response.put("instructions", StringUtils.defaultIfBlank(instructions, "请朗读题目文本。"));

        return toJson(Map.of(
                "event_id", eventId(),
                "type", "response.create",
                "response", response
        ));
    }

    public String inputAudioAppend(byte[] audioChunk) {
        return toJson(Map.of(
                "event_id", eventId(),
                "type", "input_audio_buffer.append",
                "audio", Base64.getEncoder().encodeToString(audioChunk)
        ));
    }

    public String inputAudioCommit() {
        return toJson(Map.of(
                "event_id", eventId(),
                "type", "input_audio_buffer.commit"
        ));
    }

    public String inputAudioClear() {
        return toJson(Map.of(
                "event_id", eventId(),
                "type", "input_audio_buffer.clear"
        ));
    }

    public Optional<String> extractCompletedTranscript(String serverEvent) {
        try {
            JsonNode root = objectMapper.readTree(serverEvent);
            if (!Objects.equals(root.path("type").asText(), TRANSCRIPTION_COMPLETED)) {
                return Optional.empty();
            }
            String transcript = root.path("transcript").asText("");
            return StringUtils.isBlank(transcript) ? Optional.empty() : Optional.of(transcript.trim());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<byte[]> extractResponseAudioDelta(String serverEvent) {
        try {
            JsonNode root = objectMapper.readTree(serverEvent);
            if (!Objects.equals(root.path("type").asText(), "response.audio.delta")) {
                return Optional.empty();
            }
            String delta = root.path("delta").asText("");
            return StringUtils.isBlank(delta) ? Optional.empty() : Optional.of(Base64.getDecoder().decode(delta));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<String> extractResponseTranscriptDone(String serverEvent) {
        try {
            JsonNode root = objectMapper.readTree(serverEvent);
            String type = root.path("type").asText("");
            if (Objects.equals(type, "response.audio_transcript.done")) {
                String transcript = root.path("transcript").asText("");
                return StringUtils.isBlank(transcript) ? Optional.empty() : Optional.of(transcript.trim());
            }
            if (Objects.equals(type, "response.text.done")) {
                String text = root.path("text").asText("");
                return StringUtils.isBlank(text) ? Optional.empty() : Optional.of(text.trim());
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public boolean isResponseDone(String serverEvent) {
        try {
            JsonNode root = objectMapper.readTree(serverEvent);
            return Objects.equals(root.path("type").asText(), "response.done");
        } catch (IOException e) {
            return false;
        }
    }

    private String toJson(Map<String, Object> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (IOException e) {
            throw new IllegalStateException("Realtime event serialization failed", e);
        }
    }

    private String eventId() {
        return "event_" + UUID.randomUUID();
    }
}
