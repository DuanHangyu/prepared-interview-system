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
 * @DATE 20:48 2025/9/23
 */
@Data
@Schema(description = "考核预约设置")
@TableName("assessment_appointment_setting")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AssessmentAppointmentSettingPO {

    @TableId(type = IdType.AUTO)
    private Integer id;

    @Schema(description = "考核id")
    private Integer assessmentId;

    @Schema(description = "开始时间")
    private LocalDateTime timePeriod;

    @Schema(description = "该时间段内的限制人数")
    private Integer participantLimit;

    @Schema(description = "地点")
    private String location;

}
