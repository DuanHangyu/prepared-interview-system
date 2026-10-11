package system.assessment.defense.infrastructure.contract;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AudioTurnProtocolContractTest {

    private static final Path REPOSITORY_ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void activeInterviewPagesWaitForVoiceFlushAckInsteadOfUsingATimer() throws Exception {
        String questions = file("defense-system/src/views/studentDashboard/questions/index2.vue");
        String defense = file("defense-system/src/views/studentDashboard/defense/index2.vue");
        String voiceSocket = file("defense-system/src/composables/useSpeechRecognition.js");

        assertThat(questions)
                .contains("finishAudioTurn")
                .contains("sendAssessmentAction(\"end-answer\", currentAnswerTurnId)");
        assertThat(defense)
                .contains("finishAudioTurn")
                .contains("sendAssessmentAction(\"end-defense\", currentDefenseTurnId)")
                .doesNotContain("setTimeout(resolve, 200)");
        assertThat(voiceSocket)
                .contains("audio-start")
                .contains("audio-flush")
                .contains("audio-flush-ack");
    }

    private String file(String relativePath) throws Exception {
        return Files.readString(REPOSITORY_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }
}
