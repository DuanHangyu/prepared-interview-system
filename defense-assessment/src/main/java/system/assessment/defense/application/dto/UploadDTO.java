package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author taoHouChao
 * @Date 22:03 2025/6/13
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "上传文件返回参数")
public class UploadDTO {

    @Schema(description = "预签名地址")
    private String presignedUrl;

    @Schema(description = "文件地址")
    private String url;

    @Schema(description = "文件名称")
    private String fileName;
}
