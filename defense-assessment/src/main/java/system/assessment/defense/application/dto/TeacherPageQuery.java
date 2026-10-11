package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 06:48 2025/8/14
 */
@Data
@Schema(description = "老师分页查询参数")
public class TeacherPageQuery {

    @Schema(description = "老师姓名")
    @Size(max = 255, message = "老师姓名不能超过255个字符")
    private String name;

    @Schema(description = "手机号")
    @Size(max = 32, message = "手机号不能超过32个字符")
    private String phone;

    @Schema(description = "账号")
    @Size(max = 255, message = "账号不能超过255个字符")
    private String account;

    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码必须大于等于1")
    private Integer page = 1;

    @NotNull(message = "每页数量不能为空")
    @Min(value = 1, message = "每页数量必须大于等于1")
    @Max(value = 100, message = "每页数量不能超过100")
    private Integer size = 20;
}
