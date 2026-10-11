package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 16:22 2025/8/19
 */
@Data
@Schema(description = "折线图")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class LineChartDTO {

    @Schema(description = "x轴")
    private List<String> xAxis;

    @Schema(description = "y轴")
    private List<SeriesDTO> series;

    public static LineChartDTO empty(){
        return LineChartDTO.builder()
                .xAxis(List.of())
                .series(List.of())
                .build();
    }
}
