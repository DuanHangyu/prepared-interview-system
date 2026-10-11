package system.assessment.defense.infrastructure.repository.dao.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import system.assessment.defense.infrastructure.repository.dao.mapper.AssessmentStudentRelationMapper;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentStudentRelationPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentStudentRelationService;

import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 07:23 2025/8/14
 */
@Repository
public class AssessmentStudentRelationServiceImpl extends ServiceImpl<AssessmentStudentRelationMapper, AssessmentStudentRelationPO> implements AssessmentStudentRelationService {
    @Override
    public List<AssessmentStudentRelationPO> findByStudentId(Integer studentId) {
        return this.list(Wrappers.lambdaQuery(AssessmentStudentRelationPO.class)
                .eq(AssessmentStudentRelationPO::getStudentId, studentId));
    }
}
