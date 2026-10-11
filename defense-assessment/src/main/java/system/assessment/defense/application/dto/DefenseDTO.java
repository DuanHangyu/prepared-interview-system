package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 15:06 2025/8/22
 */
@Data
@Schema(description = "考核分析结果")
public class DefenseDTO {

    @Schema(description = "答辩内容")
    private String defenseAnswer;

    @Schema(description = "答辩语音")
    private String defenseAnswerFile;
}
