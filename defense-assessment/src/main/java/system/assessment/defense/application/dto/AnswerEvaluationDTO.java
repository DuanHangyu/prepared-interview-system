package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @USER taoHouChao
 * @DATE 16:19 2025/6/23
 */
@Data
@Schema(description = "答案评估结果")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AnswerEvaluationDTO {

    @Schema(description = "问答id")
    private Integer id;

    @Schema(description = "问题")
    private String question;

    @Schema(description = "答案")
    private String answer;

    @Schema(description = "答案文件")
    private String answerFile;

    @Schema(description = "追问问题")
    private String followQuestion;

    @Schema(description = "追问问题回答")
    private String followAnswer;

    @Schema(description = "追问问题回答文件")
    private String followAnswerFile;
}
