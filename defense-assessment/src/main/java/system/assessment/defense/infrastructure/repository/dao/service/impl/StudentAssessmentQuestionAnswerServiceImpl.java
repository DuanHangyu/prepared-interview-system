package system.assessment.defense.infrastructure.repository.dao.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import system.assessment.defense.infrastructure.repository.dao.mapper.StudentAssessmentQuestionAnswerMapper;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentQuestionAnswerPO;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;

import java.util.List;
import java.util.Optional;

/**
 * @USER taoHouChao
 * @DATE 20:05 2025/8/15
 */
@Repository
public class StudentAssessmentQuestionAnswerServiceImpl extends ServiceImpl<StudentAssessmentQuestionAnswerMapper, StudentAssessmentQuestionAnswerPO>
        implements StudentAssessmentQuestionAnswerService {
    @Override
    public List<StudentAssessmentQuestionAnswerPO> listByAssessmentIdAndStudentId(Integer assessmentId, Integer studentId) {
        return this.list(Wrappers.lambdaQuery(StudentAssessmentQuestionAnswerPO.class)
                .eq(StudentAssessmentQuestionAnswerPO::getAssessmentId, assessmentId)
                .eq(StudentAssessmentQuestionAnswerPO::getStudentId, studentId));
    }

    @Override
    public void clearQuestionAnswer(Integer studentId, int assessmentId) {
        this.remove(Wrappers.lambdaQuery(StudentAssessmentQuestionAnswerPO.class)
                .eq(StudentAssessmentQuestionAnswerPO::getAssessmentId, assessmentId)
                .eq(StudentAssessmentQuestionAnswerPO::getStudentId, studentId));
    }

    @Override
    public Optional<StudentAssessmentQuestionAnswerPO> lastQuestionAnswer(Integer studentId, Integer assessmentId) {
        return Optional.ofNullable(
                this.getOne(Wrappers.lambdaQuery(StudentAssessmentQuestionAnswerPO.class)
                        .eq(StudentAssessmentQuestionAnswerPO::getStudentId, studentId)
                        .eq(StudentAssessmentQuestionAnswerPO::getAssessmentId, assessmentId)
                        .last("order by id desc limit 1"))
        );
    }
}
