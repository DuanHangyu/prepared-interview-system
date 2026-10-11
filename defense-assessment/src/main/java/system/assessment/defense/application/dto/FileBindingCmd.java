package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Client-controlled portion of an uploaded file reference.
 *
 * <p>Signed URLs and material parsing fields are server-managed and therefore
 * intentionally absent from this command.</p>
 */
@Data
@Schema(description = "已上传文件绑定参数")
public class FileBindingCmd {

    @Schema(description = "OSS 对象键")
    @NotBlank(message = "文件对象键不能为空")
    @Size(max = 1000, message = "文件对象键不能超过1000个字符")
    private String fileUrl;

    @Schema(description = "原始文件名")
    @Size(max = 255, message = "文件名不能超过255个字符")
    private String fileName;

    @Schema(description = "文件大小")
    @Size(max = 32, message = "文件大小描述不能超过32个字符")
    private String fileSize;
}
