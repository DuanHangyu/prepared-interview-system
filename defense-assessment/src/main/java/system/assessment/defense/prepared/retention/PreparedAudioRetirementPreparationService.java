package system.assessment.defense.prepared.retention;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.PreparedSubmissionService;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.*;
import static system.assessment.defense.prepared.retention.PreparedAudioRetirementPreparation.*;

/** INTERNAL ONLY: no HTTP, scheduler, model/storage dependency or delete method.
 * Preparation serializes original evidence and the existing audio-read fence.
 * This is not an object-deletion permit or proof that all copies have exited.
 * Recovery/cancel is only valid before an irreversible execution intent exists.
 * Results/history and student times/permissions are deliberately not mutated. */
@Service
public class PreparedAudioRetirementPreparationService {
    private final PreparedRetentionEvidenceService evidence;
    private final PreparedSubmissionService submissions;
    private final JdbcTemplate jdbc;
    private final PreparedRetentionProtectionStore protection;
    private final PreparedAudioRetirementPreparationStore store;
    private final TransactionTemplate tx;
    private final Clock clock;
    @Autowired public PreparedAudioRetirementPreparationService(PreparedRetentionEvidenceService evidence,PreparedSubmissionService submissions,JdbcTemplate jdbc,PlatformTransactionManager manager){this(evidence,submissions,jdbc,manager,Clock.systemUTC());}
    public PreparedAudioRetirementPreparationService(PreparedRetentionEvidenceService evidence,PreparedSubmissionService submissions,JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock){
        this.evidence=Objects.requireNonNull(evidence);this.submissions=Objects.requireNonNull(submissions);this.jdbc=Objects.requireNonNull(jdbc);this.protection=new PreparedRetentionProtectionStore(jdbc);this.store=new PreparedAudioRetirementPreparationStore(jdbc);this.tx=new TransactionTemplate(manager);this.clock=Objects.requireNonNull(clock);
    }
    public Optional<View> status(int student,int assessment,String attempt){return tx.execute(s->{lock(student,assessment,attempt);guard(student,assessment,attempt);var view=store.latest(student,assessment,attempt);
        return new PreparedAudioRetirementExecutionStore(jdbc).byScope(student,assessment,attempt).isPresent()?view.map(v->new View("EXECUTING",v.preparation(),null)):view;});}
    public Receipt stage(int student,int assessment,String attempt,Command command){
        if(command==null)throw new IllegalArgumentException("原录音准备命令缺失");
        return tx.execute(s->{
            lock(student,assessment,attempt);var guard=guard(student,assessment,attempt);var replay=store.byKey(student,assessment,attempt,command.idempotencyKey());
            if(replay.isPresent()){
                var receipt=replay.orElseThrow().preparation();if(receipt.expectedRevision()!=command.expectedRevision())throw conflict();return receipt;
            }
            long revision=guard.map(PreparedRetentionProtectionStore.Guard::revision).orElse(0L);
            if(revision!=command.expectedRevision()||revision>=9007199254740990L)throw conflict();
            var decision=evidence.inspect(student,assessment,attempt);Instant now=now();
            if(!decision.audioDeleteCandidate()||now.isBefore(decision.audioDueAt()))throw conflict();
            if(guard.isPresent()&&(guard.orElseThrow().held()||!"RETAINED".equals(guard.orElseThrow().audio())||!"RETAINED".equals(guard.orElseThrow().results())||now.isBefore(Instant.parse(guard.orElseThrow().updated()))))throw conflict();
            var originals=submissions.retentionSources(student,assessment,attempt);
            var physical=new PreparedOriginalAudioInventory(submissions,jdbc).read(student,assessment,attempt);
            // Digest canonical identities/proofs only, not transcript/rubric/audio bytes or locators.
            var bindings=originals.sources().stream().map(PreparedSubmissionService.ProcessingSource::binding).toList();
            if(bindings.isEmpty()||bindings.size()>1000)throw conflict();
            var receipt=new Receipt(UUID.randomUUID().toString(),student,assessment,attempt,command.idempotencyKey(),revision,revision+1,decision.audioDueAt().toString(),now.toString(),PreparedAudioRetirementExecution.evidenceHash(bindings,physical,decision.audioDueAt()));
            store.insert(receipt);var time=Timestamp.from(now);
            if(guard.isPresent()){
                if(jdbc.update("UPDATE prepared_retention_guard SET audio_state='RETIRING',revision=?,updated_at=? WHERE assessment_id=? AND student_id=? AND attempt_id=? AND revision=? AND held=0 AND audio_state='RETAINED' AND results_state='RETAINED'",receipt.revision(),time,assessment,student,attempt,revision)!=1)throw conflict();
            }else jdbc.update("INSERT INTO prepared_retention_guard(attempt_id,assessment_id,student_id,revision,created_at,updated_at,audio_state) VALUES (?,?,?,?,?,?,'RETIRING')",attempt,assessment,student,receipt.revision(),time,time);
            guard(student,assessment,attempt);return receipt;
        });
    }
    public Cancellation cancel(int student,int assessment,String attempt,String id,Command command){
        PreparedJobInput.uuid(id);if(command==null)throw new IllegalArgumentException("原录音取消准备命令缺失");
        return tx.execute(s->{
            lock(student,assessment,attempt);var guard=guard(student,assessment,attempt).orElseThrow(PreparedAudioRetirementPreparationService::conflict);
            var view=store.byId(student,assessment,attempt,id).orElseThrow(PreparedAudioRetirementPreparationService::conflict);
            if(view.cancellation()!=null){var receipt=view.cancellation();if(!receipt.idempotencyKey().equals(command.idempotencyKey())||receipt.expectedRevision()!=command.expectedRevision())throw conflict();return receipt;}
            var prepared=view.preparation();Instant now=now();
            if(guard.revision()!=command.expectedRevision()||guard.revision()!=prepared.revision()||guard.held()||!"RETIRING".equals(guard.audio())||!"RETAINED".equals(guard.results())||now.isBefore(Instant.parse(guard.updated())))throw conflict();
            var receipt=new Cancellation(id,student,assessment,attempt,command.idempotencyKey(),guard.revision(),guard.revision()+1,now.toString());store.cancel(receipt);
            if(jdbc.update("UPDATE prepared_retention_guard SET audio_state='RETAINED',revision=?,updated_at=? WHERE assessment_id=? AND student_id=? AND attempt_id=? AND revision=? AND held=0 AND audio_state='RETIRING' AND results_state='RETAINED'",receipt.revision(),Timestamp.from(now),assessment,student,attempt,guard.revision())!=1)throw conflict();
            guard(student,assessment,attempt);return receipt;
        });
    }
    private Optional<PreparedRetentionProtectionStore.Guard> guard(int student,int assessment,String attempt){var guard=protection.guard(student,assessment,attempt);guard.ifPresent(g->protection.latest(student,assessment,attempt,g));return guard;}
    private void lock(int student,int assessment,String attempt){
        if(student<1||assessment<1)throw new IllegalArgumentException("原录音准备范围无效");PreparedJobInput.uuid(attempt);
        if(jdbc.queryForList("SELECT id FROM assessment WHERE id=? FOR UPDATE",Integer.class,assessment).size()!=1||jdbc.queryForList("SELECT id FROM prepared_attempt WHERE assessment_id=? AND student_id=? AND id=? FOR UPDATE",String.class,assessment,student,attempt).size()!=1)throw conflict();
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private static BusinessException conflict(){return new BusinessException(409,"原录音准备范围、版本、到期或保护状态不允许此操作，请核验原记录");}
}
