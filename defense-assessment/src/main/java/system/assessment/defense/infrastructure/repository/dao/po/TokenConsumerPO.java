package system.assessment.defense.infrastructure.repository.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @USER taoHouChao
 * @DATE 22:50 2025/9/18
 */
@Data
@Schema(description = "token消费者")
@TableName("token_consumer")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TokenConsumerPO {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String method;

    private String param;

    private String result;

    private Integer promptTokenCount;

    private Integer candidatesTokenCount;

    private Integer totalTokenCount;

    private Integer cachedContentTokenCount;

    private Long costTime;

    private LocalDateTime createTime;
}
