package system.assessment.defense.application.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 15:53 2025/8/14
 */
@Data
@Schema(description = "修改密码参数")
public class ChangePasswordDTO {

    @NotBlank(message = "当前密码不能为空")
    @Size(max = 255, message = "当前密码不能超过255个字符")
    @JsonAlias("oldPassword")
    @Schema(description = "当前密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String currentPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 72, message = "新密码长度必须在8到72个字符之间")
    @Schema(description = "新密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String newPassword;
}
