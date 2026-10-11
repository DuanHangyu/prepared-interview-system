package system.assessment.defense.prepared.attempt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import static system.assessment.defense.prepared.attempt.PreparedAttemptStart.*;

/** Durable start foundation; HTTP mutations remain gated until authorized capture/upload recovery is integrated. */
@Service
public class PreparedAttemptStartService {
    private final ResourceAccessControlService access;
    private final PreparedStudentEligibilityRepository eligibility;
    private final PreparedAttemptStartRepository repository;
    private final Clock clock;
    @Autowired public PreparedAttemptStartService(ResourceAccessControlService access,PreparedStudentEligibilityRepository eligibility,PreparedAttemptStartRepository repository) {
        this(access,eligibility,repository,Clock.systemUTC());
    }
    public PreparedAttemptStartService(ResourceAccessControlService access,PreparedStudentEligibilityRepository eligibility,PreparedAttemptStartRepository repository,Clock clock) {
        this.access=access;this.eligibility=eligibility;this.repository=repository;this.clock=clock;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Summary start(int student,int assessment,Command command) {
        validateScope(student,assessment);if(command==null)throw new IllegalArgumentException("开考命令无效");command.validate();
        access.requireAssignedStudent(student,assessment);
        boolean frozen=repository.lockForStudentStart(student,assessment);
        // Locking/current read after the parent lock: do not reuse an earlier repeatable-read authorization snapshot.
        var context=frozen?eligibility.contextForShare(student,assessment):eligibility.contextForUpdate(student,assessment);
        String hash=PreparedJobInput.digest("prepared-attempt-start-v1:"+assessment+":"+student);
        var receipt=repository.receipt(student,assessment,command.idempotencyKey());
        if(receipt.isPresent()) {
            if(!hash.equals(receipt.get().hash()))throw new BusinessException(409,"原开考命令不一致");
            return verify(repository.get(student,assessment,receipt.get().attemptId()),true);
        }
        var existing=repository.currentForUpdate(student,assessment);
        Summary result;
        if(existing.isPresent())result=verify(existing.get(),true); // A new key is NEVER a request for re-examination.
        else {
            if(context.paperVersionId()!=null)PreparedStudentPaperVerifier.verify(assessment,context.snapshotJson(),context.contentSha256());
            var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            var phase=PreparedStudentEligibilityPolicy.inspect(new PreparedStudentEligibilityPolicy.Conditions(context.paperVersionId()!=null,context.hasLegacyRecord(),
                    context.appointmentRequired(),context.appointmentState(),context.appointmentAt()),now);
            if(phase!=PreparedStudentEligibility.Phase.READY_FOR_PREFLIGHT)throw new BusinessException(409,phase.message());
            result=repository.create(student,assessment,context.paperVersionId(),now,frozen);
            if(context.appointmentRequired())repository.startAppointment(student,assessment);
        }
        repository.recordReceipt(student,assessment,command.idempotencyKey(),hash,result.id());return result;
    }
    @Transactional(readOnly=true)
    public Optional<Summary> current(int student,int assessment) {
        validateScope(student,assessment);access.requireAssignedStudent(student,assessment);eligibility.context(student,assessment);
        return repository.current(student,assessment).map(summary->verify(summary,false));
    }
    private Summary verify(Summary summary,boolean lock) {
        var paper=lock?repository.boundPaperForShare(summary.assessmentId(),summary.paperVersionId()):repository.boundPaper(summary.assessmentId(),summary.paperVersionId());
        PreparedStudentPaperVerifier.verify(summary.assessmentId(),paper.content(),paper.sha256());return summary;
    }
    private static void validateScope(int student,int assessment) {if(student<1||assessment<1)throw new IllegalArgumentException("身份或考核标识无效");}
}
