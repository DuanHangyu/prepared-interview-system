package system.assessment.defense.application.dto;

import lombok.Data;

import java.util.Objects;

@Data
public class AudioTurnMessageDTO {

    public static final String START_ACTION = "audio-start";
    public static final String FLUSH_ACTION = "audio-flush";

    private String action;
    private String turnId;

    public boolean start() {
        return Objects.equals(START_ACTION, action);
    }

    public boolean flush() {
        return Objects.equals(FLUSH_ACTION, action);
    }
}
