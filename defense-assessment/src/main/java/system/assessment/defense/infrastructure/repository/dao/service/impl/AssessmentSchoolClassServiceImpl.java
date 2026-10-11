package system.assessment.defense.infrastructure.repository.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import system.assessment.defense.infrastructure.repository.dao.mapper.AssessmentSchoolClassMapper;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSchoolClassPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSchoolClassService;

@Repository
public class AssessmentSchoolClassServiceImpl
        extends ServiceImpl<AssessmentSchoolClassMapper, AssessmentSchoolClassPO>
        implements AssessmentSchoolClassService {
}
