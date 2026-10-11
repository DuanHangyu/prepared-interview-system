package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import system.assessment.defense.application.dto.backed.AppointmentSettingDTO;
import system.assessment.defense.application.dto.backed.SchoolClassStudentDTO;

import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 18:21 2025/8/12
 */
@Data
@Schema(description = "考核设置")
public class AssessmentSettingsDTO {

    @Schema(description = "考核ID")
    private Integer id;

    @Schema(description = "考核主题")
    @NotEmpty(message = "考核主题不能为空")
    private String theme;

    @Schema(description = "考核标准")
    @NotEmpty(message = "考核标准不能为空")
    private String assessmentCriteria;

    @Schema(description = "考核标准中的总分")
    @NotNull(message = "考核标准中的总分不能为空")
    private Integer fullMarks;

    @Schema(description = "考核时长")
    @NotNull(message = "考核时长不能为空")
    private Integer duration;

    @Schema(description = "考核题目数量")
    @NotNull(message = "考核题目数量不能为空")
    private Integer questionCount;

    @Schema(description = "考核答题时间")
    @NotNull(message = "考核答题时间不能为空")
    private Integer answerTime;

    @Schema(description = "是否需要追问")
    @NotNull(message = "是否需要追问不能为空")
    private Boolean followUp;

    @Schema(description = "追问提示词")
    private String followUpPrompt;

    @Schema(description = "考核文件")
    private List<FileDTO> assessmentFiles;

    @Schema(description = "总分")
    private Integer totalScore;

    @Schema(description = "通过分数")
    private Integer passScore;

    @Schema(description = "考核要求")
    private String assessmentRequirements;

    @Schema(description = "是否答辩")
    private Boolean defense;

    @Schema(description = "是否提问")
    private Boolean question;

    @Schema(description = "追问标准")
    private String followUpStandards;

    @Schema(description = "是否显示结果")
    private Boolean showResult;

    @Schema(description = "邀请教师id集合")
    private List<TeacherOptionDTO> inviteTeachers;

    @Schema(description = "班级名称集合")
    private List<SchoolClassStudentDTO> schoolClassList;

    @Schema(description = "学生id集合")
    private List<StudentDTO> students;

    @Schema(description = "预约设置")
    private AppointmentSettingDTO appointmentSetting;

    @Schema(description = "是否需要预约")
    private Boolean needAppoint;

    @Schema(description = "参与学生")
    private List<ParticipatingStudentDTO> participatingStudents;

    @Data
    @Schema(description = "参与学生")
    public static class ParticipatingStudentDTO {

        @Schema(description = "学生名")
        private String studentName;

        @Schema(description = "班级")
        private String schoolClass;
    }
}
