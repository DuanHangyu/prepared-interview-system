package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 19:59 2025/8/30
 */
@Data
public class AnalysisBO {

    @Schema(description = "分析结果")
    private List<AnalysisDTO> analysis;

    @Schema(description = "建议")
    private List<String> suggestions;

    @Schema(description = "优势")
    private List<String> strengths;

    @Schema(description = "弱点")
    private List<String> weaknesses;
}
