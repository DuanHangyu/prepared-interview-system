package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 21:13 2025/8/7
 */
@Data
@Schema(description = "老师修改命令")
public class TeacherModifyCmd {

    @Schema(description = "老师id")
    @NotNull(message = "学生id不能为空")
    @Positive(message = "老师id必须为正整数")
    private Integer id;

    @Schema(description = "姓名")
    @NotBlank(message = "姓名不能为空")
    @Size(max = 255, message = "姓名不能超过255个字符")
    private String name;

    @Schema(description = "账号")
    @NotBlank(message = "账号不能为空")
    @Size(max = 255, message = "账号不能超过255个字符")
    private String account;

    @Schema(description = "新密码；不修改密码时省略")
    @Size(min = 8, max = 72, message = "密码长度必须在8到72个字符之间")
    private String password;

    @Schema(description = "手机号")
    @NotBlank(message = "手机号不能为空")
    @Size(max = 32, message = "手机号不能超过32个字符")
    private String phone;
}
