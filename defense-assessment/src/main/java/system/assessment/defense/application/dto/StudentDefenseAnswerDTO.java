package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 19:45 2025/8/15
 */
@Data
@Schema(description = "学生答辩答案")
public class StudentDefenseAnswerDTO {

    @Schema(description = "考核id")
    private Integer assessmentId;

    @Schema(description = "问题")
    private String question;

    @Schema(description = "答案")
    private String answer;
}
