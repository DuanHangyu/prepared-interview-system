package system.assessment.defense.application.dto.backed;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @USER taoHouChao
 * @DATE 17:15 2025/8/19
 */
@Data
@Schema(description = "学生记录修改得分")
public class StudentRecordChangeScoreCmd {

    @Schema(description = "学生记录id")
    @NotNull(message = "学生记录id不能为空")
    @Positive(message = "学生记录id必须为正整数")
    private Integer id;

    @Schema(description = "教师核分")
    @NotNull(message = "教师核分不能为空")
    @DecimalMin(value = "0", message = "教师核分不能小于0")
    private BigDecimal checkScore;
}
