package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 18:49 2025/8/12
 */
@Data
@Schema(description = "考核设置删除命令")
public class AssessmentSettingsRemoveCmd {

    @Schema(description = "考核ID")
    @NotNull(message = "考核ID不能为空")
    @Positive(message = "考核ID必须为正整数")
    private Integer id;
}
