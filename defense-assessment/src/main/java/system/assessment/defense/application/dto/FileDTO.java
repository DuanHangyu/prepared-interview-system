package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 10:33 2025/8/14
 */
@Data
@Schema(description = "考核文件")
public class FileDTO {

    @Schema(description = "预签名地址")
    private String presignedUrl;

    @Schema(description = "文件地址")
    private String fileUrl;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文件大小")
    private String fileSize;

    @Schema(description = "分辨率")
    private String resolution;

    @Schema(description = "材料解析状态")
    private String materialStatus;

    @Schema(description = "材料解析文本")
    private String materialText;

    @Schema(description = "材料解析错误")
    private String materialError;

    @Schema(description = "材料解析时间戳")
    private Long materialParsedAt;
}
