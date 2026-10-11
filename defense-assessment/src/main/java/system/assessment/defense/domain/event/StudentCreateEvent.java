package system.assessment.defense.domain.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;

/**
 * @USER taoHouChao
 * @DATE 15:22 2025/10/10
 */
@Getter
public class StudentCreateEvent extends ApplicationEvent {

    private final UserPO student;

    public StudentCreateEvent(Object source, UserPO student) {
        super(source);
        this.student = student;
    }
}
