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
 * @DATE 19:06 2025/9/20
 */
@Data
@Schema(description = "考核邀请")
@TableName("assessment_invite")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AssessmentInvitePO {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "考核id")
    private Integer assessmentId;

    @Schema(description = "被邀请教师id")
    private Integer inviteeTeacherId;

    @Schema(description = "邀请教师id")
    private Integer InviterTeacherId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
