package system.assessment.defense.infrastructure.repository.dao.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import system.assessment.defense.infrastructure.repository.dao.mapper.StudentAssessmentAppointmentMapper;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentAppointmentPO;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;

import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 00:25 2025/9/21
 */
@Repository
public class StudentAssessmentAppointmentServiceImpl extends ServiceImpl<StudentAssessmentAppointmentMapper, StudentAssessmentAppointmentPO> implements StudentAssessmentAppointmentService {
    @Override
    public List<StudentAssessmentAppointmentPO> findByStudentIdAndAssessmentIds(Integer studentId, List<Integer> assessmentIds) {
        return this.list(Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                .eq(StudentAssessmentAppointmentPO::getStudentId, studentId)
                .in(StudentAssessmentAppointmentPO::getAssessmentId, assessmentIds));
    }

    @Override
    public StudentAssessmentAppointmentPO getByStudentIdAndAssessmentId(Integer studentId, Integer assessmentId) {
        return baseMapper.getWithLocationByStudentIdAndAssessmentId(studentId, assessmentId);
    }
}
