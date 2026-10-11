package system.assessment.defense.infrastructure.repository.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import system.assessment.defense.infrastructure.repository.dao.mapper.AssessmentAppointmentSettingMapper;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentAppointmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentAppointmentSettingService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 20:50 2025/9/23
 */
@Repository
public class AssessmentAppointmentSettingServiceImpl extends ServiceImpl<AssessmentAppointmentSettingMapper, AssessmentAppointmentSettingPO> implements AssessmentAppointmentSettingService {
}
