package system.assessment.defense.application.dto;

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
 * @DATE 20:49 2025/8/14
 */
@Data
@Schema(description = "学生答辩简单信息")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StudentTodoDefenseDTO {

    @Schema(description = "待考核ID")
    private Integer id;

    @Schema(description = "考核主题")
    private String theme;

    @Schema(description = "考核总分")
    private Integer score;

    @Schema(description = "答辩时长")
    private Integer duration;

    @Schema(description = "考核题目数量")
    private Integer questionCount;

    @Schema(description = "考核答题时间")
    private Integer answerTime;

    @Schema(description = "是否完成答辩")
    private Boolean finishDefense;

    @Schema(description = "是否答辩")
    private Boolean defense;

    @Schema(description = "是否提问")
    private Boolean question;

    @Schema(description = "预约时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime timePeriod;

    @Schema(description = "地点")
    private String location;

    @Schema(description = "状态 2完成 3过期未考核")
    private Integer state;

    @Schema(description = "可预约时间段")
    private List<CanAppointmentTimeDTO> canAppointmentTimes;

    public Boolean getFinishDefense() {
        return finishDefense != null && finishDefense;
    }

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
