package system.assessment.defense.infrastructure.repository.dao.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import system.assessment.defense.infrastructure.repository.dao.mapper.AssessmentSettingMapper;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentAppointmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentAppointmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;

import java.util.Optional;

/**
 * @USER taoHouChao
 * @DATE 20:58 2025/8/7
 */
@Repository
@RequiredArgsConstructor
public class AssessmentSettingServiceImpl extends ServiceImpl<AssessmentSettingMapper, AssessmentSettingPO> implements AssessmentSettingService {

    private final AssessmentAppointmentSettingService appointmentSettingService;


    @Override
    public Optional<AssessmentSettingPO> findById(Integer assessmentId) {
        return Optional.ofNullable(this.getById(assessmentId));
    }

    @Override
    public Optional<AssessmentSettingPO> findByIdLock(Integer assessmentId) {
        Optional<AssessmentSettingPO> settingOp = Optional.ofNullable(this.getBaseMapper().selectOne(Wrappers.lambdaQuery(AssessmentSettingPO.class)
                .eq(AssessmentSettingPO::getId, assessmentId)
                .last("for update")));
        settingOp.ifPresent(setting -> setting.setAppointmentSettingList(this.appointmentSettingService.list(Wrappers.lambdaQuery(AssessmentAppointmentSettingPO.class)
                .eq(AssessmentAppointmentSettingPO::getAssessmentId, assessmentId))));
        return settingOp;
    }
}
