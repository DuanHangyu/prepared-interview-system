package system.assessment.defense.application.dto.backed;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 17:16 2025/9/20
 */
@Data
@Schema(description = "班级学生信息")
public class SchoolClassStudentSaveCmd {

    @Schema(description = "班级名称")
    @NotBlank(message = "班级名称不能为空")
    @Size(max = 255, message = "班级名称不能超过255个字符")
    private String schoolClass;

    @Schema(description = "班级学生信息")
    @Size(max = 1000, message = "单个班级最多选择1000名学生")
    private List<@NotNull(message = "学生id不能为空") @Positive(message = "学生id必须为正整数") Integer> students;
}
