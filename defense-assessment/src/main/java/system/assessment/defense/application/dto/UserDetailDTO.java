package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @USER taoHouChao
 * @DATE 15:12 2025/8/13
 */
@Data
@Schema(description = "用户详情")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserDetailDTO {

    @Schema(description = "用户id")
    private Integer id;

    @Schema(description = "用户名")
    private String name;

    @Schema(description = "用户账号")
    private String account;

    @Schema(description = "用户角色")
    private String role;
}
