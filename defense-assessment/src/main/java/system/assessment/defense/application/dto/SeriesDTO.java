package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 22:04 2025/8/19
 */
@Data
@Schema(description = "系列")
public class SeriesDTO {

    @Schema(description = "名称")
    private String name;

    @Schema(description = "数据")
    private List<Integer> data;
}
