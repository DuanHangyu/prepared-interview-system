package system.assessment.defense.domain.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;

/**
 * @USER taoHouChao
 * @DATE 21:27 2025/10/20
 */
@Getter
public class SettingCreateOrUpdateEvent extends ApplicationEvent {

    private final AssessmentSettingPO settingPO;

    public SettingCreateOrUpdateEvent(Object source, AssessmentSettingPO settingPO) {
        super(source);
        this.settingPO = settingPO;
    }
}
