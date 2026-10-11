package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @USER taoHouChao
 * @DATE 13:59 2025/8/17
 */
@Data
@Schema(description = "学习记录")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StudyRecordDTO {

    @Schema(description = "学习记录id")
    private Integer id;

    @Schema(description = "考核id；仅在当前教师有管理权限时返回")
    private Integer assessmentId;

    @Schema(description = "是否存在预置面试尝试；预置记录不得使用旧版整数成绩和操作")
    private boolean prepared;

    @Schema(description = "原有效预置尝试状态；不是成绩或完整提交回执")
    private String preparedState;

    @Schema(description = "学生姓名")
    private String studentName;

    @Schema(description = "考核主题")
    private String theme;

    @Schema(description = "得分")
    private Integer score;

    @Schema(description = "教师核分")
    private Integer checkScore;

    @Schema(description = "总分")
    private Integer totalScore;

    @Schema(description = "考核时间")
    private LocalDateTime assessmentTime;

    @Schema(description = "状态")
    private Integer state;
}
