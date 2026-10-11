package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 20:44 2025/8/22
 */
@Data
@Schema(description = "生成de问题")
public class GeneratedQuestionDTO {

    private Integer id;

    @Schema(description = "是否是追问")
    private Boolean follow;

    @Schema(description = "问题")
    private String question;

    @Schema(description = "问题语音")
    private String questionVoice;
}
