package system.assessment.defense.infrastructure.repository.dao.service;

import com.baomidou.mybatisplus.extension.service.IService;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;

import java.util.Optional;

public interface AssessmentSettingService extends IService<AssessmentSettingPO> {

    Optional<AssessmentSettingPO> findById(Integer assessmentId);

    Optional<AssessmentSettingPO> findByIdLock(Integer assessmentId);
}
