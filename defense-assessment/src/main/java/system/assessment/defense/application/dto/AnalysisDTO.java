package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 21:01 2025/6/16
 */
@Data
@Schema(description = "分析结果")
public class AnalysisDTO {

    @Schema(description = "指标名")
    private String name;

    @Schema(description = "得分")
    private Integer score;

    @Schema(description = "描述")
    private String description;
}
