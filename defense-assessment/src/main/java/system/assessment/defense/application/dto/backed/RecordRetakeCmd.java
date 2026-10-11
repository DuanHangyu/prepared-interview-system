package system.assessment.defense.application.dto.backed;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 14:20 2025/10/20
 */
@Data
@Schema(description = "重新考试命令")
public class RecordRetakeCmd {

    @Schema(description = "考试记录id")
    @NotNull(message = "考试记录id不能为空")
    @Positive(message = "考试记录id必须为正整数")
    private Integer recordId;
}
