package system.assessment.defense.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @USER taoHouChao
 * @DATE 14:41 2025/8/9
 */
@Data
@Schema(description = "学生信息")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StudentDTO {

    @Schema(description = "学生id")
    private Integer id;

    @Schema(description = "学生姓名")
    private String name;

    @Schema(description = "学生账号")
    private String account;

    @Schema(description = "班级")
    private String schoolClass;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy/MM/dd HH:mm", timezone = "GMT+8")
    private LocalDateTime createTime;
}
