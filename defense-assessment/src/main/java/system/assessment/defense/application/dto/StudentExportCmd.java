package system.assessment.defense.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 14:36 2025/8/9
 */
@Data
@Schema(description = "学生导出命令")
public class StudentExportCmd {

    @Schema(description = "学生id集合")
    @Size(max = 1000, message = "单次最多导出1000名学生")
    private List<@NotNull(message = "学生id不能为空") @Positive(message = "学生id必须为正整数") Integer> ids;

    @Schema(description = "导出所有")
    private Boolean all;

    @JsonIgnore
    @AssertTrue(message = "请选择要导出的学生")
    public boolean isSelectionValid() {
        return Boolean.TRUE.equals(all) || (ids != null && !ids.isEmpty());
    }
}
