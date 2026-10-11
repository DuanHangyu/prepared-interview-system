package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @USER taoHouChao
 * @DATE 11:24 2025/8/19
 */
@Data
@Schema(description = "饼图")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PieChartDTO {

    @Schema(description = "名称")
    private String name;

    @Schema(description = "值")
    private Integer value;
}
