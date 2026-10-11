package system.assessment.defense.infrastructure.repository.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * @USER taoHouChao
 * @DATE 20:38 2025/8/7
 */
@Data
@Schema(description = "考核设置")
@TableName("assessment")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AssessmentSettingPO {

    private static final String FOLLOW_UP_DEFAULT_PROMPT = "该追问必须与原题在考查主题或知识范畴上保持相似，但不得以原题的答案、结论或事实判断为前提，不得存在逻辑因果、条件依赖、推导关系或隐含预设；";

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "考核主题")
    private String theme;

    @Schema(description = "考核要求")
    private String assessmentRequirements;

    @Schema(description = "考核标准")
    private String assessmentCriteria;

    @Schema(description = "考核总分")
    private Integer totalScore;

    @Schema(description = "通过分数")
    private Integer passScore;

    @Schema(description = "答辩时长")
    private Integer duration;

    @Schema(description = "考核题目数量")
    private Integer questionCount;

    @Schema(description = "考核答题时间")
    private Integer answerTime;

    @Schema(description = "是否需要追问")
    private Boolean followUp;

    @Schema(description = "追问提示词")
    private String followUpPrompt;

    @Schema(description = "追问标准")
    private String followUpStandards;

    @Schema(description = "考核文件")
    private String assessmentFiles;

    @Schema(description = "是否答辩")
    private Boolean defense;

    @Schema(description = "是否提问")
    private Boolean question;

    @Schema(description = "是否显示结果")
    private Boolean showResult;

    @Schema(description = "创建人")
    private Integer createId;

    @Schema(description = "是否需要预约")
    private Boolean needAppoint;

    @Schema(description = "考核失败是否惩罚")
    private Boolean assessmentFailPunish;

    @Schema(description = "可重新预约时间")
    private LocalDateTime rescheduleAppointTime;

    @Schema(description = "最后预约时间")
    private LocalDateTime lastAppointTime;

    @Schema(description = "预约设置")
    @TableField(exist = false)
    private List<AssessmentAppointmentSettingPO> appointmentSettingList;

    @Schema(description = "是否删除")
    private Boolean deleted;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "删除时间，用于材料保留期计算")
    private LocalDateTime deletedAt;

    public String getFollowUpPrompt() {
        if (!Objects.equals(followUp, true)) {
            return "";
        }
        if (StringUtils.isBlank(followUpPrompt)) {
            return FOLLOW_UP_DEFAULT_PROMPT;
        }
        return followUpPrompt;
    }

    public boolean defenseAndQuestion(){
        return defense && question;
    }

    public boolean onlyDefense(){
        return !question && defense;
    }

    public boolean onlyQuestion(){
        return question && !defense;
    }

    public AssessmentAppointmentSettingPO findAppointmentSetting(LocalDateTime timePeriod) {
        if (CollectionUtils.isEmpty(appointmentSettingList)) {
            return null;
        }
        return appointmentSettingList.stream()
                .filter(appointmentSettingPO -> appointmentSettingPO.getTimePeriod().equals(timePeriod))
                .findFirst()
                .orElse(null);
    }
}
