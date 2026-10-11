package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @USER taoHouChao
 * @DATE 15:06 2025/8/22
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ValueDTO {

    @Schema(description = "指标名")
    private String name;

    @Schema(description = "指标值")
    private Integer value;
}
