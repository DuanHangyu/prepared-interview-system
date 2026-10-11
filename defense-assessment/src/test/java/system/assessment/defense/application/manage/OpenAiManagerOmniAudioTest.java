package system.assessment.defense.application.manage;

import org.junit.jupiter.api.Test;
import system.assessment.defense.application.dto.OmniAudioResponseDTO;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiManagerOmniAudioTest {

    @Test
    void parseTextAndAudioFromStreamingChunks() {
        OmniAudioResponseDTO response = OpenAiManager.parseOmniAudioStreamingLines(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"QUJD\"}}}]}",
                "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"RA==\"}}}]}",
                "data: {\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{\"content\":\"\"}}]}",
                "data: [DONE]"
        ));

        assertThat(response.getText()).isEqualTo("你好");
        assertThat(response.getAudioBase64()).isEqualTo("QUJDRA==");
        assertThat(response.getAudioBytes()).containsExactly('A', 'B', 'C', 'D');
    }

    @Test
    void streamsDecodedAudioWithoutRetainingTheFullBase64Payload() {
        ByteArrayOutputStream streamedAudio = new ByteArrayOutputStream();

        OmniAudioResponseDTO response = OpenAiManager.parseOmniAudioStreamingLines(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"题目\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"QUJD\"}}}]}",
                "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"RA==\"}}}]}",
                "data: [DONE]"
        ), streamedAudio::writeBytes);

        assertThat(response.getText()).isEqualTo("题目");
        assertThat(response.getAudioBase64()).isEmpty();
        assertThat(streamedAudio.toByteArray()).containsExactly('A', 'B', 'C', 'D');
    }
}
