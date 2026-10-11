package system.assessment.defense.application.dto.backed;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentAppointmentSettingPO;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @USER taoHouChao
 * @DATE 20:38 2025/9/23
 */
@Data
@Schema(description = "考核预约命令")
public class AppointmentSettingCreateCmd {

    @Schema(description = "考核设置id")
    @NotNull(message = "考核设置id不能为空")
    @Positive(message = "考核设置id必须为正整数")
    private Integer assessmentId;

    @Schema(description = "可以预约时间段")
    @NotEmpty(message = "预约时间段不能为空")
    @Size(max = 200, message = "单个考核最多设置200个预约时间段")
    private List<@Valid @NotNull(message = "预约时间段不能为空") TimePeriod> timePeriods;

    @Schema(description = "考核失败是否惩罚")
    @NotNull(message = "考核失败是否惩罚不能为空")
    private Boolean assessmentFailPunish;

    @Schema(description = "可重新预约时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime rescheduleAppointTime;

    public List<AssessmentAppointmentSettingPO> toAppointmentSettingPOList() {
        return timePeriods.stream().map(timePeriod -> AssessmentAppointmentSettingPO.builder()
                .id(timePeriod.getId())
                .assessmentId(assessmentId)
                .timePeriod(timePeriod.getTimePeriod())
                .participantLimit(timePeriod.getParticipantLimit())
                .location(timePeriod.getLocation())
                .build()).collect(Collectors.toList());
    }

    public LocalDateTime lastTimePeriod() {
        return timePeriods.stream().max(Comparator.comparing(TimePeriod::getTimePeriod)).map(TimePeriod::getTimePeriod).orElse(null);
    }

    @Data
    @Schema(description = "考核时间段")
    public static class TimePeriod{

        @Schema(description = "时间段id")
        @Positive(message = "时间段id必须为正整数")
        private Integer id;

        @Schema(description = "时间段")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
        @NotNull(message = "预约时间不能为空")
        private LocalDateTime timePeriod;

        @Schema(description = "该时间段内的限制人数")
        @NotNull(message = "预约人数限制不能为空")
        @Positive(message = "预约人数限制必须大于0")
        private Integer participantLimit;

        @Schema(description = "地点")
        @NotBlank(message = "预约地点不能为空")
        @Size(max = 255, message = "预约地点不能超过255个字符")
        private String location;
    }
}
