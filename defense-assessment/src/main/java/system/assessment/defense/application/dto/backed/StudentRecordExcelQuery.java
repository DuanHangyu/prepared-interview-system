package system.assessment.defense.application.dto.backed;

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
 * @DATE 22:36 2025/9/26
 */
@Data
@Schema(description = "学生记录查询参数")
public class StudentRecordExcelQuery {

    @Schema(description = "考核主题")
    @Size(max = 500, message = "考核主题不能超过500个字符")
    private String theme;

    @Schema(description = "考核记录ID列表")
    @Size(max = 1000, message = "单次最多导出1000条考核记录")
    private List<@NotNull(message = "考核记录ID不能为空") @Positive(message = "考核记录ID必须为正整数") Integer> recordIds;

    @JsonIgnore
    @AssertTrue(message = "请提供考核主题或记录ID")
    public boolean isSelectionValid() {
        return (theme != null && !theme.isBlank()) || (recordIds != null && !recordIds.isEmpty());
    }
}
