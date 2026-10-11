package system.assessment.defense.infrastructure.repository.dao.service;

import com.baomidou.mybatisplus.extension.service.IService;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentAppointmentPO;

import java.util.List;

public interface StudentAssessmentAppointmentService extends IService<StudentAssessmentAppointmentPO> {
    /**
     * 根据学生id和考核id列表查询预约信息
     *
     * @param studentId      学生id
     * @param assessmentIds 考核id列表
     * @return 预约信息列表
     */
    List<StudentAssessmentAppointmentPO> findByStudentIdAndAssessmentIds(Integer studentId, List<Integer> assessmentIds);

    /**
     * 根据学生id和考核id查询预约信息
     *
     * @param studentId      学生id
     * @param assessmentId 考核id
     * @return 预约信息
     */
    StudentAssessmentAppointmentPO getByStudentIdAndAssessmentId(Integer studentId, Integer assessmentId);
}
