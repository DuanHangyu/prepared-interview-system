package system.assessment.defense.infrastructure.repository.dao.po;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import system.assessment.defense.application.dto.AssessmentEvaluationDTO;
import system.assessment.defense.application.dto.ValueDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 20:10 2025/8/14
 */
@Data
@TableName("student_assessment_record")
@Schema(description = "学生考核记录")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Slf4j
public class StudentAssessmentRecordPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "考核ID")
    private Integer assessmentId;

    @Schema(description = "学生ID")
    private Integer studentId;

    @Schema(description = "答辩材料")
    private String defenseFile;

    @Schema(description = "答辩内容")
    private String defenseContent;

    @Schema(description = "答辩语音")
    private String defenseVoice;

    @Schema(description = "得分")
    private Integer score;

    @Schema(description = "教师核分")
    private Integer checkScore;

    @Schema(description = "答辩时间")
    private Integer defenseTime;

    @Schema(description = "开始答辩时间")
    private LocalDateTime startDefenseTime;

    @Schema(description = "结束答辩时间")
    private LocalDateTime endDefenseTime;

    @Schema(description = "现场问答阶段首次开始时间")
    private LocalDateTime questionStartedAt;

    @Schema(description = "答辩结果")
    private String defenseResult;

    @Schema(description = "状态 0未完成 1已完成 2分析中 3重考")
    private Integer state;

    @Schema(description = "是否重新分析")
    private Boolean reAnalysis;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    public Integer getScore() {
        if (score == null) {
            return 0;
        }
        if (score > 0) {
            return score;
        }
        if (StringUtils.isBlank(defenseResult)) {
            return 0;
        }
        try {
            AssessmentEvaluationDTO evaluation = JSONUtil.toBean(defenseResult, AssessmentEvaluationDTO.class);
            List<ValueDTO> value = evaluation.getValue();
            return value.stream().mapToInt(ValueDTO::getValue).sum();
        } catch (Exception e) {
            log.error("获取学生得分失败", e);
            return 0;
        }
    }

    public Integer frontScore(){
        if (checkScore != null) {
            return checkScore;
        }
        return getScore();
    }

    public String assessmentIdAndStudentId(){
        return assessmentId + "_" + studentId;
    }
}
