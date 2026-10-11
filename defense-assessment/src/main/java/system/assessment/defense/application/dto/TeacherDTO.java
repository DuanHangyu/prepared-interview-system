package system.assessment.defense.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @USER taoHouChao
 * @DATE 14:41 2025/8/9
 */
@Data
@Schema(description = "老师信息")
public class TeacherDTO {

    @Schema(description = "老师id")
    private Integer id;

    @Schema(description = "老师姓名")
    private String name;

    @Schema(description = "老师账号")
    private String account;

    @Schema(description = "老师手机号")
    private String phone;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy/MM/dd HH:mm", timezone = "GMT+8")
    private LocalDateTime createTime;
}
