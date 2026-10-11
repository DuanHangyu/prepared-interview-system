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
@Schema(description = "学生删除命令")
public class StudentRemoveCmd {

    @Schema(description = "学生id")
    @NotNull(message = "学生id不能为空")
    @Positive(message = "学生id必须为正整数")
    private Integer id;
}
