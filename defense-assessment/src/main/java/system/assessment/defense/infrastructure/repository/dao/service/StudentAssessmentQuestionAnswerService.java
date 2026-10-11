package system.assessment.defense.infrastructure.repository.dao.service;

import com.baomidou.mybatisplus.extension.service.IService;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentQuestionAnswerPO;

import java.util.List;
import java.util.Optional;

public interface StudentAssessmentQuestionAnswerService extends IService<StudentAssessmentQuestionAnswerPO> {

    /**
     * 根据考核id和学生id查询学生考核问题答案
     *
     * @param assessmentId 考核id
     * @param studentId    学生id
     * @return 学生考核问题答案
     */
    List<StudentAssessmentQuestionAnswerPO> listByAssessmentIdAndStudentId(Integer assessmentId, Integer studentId);

    /**
     * 清空学生考核问题答案
     *
     * @param studentId    学生id
     * @param assessmentId 考核id
     */
    void clearQuestionAnswer(Integer studentId, int assessmentId);

    /**
     * 获取最后一个学生考核问题答案
     *
     * @param studentId    学生id
     * @param assessmentId 考核id
     * @return 学生考核问题答案
     */
    Optional<StudentAssessmentQuestionAnswerPO> lastQuestionAnswer(Integer studentId, Integer assessmentId);
}
