package system.assessment.defense.prepared.attempt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.service.ResourceAccessControlService;
import java.time.Clock;
import static system.assessment.defense.prepared.attempt.PreparedStudentEligibility.*;

@Service
public class PreparedStudentEligibilityService {
    private final ResourceAccessControlService access;
    private final PreparedStudentEligibilityRepository repository;
    private final Clock clock;
    @Autowired public PreparedStudentEligibilityService(ResourceAccessControlService access,PreparedStudentEligibilityRepository repository) {
        this(access,repository,Clock.systemUTC());
    }
    public PreparedStudentEligibilityService(ResourceAccessControlService access,PreparedStudentEligibilityRepository repository,Clock clock) {
        this.access=access;this.repository=repository;this.clock=clock;
    }
    @Transactional(readOnly=true)
    public Status status(int student,int assessment) {
        if (student<1 || assessment<1) throw new IllegalArgumentException("身份或考核标识无效");
        access.requireAssignedStudent(student,assessment);
        var context=repository.context(student,assessment);
        var paper=context.paperVersionId()==null?null:PreparedStudentPaperVerifier.verify(assessment,context.snapshotJson(),context.contentSha256());
        var now=clock.instant();
        var phase=PreparedStudentEligibilityPolicy.inspect(new PreparedStudentEligibilityPolicy.Conditions(
                paper!=null,context.hasLegacyRecord(),context.appointmentRequired(),context.appointmentState(),context.appointmentAt()),now);
        var slot=context.appointmentRequired()?context.appointmentAt():null;
        return new Status(assessment,paper==null?context.theme():paper.theme(),paper==null?0:paper.questionCount(),paper!=null,
                context.appointmentRequired(),phase,phase.message(),now.toString(),slot==null?null:slot.toString(),
                slot==null?null:slot.plusSeconds(PreparedStudentEligibilityPolicy.FIRST_START_WINDOW_SECONDS).toString());
    }
}
