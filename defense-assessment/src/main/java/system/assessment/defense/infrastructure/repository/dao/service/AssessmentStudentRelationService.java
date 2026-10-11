package system.assessment.defense.infrastructure.repository.dao.service;

import com.baomidou.mybatisplus.extension.service.IService;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentStudentRelationPO;

import java.util.List;

public interface AssessmentStudentRelationService extends IService<AssessmentStudentRelationPO> {
    /**
     * 根据学生ID查询考核关系
     *
     * @param studentId 学生ID
     * @return 考核关系列表
     */
    List<AssessmentStudentRelationPO> findByStudentId(Integer studentId);
}
