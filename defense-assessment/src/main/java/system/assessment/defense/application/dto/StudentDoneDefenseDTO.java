package system.assessment.defense.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
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
 * @DATE 21:24 2025/8/14
 */
@Data
@Schema(description = "学生已完成答辩信息")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StudentDoneDefenseDTO {

    @Schema(description = "已考核ID")
    private Integer id;

    @Schema(description = "考核主题")
    private String theme;

    @Schema(description = "考核总分")
    private Integer score;

    @Schema(description = "答辩时长")
    private Integer duration;

    @Schema(description = "作答开始时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime assessStartTime;

    @Schema(description = "作答结束时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime assessmentEndTime;

    @Schema(description = "答辩时间")
    private Integer defenseTime;

    @Schema(description = "考核得分")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer defenseScore;

    @Schema(description = "状态 0预约中 1考核中 2完成 3预约未考 4可重新预约 5过期未考")
    private Integer state;

    @Schema(description = "状态标签")
    private String stateTag;

    @Schema(description = "是否失败惩罚")
    private Boolean assessmentFailPunish;

    @Schema(description = "可重新预约时间")
    private LocalDateTime rescheduleAppointTime;

    @Schema(description = "是否显示结果")
    private Boolean showResult;

    @Schema(description = "考核时间段")
    private LocalDateTime timePeriod;

    @Schema(description = "地点")
    private String location;

    @Schema(description = "通过分数")
    private Integer passScore;

    @Schema(description = "可预约时间段")
    private List<CanAppointmentTimeDTO> canAppointmentTimes;

    @Schema(description = "是否预约的考核记录")
    private Boolean appointRecordFlag;

    @Data
    @Schema(description = "可预约时间段")
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class CanAppointmentTimeDTO{

        @Schema(description = "预约时间")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime timePeriod;

        @Schema(description = "是否已满")
        private Boolean full;

        @Schema(description = "地点")
        private String location;

        @Schema(description = "剩余数量")
        private Integer remainCount;
    }
}
