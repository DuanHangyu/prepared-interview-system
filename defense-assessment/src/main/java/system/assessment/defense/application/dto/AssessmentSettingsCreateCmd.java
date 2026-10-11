package system.assessment.defense.application.dto;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import system.assessment.defense.application.dto.backed.SchoolClassStudentSaveCmd;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;

import java.util.List;
import java.util.Objects;

/**
 * @USER taoHouChao
 * @DATE 19:51 2025/8/7
 */
@Data
@Schema(description = "考核设置创建命令")
public class AssessmentSettingsCreateCmd {

    @Schema(description = "考核主题")
    @NotEmpty(message = "考核主题不能为空")
    @Size(max = 500, message = "考核主题不能超过500个字符")
    private String theme;

    @Schema(description = "考核标准")
    @NotEmpty(message = "考核标准不能为空")
    @Size(max = 12000, message = "考核标准不能超过12000个字符")
    private String assessmentCriteria;

    @Schema(description = "考核时长")
    @Positive(message = "考核时长必须大于0")
    private Integer duration;

    @Schema(description = "考核题目数量")
    @Positive(message = "考核题目数量必须大于0")
    @Max(value = 100, message = "考核题目数量不能超过100")
    private Integer questionCount;

    @Schema(description = "考核答题时间")
    @Positive(message = "考核答题时间必须大于0")
    @Max(value = 3600, message = "单题答题时间不能超过3600秒")
    private Integer answerTime;

    @Schema(description = "是否需要追问")
    @NotNull(message = "是否需要追问不能为空")
    private Boolean followUp;

    @Schema(description = "追问提示词")
    @Size(max = 4000, message = "追问提示词不能超过4000个字符")
    private String followUpPrompt;

    @Schema(description = "追问标准")
    @Size(max = 4000, message = "追问标准不能超过4000个字符")
    private String followUpStandards;

    @Schema(description = "考核文件")
    @Size(max = 10, message = "单个考核最多绑定10个文件")
    private List<@Valid @NotNull(message = "考核文件不能为空") FileBindingCmd> assessmentFiles;

    @Schema(description = "总分")
    @NotNull(message = "总分不能为空")
    @PositiveOrZero(message = "总分不能小于0")
    private Integer totalScore;

    @Schema(description = "通过分数")
    @NotNull(message = "通过分数不能为空")
    @PositiveOrZero(message = "通过分数不能小于0")
    private Integer passScore;

    @Schema(description = "考核要求")
    @Size(max = 8000, message = "考核要求不能超过8000个字符")
    private String assessmentRequirements;

    @Schema(description = "是否答辩")
    @NotNull(message = "是否答辩不能为空")
    private Boolean defense;

    @Schema(description = "是否提问")
    @NotNull(message = "是否提问不能为空")
    private Boolean question;

    @Schema(description = "是否显示结果")
    @NotNull(message = "是否显示结果不能为空")
    private Boolean showResult;

    @Schema(description = "是否需要预约")
    @NotNull(message = "是否需要预约不能为空")
    private Boolean needAppoint;

    @Schema(description = "邀请教师id集合")
    @Size(max = 100, message = "单个考核最多邀请100名教师")
    private List<@NotNull(message = "邀请教师id不能为空") @Positive(message = "邀请教师id必须为正整数") Integer> inviteTeachers;

    @Schema(description = "班级名称集合")
    @Size(max = 100, message = "单个考核最多选择100个班级")
    private List<@Valid @NotNull(message = "班级学生信息不能为空") SchoolClassStudentSaveCmd> schoolClassList;

    @Schema(description = "学生id集合")
    @Size(max = 1000, message = "单个考核最多直接选择1000名学生")
    private List<@NotNull(message = "学生id不能为空") @Positive(message = "学生id必须为正整数") Integer> studentIds;

    @JsonIgnore
    @AssertTrue(message = "答辩和提问至少需要启用一项")
    public boolean isAssessmentModeValid() {
        return Boolean.TRUE.equals(defense) || Boolean.TRUE.equals(question);
    }

    @JsonIgnore
    @AssertTrue(message = "启用答辩时考核时长不能为空")
    public boolean isDefenseDurationValid() {
        return !Boolean.TRUE.equals(defense) || duration != null;
    }

    @JsonIgnore
    @AssertTrue(message = "启用提问时题目数量和答题时间不能为空")
    public boolean isQuestionConfigurationValid() {
        return !Boolean.TRUE.equals(question) || (questionCount != null && answerTime != null);
    }

    @JsonIgnore
    @AssertTrue(message = "通过分数不能大于总分")
    public boolean isScoreRangeValid() {
        return totalScore == null || passScore == null || passScore <= totalScore;
    }

    public AssessmentSettingPO toPO(){
        return AssessmentSettingPO.builder()
                .theme(theme)
                .assessmentRequirements(assessmentRequirements)
                .assessmentCriteria(assessmentCriteria)
                .duration(duration)
                .questionCount(questionCount)
                .answerTime(answerTime)
                .followUp(followUp)
                .followUpPrompt(followUpPrompt)
                .followUpStandards(followUpStandards)
                .assessmentFiles(assessmentFiles == null ? "" : JSONUtil.toJsonStr(assessmentFiles))
                .totalScore(totalScore)
                .passScore(passScore)
                .defense(defense)
                .question(question)
                .showResult(showResult)
                .needAppoint(needAppoint)
                .build();
    }

    public void check() {
    }
}
