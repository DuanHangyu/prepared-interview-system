package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 20:40 2025/8/17
 */
@Data
@Schema(description = "学习记录分页查询参数")
public class StudyRecordPagingQuery {

    @Schema(description = "学生姓名")
    @Size(max = 255, message = "学生姓名不能超过255个字符")
    private String studentName;

    @Schema(description = "考核主题")
    @Size(max = 500, message = "考核主题不能超过500个字符")
    private String theme;

    @Schema(description = "页码")
    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码必须大于等于1")
    private Integer page = 1;

    @Schema(description = "每页数量")
    @NotNull(message = "每页数量不能为空")
    @Min(value = 1, message = "每页数量必须大于等于1")
    @Max(value = 100, message = "每页数量不能超过100")
    private Integer size = 20;
}
