package system.assessment.defense.prepared.retention;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.PreparedSubmissionService;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static system.assessment.defense.prepared.retention.PreparedAudioRetirementExecution.*;

/** Internal irreversible intent only. No controller, scheduler, storage/model
 * dependency, automatic expiry or delete method. Once persisted, cancel is never
 * safe, including after restart. Actual IO/progress/final exit remain separate. */
@Service
public class PreparedAudioRetirementExecutionService {
    private final PreparedRetentionEvidenceService evidence;
    private final PreparedSubmissionService submissions;
    private final JdbcTemplate jdbc;
    private final PreparedRetentionProtectionStore protection;
    private final PreparedAudioRetirementPreparationStore preparations;
    private final PreparedAudioRetirementExecutionStore executions;
    private final PreparedOriginalAudioInventory inventory;
    private final TransactionTemplate tx;
    private final Clock clock;
    @Autowired public PreparedAudioRetirementExecutionService(PreparedRetentionEvidenceService evidence,PreparedSubmissionService submissions,JdbcTemplate jdbc,PlatformTransactionManager manager){this(evidence,submissions,jdbc,manager,Clock.systemUTC());}
    public PreparedAudioRetirementExecutionService(PreparedRetentionEvidenceService evidence,PreparedSubmissionService submissions,JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock){this(evidence,submissions,jdbc,manager,clock,new PreparedOriginalAudioInventory(submissions,jdbc));}
    PreparedAudioRetirementExecutionService(PreparedRetentionEvidenceService evidence,PreparedSubmissionService submissions,JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,PreparedOriginalAudioInventory inventory){
        this.evidence=Objects.requireNonNull(evidence);this.submissions=Objects.requireNonNull(submissions);this.jdbc=Objects.requireNonNull(jdbc);
        this.protection=new PreparedRetentionProtectionStore(jdbc);this.preparations=new PreparedAudioRetirementPreparationStore(jdbc);this.executions=new PreparedAudioRetirementExecutionStore(jdbc);
        this.inventory=Objects.requireNonNull(inventory);this.tx=new TransactionTemplate(manager);this.clock=Objects.requireNonNull(clock);
    }
    public Optional<Receipt> status(int student,int assessment,String attempt){return tx.execute(s->{lock(student,assessment,attempt);guard(student,assessment,attempt);return executions.byScope(student,assessment,attempt);});}
    public Receipt begin(int student,int assessment,String attempt,String preparation,PreparedAudioRetirementPreparation.Command command){
        PreparedJobInput.uuid(preparation);if(command==null)throw new IllegalArgumentException("原录音退出命令缺失");
        return tx.execute(s->{
            lock(student,assessment,attempt);var guard=guard(student,assessment,attempt).orElseThrow(PreparedAudioRetirementExecutionService::conflict);
            var prepared=preparations.byId(student,assessment,attempt,preparation).orElseThrow(PreparedAudioRetirementExecutionService::conflict);
            var replay=executions.byKey(student,assessment,attempt,command.idempotencyKey());
            if(replay.isPresent()){
                var r=replay.orElseThrow();if(r.expectedRevision()!=command.expectedRevision()||!r.preparationId().equals(preparation))throw conflict();return r;
            }
            Instant now=clock.instant().truncatedTo(ChronoUnit.MILLIS);var original=prepared.preparation();
            // guard() already verifies exact preparation/execution revision, time,
            // RETIRING audio, retained results and no hold. A registered intent has
            // revision preparation+1, so it can never satisfy the new-intent fence.
            if(!"STAGED".equals(prepared.state())||guard.revision()!=command.expectedRevision()||guard.revision()!=original.revision()
                ||now.isBefore(Instant.parse(guard.updated())))throw conflict();
            var decision=evidence.inspectForExecution(student,assessment,attempt,preparation,guard.revision());
            if(!decision.audioDeleteCandidate()||now.isBefore(decision.audioDueAt())||!decision.audioDueAt().toString().equals(original.audioDueAt()))throw conflict();
            var bindings=submissions.retentionSources(student,assessment,attempt).sources().stream().map(PreparedSubmissionService.ProcessingSource::binding).toList();
            var physical=inventory.read(student,assessment,attempt);
            if(!evidenceHash(bindings,physical,decision.audioDueAt()).equals(original.evidenceSha256()))throw conflict();
            var receipt=new Receipt(UUID.randomUUID().toString(),student,assessment,attempt,preparation,command.idempotencyKey(),guard.revision(),guard.revision()+1,
                now.toString(),original.evidenceSha256(),physical.sha256(),physical.objects().size(),physical.byteSize());
            executions.insert(receipt);
            if(jdbc.update("UPDATE prepared_retention_guard SET revision=?,updated_at=? WHERE assessment_id=? AND student_id=? AND attempt_id=? AND revision=? AND held=0 AND audio_state='RETIRING' AND results_state='RETAINED'",
                receipt.revision(),Timestamp.from(now),assessment,student,attempt,guard.revision())!=1)throw conflict();
            guard(student,assessment,attempt);return receipt;
        });
    }
    private Optional<PreparedRetentionProtectionStore.Guard> guard(int student,int assessment,String attempt){var g=protection.guard(student,assessment,attempt);g.ifPresent(v->protection.latest(student,assessment,attempt,v));return g;}
    private void lock(int student,int assessment,String attempt){
        if(student<1||assessment<1)throw new IllegalArgumentException("原录音退出范围无效");PreparedJobInput.uuid(attempt);
        if(jdbc.queryForList("SELECT id FROM assessment WHERE id=? FOR UPDATE",Integer.class,assessment).size()!=1
            ||jdbc.queryForList("SELECT id FROM prepared_attempt WHERE assessment_id=? AND student_id=? AND id=? FOR UPDATE",String.class,assessment,student,attempt).size()!=1)throw conflict();
    }
    private static BusinessException conflict(){return new BusinessException(409,"原录音退出范围、版本、到期或保护状态不允许此操作，请核验原记录");}
}
