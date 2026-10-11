package system.assessment.defense.application.dto.backed;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 15:37 2025/9/21
 */
@Data
@Schema(description = "解除预约信息")
public class ReleaseAppointmentCmd {

    @Schema(description = "记录id")
    private Integer recordId;
}
