package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 10:54 2025/8/15
 */
@Data
@Schema(description = "学生考核文件上传命令")
public class StudentDefenseFileUploadCmd {

    @Schema(description = "考核ID")
    @NotNull(message = "考核ID不能为空")
    @Positive(message = "考核ID必须为正整数")
    private Integer assessmentId;

    @Schema(description = "文件")
    @NotNull(message = "文件不能为空")
    @Valid
    private FileBindingCmd file;
}
