package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 21:16 2025/8/7
 */
@Data
@Schema(description = "老师删除命令")
public class TeacherRemoveCmd {

    @Schema(description = "老师id")
    @NotNull(message = "老师id不能为空")
    @Positive(message = "老师id必须为正整数")
    private Integer id;
}
