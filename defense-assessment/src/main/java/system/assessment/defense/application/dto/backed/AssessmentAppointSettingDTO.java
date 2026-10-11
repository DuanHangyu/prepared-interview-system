package system.assessment.defense.application.dto.backed;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 23:18 2025/9/23
 */
@Data
@Schema(description = "考核预约设置")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AssessmentAppointSettingDTO {

    @Schema(description = "预约时间段")
    private LocalDateTime timePeriod;

    @Schema(description = "预约地点")
    private String location;

    @Schema(description = "预约学生")
    private List<AppointmentStudentDTO> appointmentStudents;

    @Schema(description = "已考核学生")
    private List<AppointmentStudentDTO> alreadyAssessStudents;

    @Schema(description = "过期未考学生")
    private List<AppointmentStudentDTO> expiredStudents;

    @Data
    @Schema(description = "预约学生")
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class AppointmentStudentDTO{

        @Schema(description = "预约id")
        private Integer id;

        @Schema(description = "学号")
        private String studentName;

        @Schema(description = "班级")
        private String schoolClass;

        @Schema(description = "处罚状态 0处罚中 1已解除惩罚")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Integer punishState;
    }
}
