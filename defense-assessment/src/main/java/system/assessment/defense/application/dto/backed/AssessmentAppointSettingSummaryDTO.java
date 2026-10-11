package system.assessment.defense.application.dto.backed;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 15:23 2025/9/30
 */
@Data
@Schema(description = "考核预约设置汇总信息")
public class AssessmentAppointSettingSummaryDTO {

    @Schema(description = "已预约人数")
    private Integer alreadyAppointCount;

    @Schema(description = "未预约人数")
    private Integer notAppointCount;

    @Schema(description = "预约设置")
    private List<AssessmentAppointSettingDTO> timePeriods;

    @Schema(description = "未预约学生")
    private List<AppointmentStudentDTO> notAppointStudents;

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
    }
}
