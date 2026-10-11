package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 09:43 2025/8/16
 */
@Data
@Schema(description = "考核信息")
public class AssessmentSimpleDTO {

    @Schema(description = "考核ID")
    private Integer id;

    @Schema(description = "考核主题")
    @NotEmpty(message = "考核主题不能为空")
    private String theme;

    @Schema(description = "考核时长")
    @NotNull(message = "考核时长不能为空")
    private Integer duration;

    @Schema(description = "考核题目数量")
    @NotNull(message = "考核题目数量不能为空")
    private Integer questionCount;

    @Schema(description = "考核答题时间")
    @NotNull(message = "考核答题时间不能为空")
    private Integer answerTime;

    @Schema(description = "是否答辩")
    private Boolean defense;

    @Schema(description = "是否提问")
    private Boolean question;

    @Schema(description = "是否完成答辩")
    private Boolean finishDefense;

}
