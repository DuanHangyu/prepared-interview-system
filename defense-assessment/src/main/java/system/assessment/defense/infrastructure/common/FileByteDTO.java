package system.assessment.defense.infrastructure.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * @USER taoHouChao
 * @DATE 08:24 2025/8/15
 */
@Data
@Schema(description = "文件字节流")
@AllArgsConstructor
@NoArgsConstructor
public class FileByteDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private byte[] bytes;
}
