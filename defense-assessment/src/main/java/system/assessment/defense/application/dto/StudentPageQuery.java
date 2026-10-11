package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 14:43 2025/8/9
 */
@Data
public class StudentPageQuery {

    @Schema(description = "学生姓名")
    @Size(max = 255, message = "学生姓名不能超过255个字符")
    private String name;

    @Schema(description = "手机号")
    @Size(max = 32, message = "手机号不能超过32个字符")
    private String phone;

    @Schema(description = "账号")
    @Size(max = 255, message = "账号不能超过255个字符")
    private String account;

    @Schema(description = "班级")
    @Size(max = 255, message = "班级不能超过255个字符")
    private String schoolClass;

    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码必须大于等于1")
    private Integer page = 1;

    @NotNull(message = "每页数量不能为空")
    @Min(value = 1, message = "每页数量必须大于等于1")
    @Max(value = 100, message = "每页数量不能超过100")
    private Integer size = 20;
}
