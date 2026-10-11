package system.assessment.defense.application.manage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeSpeechEventFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RealtimeSpeechEventFactory factory = new RealtimeSpeechEventFactory(objectMapper);

    @Test
    void buildsManualSessionUpdateForInterviewTranscription() throws Exception {
        String json = factory.sessionUpdate("Ethan", "请只转写学生回答。");

        JsonNode root = objectMapper.readTree(json);

        assertThat(root.path("type").asText()).isEqualTo("session.update");
        assertThat(root.path("session").path("modalities").get(0).asText()).isEqualTo("text");
        assertThat(root.path("session").path("input_audio_format").asText()).isEqualTo("pcm");
        assertThat(root.path("session").path("output_audio_format").asText()).isEqualTo("pcm");
        assertThat(root.path("session").path("voice").asText()).isEqualTo("Ethan");
        assertThat(root.path("session").path("turn_detection").isNull()).isTrue();
        assertThat(root.path("session").path("input_audio_transcription").path("model").asText())
                .isEqualTo("qwen3-asr-flash-realtime");
        assertThat(root.path("session").path("instructions").asText()).contains("学生回答");
    }

    @Test
    void buildsAppendAndCommitEvents() throws Exception {
        JsonNode append = objectMapper.readTree(factory.inputAudioAppend(new byte[]{1, 2, 3}));
        JsonNode commit = objectMapper.readTree(factory.inputAudioCommit());

        assertThat(append.path("type").asText()).isEqualTo("input_audio_buffer.append");
        assertThat(append.path("audio").asText()).isEqualTo("AQID");
        assertThat(commit.path("type").asText()).isEqualTo("input_audio_buffer.commit");
    }

    @Test
    void extractsCompletedTranscriptFromServerEvent() {
        Optional<String> transcript = factory.extractCompletedTranscript("""
                {"type":"conversation.item.input_audio_transcription.completed","transcript":"学生回答文本"}
                """);

        assertThat(transcript).contains("学生回答文本");
    }

    @Test
    void buildsAudioOutputSessionUpdateForQuestionVoice() throws Exception {
        String json = factory.audioOutputSessionUpdate("Cherry", "请朗读题目。");

        JsonNode root = objectMapper.readTree(json);

        assertThat(root.path("type").asText()).isEqualTo("session.update");
        assertThat(root.path("session").path("modalities").get(0).asText()).isEqualTo("text");
        assertThat(root.path("session").path("modalities").get(1).asText()).isEqualTo("audio");
        assertThat(root.path("session").path("voice").asText()).isEqualTo("Cherry");
        assertThat(root.path("session").path("output_audio_format").asText()).isEqualTo("pcm");
        assertThat(root.path("session").path("turn_detection").isNull()).isTrue();
    }

    @Test
    void buildsResponseCreateForQuestionVoice() throws Exception {
        String json = factory.responseCreateForAudio("请朗读：什么是缓存？");

        JsonNode root = objectMapper.readTree(json);

        assertThat(root.path("type").asText()).isEqualTo("response.create");
        assertThat(root.path("response").path("modalities").get(0).asText()).isEqualTo("text");
        assertThat(root.path("response").path("modalities").get(1).asText()).isEqualTo("audio");
        assertThat(root.path("response").path("instructions").asText()).contains("什么是缓存");
    }

    @Test
    void buildsRealtimeInterviewSessionUpdateForAudioResponse() throws Exception {
        String json = factory.realtimeInterviewSessionUpdate("Ethan", "你是AI面试官。");

        JsonNode root = objectMapper.readTree(json);

        assertThat(root.path("type").asText()).isEqualTo("session.update");
        assertThat(root.path("session").path("modalities").get(0).asText()).isEqualTo("text");
        assertThat(root.path("session").path("modalities").get(1).asText()).isEqualTo("audio");
        assertThat(root.path("session").path("voice").asText()).isEqualTo("Ethan");
        assertThat(root.path("session").path("turn_detection").isNull()).isTrue();
        assertThat(root.path("session").path("input_audio_transcription").path("model").asText())
                .isEqualTo("qwen3-asr-flash-realtime");
        assertThat(root.path("session").path("instructions").asText()).contains("AI面试官");
    }

    @Test
    void extractsRealtimeResponseEvents() {
        assertThat(factory.extractResponseAudioDelta("""
                {"type":"response.audio.delta","delta":"AQID"}
                """)).contains(new byte[]{1, 2, 3});

        assertThat(factory.extractResponseTranscriptDone("""
                {"type":"response.audio_transcript.done","transcript":"请补充说明你的依据。"}
                """)).contains("请补充说明你的依据。");

        assertThat(factory.isResponseDone("""
                {"type":"response.done"}
                """)).isTrue();
    }
}
