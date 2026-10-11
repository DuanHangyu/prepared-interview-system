package system.assessment.defense.application.dto.backed;

import cn.hutool.core.annotation.Alias;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @USER taoHouChao
 * @DATE 22:25 2025/9/26
 */
@Data
@Schema(description = "学生记录")
public class StudentRecordExcelDTO {

    @Schema(description = "学生姓名")
    @Alias("学生姓名")
    private String studentName;

    @Schema(description = "学号")
    @Alias("学号")
    private String idNumber;

    @Schema(description = "考核主题")
    @Alias("考核主题")
    private String theme;

    @Schema(description = "总分")
    @Alias("总分")
    private Integer totalScore;

    @Schema(description = "通过分数")
    @Alias("通过分数")
    private Integer passScore;

    @Schema(description = "学生得分")
    @Alias("学生得分")
    private Integer score;

    @Schema(description = "教师核分")
    @Alias("教师核分")
    private Integer checkScore;

    @Schema(description = "答辩回答")
    @Alias("答辩回答")
    private String defenseAnswer;

    @Schema(description = "问题回答")
    @Alias("问题回答")
    private String questionAnswer;

    @Schema(description = "分析结果")
    @Alias("分析结果")
    private String analysis;

    @Schema(description = "建议")
    @Alias("建议")
    private String suggestions;

    @Schema(description = "优势")
    @Alias("优势")
    private String strengths;

    @Schema(description = "弱点")
    @Alias("弱点")
    private String weaknesses;

    @Schema(description = "考核时间")
    @Alias("考核时间")
    @JsonFormat(pattern = "yyyy/MM/dd HH:mm", timezone = "GMT+8")
    private LocalDateTime assessTime;
}
