package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 14:18 2025/8/12
 */
@Data
@Schema(description = "登录参数")
public class LoginDTO {

    @Schema(description = "账号")
    @NotBlank(message = "账号不能为空")
    @Size(max = 255, message = "账号不能超过255个字符")
    private String account;

    @Schema(description = "密码")
    @NotBlank(message = "密码不能为空")
    @Size(max = 255, message = "密码不能超过255个字符")
    private String password;
}
