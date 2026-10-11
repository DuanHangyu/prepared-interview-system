package system.assessment.defense.application.dto.front;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 18:47 2025/10/19
 */
@Data
@Schema(description = "取消预约命令")
public class StudentCancelAppointmentCmd {

    @Schema(description = "考核id")
    @NotNull(message = "考核id不能为空")
    @Positive(message = "考核id必须为正整数")
    private Integer assessmentId;
}
