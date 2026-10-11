package system.assessment.defense.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AudioTurnAckDTO {

    private final String action;
    private final String turnId;
    private final boolean accepted;
    private final String message;

    public static AudioTurnAckDTO start(String turnId, boolean accepted, String message) {
        return new AudioTurnAckDTO("audio-start-ack", turnId, accepted, message);
    }

    public static AudioTurnAckDTO flush(String turnId, boolean accepted, String message) {
        return new AudioTurnAckDTO("audio-flush-ack", turnId, accepted, message);
    }
}
