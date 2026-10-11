package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 21:05 2025/8/7
 */
@Data
@Schema(description = "老师创建命令")
public class TeacherCreateCmd {

    @Schema(description = "姓名")
    @NotBlank(message = "姓名不能为空")
    @Size(max = 255, message = "姓名不能超过255个字符")
    private String name;

    @Schema(description = "账号")
    @NotBlank(message = "账号不能为空")
    @Size(max = 255, message = "账号不能超过255个字符")
    private String account;

    @Schema(description = "密码")
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度必须在8到72个字符之间")
    private String password;

    @Schema(description = "手机号")
    @NotBlank(message = "手机号不能为空")
    @Size(max = 32, message = "手机号不能超过32个字符")
    private String phone;
}
