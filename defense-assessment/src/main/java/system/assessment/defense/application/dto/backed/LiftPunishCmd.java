package system.assessment.defense.application.dto.backed;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 00:01 2025/9/24
 */
@Data
@Schema(description = "解除惩罚")
public class LiftPunishCmd {

    @Schema(description = "预约id")
    @NotNull(message = "预约id不能为空")
    @Positive(message = "预约id必须为正整数")
    private Integer id;
}
