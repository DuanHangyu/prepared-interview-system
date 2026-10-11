package system.assessment.defense.infrastructure.repository.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @USER taoHouChao
 * @DATE 00:24 2025/9/21
 */
@Data
@Schema(description = "学生考核预约信息")
@TableName("student_assessment_appointment")
public class StudentAssessmentAppointmentPO {

    @Schema(description = "预约id")
    @TableId(type = IdType.AUTO)
    private Integer id;

    @Schema(description = "学生id")
    private Integer studentId;

    @Schema(description = "考核id")
    private Integer assessmentId;

    @Schema(description = "预约时间")
    private LocalDateTime appointmentTime;

    @Schema(description = "预约时间段")
    private LocalDateTime timePeriod;

    @Schema(description = "预约状态 0预约中 1考核中 2已完成 3预约未考 4可重新预约 5过期未预约")
    private Integer state;

    @Schema(description = "惩罚状态  -1未惩罚 0惩罚中 1已解除")
    private Integer punishState;

    @Schema(description = "预约地点")
    @TableField(exist = false)
    private String location;

    public String assessmentIdAndStudentId(){
        return assessmentId + "_" + studentId;
    }
}
