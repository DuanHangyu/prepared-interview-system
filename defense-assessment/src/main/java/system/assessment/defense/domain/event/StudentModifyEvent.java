package system.assessment.defense.domain.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;

/**
 * @USER taoHouChao
 * @DATE 15:23 2025/10/10
 */
@Getter
public class StudentModifyEvent extends ApplicationEvent {

    private final UserPO oldStudent;

    private final UserPO newStudent;
    public StudentModifyEvent(Object source, UserPO oldStudent, UserPO newStudent) {
        super(source);
        this.oldStudent = oldStudent;
        this.newStudent = newStudent;
    }
}
