package system.assessment.defense.application.dto.backed;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 10:14 2025/9/24
 */
@Data
@Schema(description = "预约设置")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AppointmentSettingDTO {

    @Schema(description = "可以预约时间段")
    private List<TimePeriod> timePeriods;

    @Schema(description = "考核失败是否惩罚")
    private Boolean assessmentFailPunish;

    @Schema(description = "可重新预约时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime rescheduleAppointTime;

    @Data
    @Schema(description = "考核时间段")
    public static class TimePeriod{

        @Schema(description = "id")
        private Integer id;

        @Schema(description = "时间段")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
        private LocalDateTime timePeriod;

        @Schema(description = "该时间段内的限制人数")
        private Integer participantLimit;

        @Schema(description = "地点")
        private String location;
    }
}
