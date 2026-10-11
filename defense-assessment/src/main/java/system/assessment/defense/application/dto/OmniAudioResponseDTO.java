package system.assessment.defense.application.dto;

import lombok.Data;

import java.util.Base64;

@Data
public class OmniAudioResponseDTO {

    private String text = "";

    private String audioBase64 = "";

    public byte[] getAudioBytes() {
        if (audioBase64 == null || audioBase64.isBlank()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(audioBase64);
    }
}
