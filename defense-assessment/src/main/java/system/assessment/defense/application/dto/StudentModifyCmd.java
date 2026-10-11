package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;

/**
 * @USER taoHouChao
 * @DATE 21:13 2025/8/7
 */
@Data
@Schema(description = "学生修改命令")
public class StudentModifyCmd {

    @Schema(description = "学生id")
    @NotNull(message = "学生id不能为空")
    @Positive(message = "学生id必须为正整数")
    private Integer id;

    @Schema(description = "姓名")
    @NotBlank(message = "姓名不能为空")
    @Size(max = 255, message = "姓名不能超过255个字符")
    private String name;

    @Schema(description = "学号")
    @NotBlank(message = "学号不能为空")
    @Size(max = 255, message = "学号不能超过255个字符")
    private String account;

    @Schema(description = "新密码；不修改密码时省略")
    @Size(min = 8, max = 72, message = "密码长度必须在8到72个字符之间")
    private String password;

    @Schema(description = "班级")
    @NotBlank(message = "班级不能为空")
    @Size(max = 255, message = "班级不能超过255个字符")
    private String schoolClass;

    public UserPO toPO(){
        return UserPO.builder()
                .id(id)
                .name(name)
                .account(account)
                .password(password)
                .type(0)
                .schoolClass(schoolClass)
                .build();
    }
}
