package system.assessment.defense.domain.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 21:59 2025/9/26
 */
@Data
@Schema(description = "教师汇总")
public class TeacherSummary {

    private Integer id;

    /**
     * 0: 学生
     * 1: 老师
     * 2: 管理员
     */
    private Integer type;

    private List<Integer> studentIds;

    /**
     * 当前教师可管理的考核范围。学习记录必须同时匹配学生范围和考核范围，
     * 避免同一学生参加其他教师的考核时发生跨教师数据泄露。
     */
    private List<Integer> managedAssessmentIds;
}
