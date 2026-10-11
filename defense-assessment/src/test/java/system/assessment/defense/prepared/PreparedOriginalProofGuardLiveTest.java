package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.assertj.core.api.ThrowableAssert;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.processing.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind;
import static system.assessment.defense.prepared.processing.PreparedProcessingCodec.encode;

/** Public service commands and actual owned MySQL, with reversible corruption of
 * this test's original receipts. Native codecs, synthetic model responses and SDK
 * doubles are fixtures: NOT paid-provider, physical-device or deletion acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedOriginalProofGuardLiveTest {
    final PreparedProcessingLiveTest p=new PreparedProcessingLiveTest();
    @TempDir Path folder;
    @BeforeEach void setup()throws Exception {p.folder=folder;p.setup();}
    @AfterEach void cleanup(){p.cleanup();}

    String answer(){return p.jdbc.queryForObject("SELECT answer_id FROM prepared_submission_answer WHERE attempt_id=? ORDER BY ordinal LIMIT 1",String.class,p.attempt);}
    PreparedSubmissionService.ProcessingSource original(){return p.f.service.currentSubmittedSources(p.student,p.assessment,p.attempt).getFirst();}
    void rollback(Runnable action){p.f.m.f.f.ends.q.f.tx.executeWithoutResult(s->{action.run();s.setRollbackOnly();});}
    void rejectMutation(String sql,Object[] args,ThrowableAssert.ThrowingCallable action,String message){
        var receipt=p.f.status();int jobs=p.count("prepared_job"),audits=p.count("prepared_audit");
        rollback(()->{assertThat(p.jdbc.update(sql,args)).isEqualTo(1);assertThatThrownBy(action).isInstanceOf(RuntimeException.class).hasMessageContaining(message);});
        assertThat(p.f.status()).isEqualTo(receipt);assertThat(p.count("prepared_job")).isEqualTo(jobs);assertThat(p.count("prepared_audit")).isEqualTo(audits);
    }
    PreparedProcessing.View scored(){var transcript=p.transcribe(p.claim(Kind.TRANSCRIBE));p.score(p.claim(Kind.SCORE),"7");return p.view(transcript.answerId());}

    @Test void absentOriginalInputsAreRejectedBeforeAnyAnswerOrTaskMutation(){
        var receipt=p.f.status();int jobs=p.count("prepared_job"),audits=p.count("prepared_audit");
        for(ThrowableAssert.ThrowingCallable call:List.<ThrowableAssert.ThrowingCallable>of(
            ()->p.f.service.processingSource(null),()->p.f.service.reviewSource(p.actor,null),
            ()->p.f.service.submit(p.student,p.assessment,p.attempt,null),
            ()->p.f.service.retentionSources(0,p.assessment,p.attempt),()->p.f.service.retentionSources(p.student,0,p.attempt)))
            assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
        assertThat(p.f.status()).isEqualTo(receipt);assertThat(p.count("prepared_job")).isEqualTo(jobs);assertThat(p.count("prepared_audit")).isEqualTo(audits);
    }
    @Test void missingEndAnchorAndAnExtendedUploadDeadlineCannotRecoverFalseSubmission(){
        rejectMutation("UPDATE prepared_attempt SET answering_ended_at=NULL,supplemental_upload_deadline=NULL WHERE id=?",new Object[]{p.attempt},p.f::status,"原作答结束或补传起算损坏");
        rejectMutation("UPDATE prepared_attempt SET supplemental_upload_deadline=DATE_ADD(supplemental_upload_deadline,INTERVAL 1 SECOND) WHERE id=?",new Object[]{p.attempt},p.f::status,"原作答结束或补传起算损坏");
        rejectMutation("UPDATE prepared_attempt SET state='UPLOAD_PENDING' WHERE id=?",new Object[]{p.attempt},p.f::status,"原提交回执状态损坏");
    }
    @Test void selfConsistentFrozenSubmissionStillRequiresSupportedSchemaAndAllOriginalAnswers()throws Exception {
        String id=p.f.status().receipt().id();String json=p.jdbc.queryForObject("SELECT snapshot_json FROM prepared_submission WHERE id=?",String.class,id);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var tree=mapper.readTree(json);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("schemaVersion",2);
        String unsupported=PreparedJobInput.json(tree.toString(),65536);
        rejectMutation("UPDATE prepared_submission SET snapshot_json=?,snapshot_sha256=? WHERE id=?",new Object[]{unsupported,PreparedJobInput.digest(unsupported),id},p.f::status,"原提交回答证明损坏");
        tree=mapper.readTree(json);((com.fasterxml.jackson.databind.node.ArrayNode)tree.get("answers")).remove(2);
        String incomplete=PreparedJobInput.json(tree.toString(),65536);
        rejectMutation("UPDATE prepared_submission SET snapshot_json=?,snapshot_sha256=? WHERE id=?",new Object[]{incomplete,PreparedJobInput.digest(incomplete),id},p.f::status,"原提交回答证明损坏");
    }
    @Test void swappedJobBindingsAndWrongOriginalWorkCannotBecomeAnotherAnswer(){
        var original=original();var work=original.binding().input();
        rejectMutation("UPDATE prepared_submission_answer SET transcribe_job_id=score_job_id WHERE answer_id=?",new Object[]{work.answer()},p.f::status,"原提交范围损坏");
        var wrong=new PreparedSubmission.Work(work.schemaVersion(),work.student(),work.assessment(),work.attempt(),work.paperVersion(),UUID.randomUUID().toString(),
            work.answer(),work.question(),work.ordinal(),work.answerRevision(),work.manifestSha256(),work.mediaJob(),work.mediaProofSha256());
        p.conflict(()->p.f.service.processingSource(wrong));assertThat(p.f.service.processingSource(work)).isEqualTo(original);
    }
    @Test void submittedStateWithoutTheUniqueReceiptDoesNotMasqueradeAsSuccess(){
        var receipt=p.f.status();rollback(()->{
            assertThat(p.jdbc.update("DELETE FROM prepared_submission_command WHERE attempt_id=?",p.attempt)).isPositive();
            assertThat(p.jdbc.update("DELETE FROM prepared_submission_answer WHERE attempt_id=?",p.attempt)).isEqualTo(3);
            assertThat(p.jdbc.update("DELETE FROM prepared_submission WHERE attempt_id=?",p.attempt)).isEqualTo(1);
            assertThatThrownBy(p.f::status).isInstanceOf(IllegalStateException.class).hasMessageContaining("缺少唯一回执");
            p.conflict(()->p.f.service.currentSubmittedSources(p.student,p.assessment,p.attempt));
            p.conflict(()->p.f.service.processingSource(originalFromReceipt(receipt)));
        });assertThat(p.f.status()).isEqualTo(receipt);
    }
    PreparedSubmission.Work originalFromReceipt(PreparedSubmission.Summary receipt){
        String input=p.jdbc.queryForObject("SELECT input_json FROM prepared_job WHERE assessment_id=? AND kind='TRANSCRIBE' ORDER BY id LIMIT 1",String.class,p.assessment);
        var work=PreparedProcessingCodec.decode(input,PreparedSubmission.Work.class);assertThat(work.submission()).isEqualTo(receipt.receipt().id());return work;
    }
    @Test void missingRecordingAuthorizationAndOutOfBoundsAsrExpiryDoNotIssueCapabilities(){
        var lease=p.claim(Kind.TRANSCRIBE);var plan=p.service.plan(lease);var media=plan.original().media();var now=Instant.parse("2026-10-07T00:00:00Z");
        int audits=p.count("prepared_audit");
        for(ThrowableAssert.ThrowingCallable call:List.<ThrowableAssert.ThrowingCallable>of(
            ()->p.service.beginTranscriptionAudioIo(lease,null),
            ()->p.service.recordAsrInputAccess(lease,null,now.plusSeconds(86400),now),
            ()->p.service.recordAsrInputAccess(lease,media,null,now),
            ()->p.service.recordAsrInputAccess(lease,media,now.plusSeconds(86400),null),
            ()->p.service.recordAsrInputAccess(lease,media,now.plusSeconds(82799),now),
            ()->p.service.recordAsrInputAccess(lease,media,now.plusSeconds(86401),now),
            ()->p.service.recordAudioAccess(p.actor,p.assessment,p.attempt,media.scope().answerId(),null,now),
            ()->p.service.recordAudioAccess(p.actor,p.assessment,p.attempt,media.scope().answerId(),media,null)))
            assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
        assertThat(p.count("prepared_audit")).isEqualTo(audits);assertThat(p.f.status().submitted()).isTrue();
    }
    @Test void aDifferentOriginalRecordingCannotAuthorizePreviewOrTranscription(){
        var lease=p.claim(Kind.TRANSCRIBE);var plan=p.service.plan(lease);var sources=p.f.service.currentSubmittedSources(p.student,p.assessment,p.attempt);
        var other=sources.stream().filter(s->!s.binding().input().answer().equals(plan.original().binding().input().answer())).findFirst().orElseThrow();
        var now=Instant.parse("2026-10-07T00:00:00Z");int audits=p.count("prepared_audit");String answer=plan.original().binding().input().answer();
        p.conflict(()->p.service.beginTranscriptionAudioIo(lease,new PreparedProcessingService.Plan(other,null,null,0,null)));
        p.conflict(()->p.service.recordAsrInputAccess(lease,other.media(),now.plusSeconds(86400),now));
        p.conflict(()->p.service.beginReviewAudioIo(p.actor,p.assessment,p.attempt,answer,other.media()));
        p.conflict(()->p.service.recordAudioAccess(p.actor,p.assessment,p.attempt,answer,other.media(),now.plusSeconds(300)));
        p.transcribe(lease);var scoring=p.claim(Kind.SCORE);var scorePlan=p.service.plan(scoring);
        p.conflict(()->p.service.beginTranscriptionAudioIo(scoring,scorePlan));
        p.conflict(()->p.service.recordAsrInputAccess(scoring,scorePlan.original().media(),now.plusSeconds(86400),now));
        assertThat(p.count("prepared_audit")).isEqualTo(audits+1);assertThat(p.f.status().submitted()).isTrue();
    }
    @Test void retentionRequiresAnActiveTransactionAndDoesNotAcceptUnrelatedPersistedJobs(){
        var original=original();var work=original.binding().input();var job=p.f.m.jobs.get(original.binding().transcribeJob()).orElseThrow();
        assertThatThrownBy(()->p.service.retentionJobMatches(job,original)).isInstanceOf(IllegalStateException.class).hasMessageContaining("需要短事务");
        rollback(()->{
            assertThatThrownBy(()->p.service.retentionJobMatches(null,original)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->p.service.retentionJobMatches(job,null)).isInstanceOf(IllegalArgumentException.class);
            assertThat(p.service.retentionJobMatches(job,original)).isTrue();
            var teacher=p.f.m.jobs.enqueue(p.actor,p.assessment,Kind.SCORE,work.answerRevision(),UUID.randomUUID().toString(),"{}");
            assertThat(p.service.retentionJobMatches(teacher,original)).isFalse();
            var unrelated=p.f.m.jobs.enqueue(p.student,p.assessment,Kind.QUESTIONS,work.answerRevision(),UUID.randomUUID().toString(),"{}");
            assertThat(p.service.retentionJobMatches(unrelated,original)).isFalse();
        });assertThat(p.f.status().submitted()).isTrue();
    }
    @Test void retentionRechecksPersistedMediaAndInitialTaskKindsVersionsAndInputs(){
        var original=original();var work=original.binding().input();
        for(var mutation:List.of(
            Map.entry("UPDATE prepared_job SET kind='SCORE' WHERE id=?",work.mediaJob()),
            Map.entry("UPDATE prepared_job SET input_version=input_version+1 WHERE id=?",work.mediaJob()),
            Map.entry("UPDATE prepared_job SET kind='SCORE' WHERE id=?",original.binding().transcribeJob()),
            Map.entry("UPDATE prepared_job SET input_version=input_version+1 WHERE id=?",original.binding().transcribeJob()),
            Map.entry("UPDATE prepared_job SET input_json='{}' WHERE id=?",original.binding().transcribeJob())))
            rollback(()->{assertThat(p.jdbc.update(mutation.getKey(),mutation.getValue())).isEqualTo(1);
                assertThat(p.service.retentionJobMatches(p.f.m.jobs.get(mutation.getValue()).orElseThrow(),original)).isFalse();});
        rollback(()->assertThat(p.service.retentionJobMatches(p.f.m.jobs.get(work.mediaJob()).orElseThrow(),original)).isTrue());
        assertThat(p.f.status().submitted()).isTrue();
    }
    @Test void retentionDoesNotTrustSuccessWithoutTheActualOriginalResult(){
        var transcript=p.transcribe(p.claim(Kind.TRANSCRIBE));var original=p.f.service.currentSubmittedSources(p.student,p.assessment,p.attempt).stream()
            .filter(s->s.binding().input().answer().equals(transcript.answerId())).findFirst().orElseThrow();
        rollback(()->{assertThat(p.jdbc.update("DELETE FROM prepared_processing_head WHERE answer_id=?",transcript.answerId())).isEqualTo(1);
            assertThat(p.jdbc.update("DELETE FROM prepared_processing_result WHERE id=?",transcript.id())).isEqualTo(1);
            assertThatThrownBy(()->p.service.retentionJobMatches(p.f.m.jobs.get(transcript.jobId()).orElseThrow(),original))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("成功任务缺少结果");});
        assertThat(p.view(transcript.answerId()).transcriptId()).isEqualTo(transcript.id());assertThat(p.f.status().submitted()).isTrue();
    }
    @Test void regradeProvenanceRejectsInvalidSchemaAndChangedImmutableRegistration(){
        var view=scored();var command=new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),view.revision(),view.transcriptId(),"原任务归属核验");
        var receipt=p.service.regrade(p.actor,p.assessment,p.attempt,view.answerId(),command);var original=p.f.service.currentSubmittedSources(p.student,p.assessment,p.attempt).stream()
            .filter(s->s.binding().input().answer().equals(view.answerId())).findFirst().orElseThrow();
        String input=p.f.m.jobs.get(receipt.jobId()).orElseThrow().inputJson();
        for(var change:Map.<String,Object>of("schemaVersion",2,"requestedRevision",0,"requestedBy",0).entrySet())
            rollback(()->{assertThat(p.jdbc.update("UPDATE prepared_job SET input_json=? WHERE id=?",p.damage(input,change.getKey(),change.getValue()),receipt.jobId())).isEqualTo(1);
                assertThatThrownBy(()->p.service.retentionJobMatches(p.f.m.jobs.get(receipt.jobId()).orElseThrow(),original)).isInstanceOf(RuntimeException.class).hasMessageContaining("原重评输入或归属损坏");});
        for(var change:List.of(Map.entry("UPDATE prepared_score_request SET requested_revision=requested_revision+1 WHERE job_id=?",receipt.jobId()),
            Map.entry("UPDATE prepared_score_request SET requested_by="+p.student+" WHERE job_id=?",receipt.jobId())))
            rollback(()->{assertThat(p.jdbc.update(change.getKey(),change.getValue())).isEqualTo(1);
                assertThatThrownBy(()->p.service.retentionJobMatches(p.f.m.jobs.get(receipt.jobId()).orElseThrow(),original)).isInstanceOf(RuntimeException.class).hasMessageContaining("原重评输入或归属损坏");});
        rollback(()->assertThat(p.service.retentionJobMatches(p.f.m.jobs.get(receipt.jobId()).orElseThrow(),original)).isTrue());
        assertThat(p.service.regrade(p.actor,p.assessment,p.attempt,view.answerId(),command)).isEqualTo(receipt);
    }
    @Test void everyRecoveredRegradeReceiptBindingIsVerifiedBeforeReturningOldSuccess(){
        var view=scored();var command=new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),view.revision(),view.transcriptId(),"原重评回执不可换绑");
        var receipt=p.service.regrade(p.actor,p.assessment,p.attempt,view.answerId(),command);
        String json=p.jdbc.queryForObject("SELECT receipt_json FROM prepared_result_command WHERE assessment_id=? AND idempotency_key=?",String.class,p.assessment,command.idempotencyKey());
        for(var change:Map.<String,Object>of("answerId",UUID.randomUUID().toString(),"transcriptId",UUID.randomUUID().toString(),"revision",receipt.revision()+1).entrySet())
            rejectMutation("UPDATE prepared_result_command SET receipt_json=? WHERE assessment_id=? AND idempotency_key=?",new Object[]{p.damage(json,change.getKey(),change.getValue()),p.assessment,command.idempotencyKey()},
                ()->p.service.regrade(p.actor,p.assessment,p.attempt,view.answerId(),command),"原重评命令回执损坏");
        String input=p.f.m.jobs.get(receipt.jobId()).orElseThrow().inputJson();
        for(var change:Map.<String,Object>of("work.submission",UUID.randomUUID().toString(),"requestedBy",p.student,"reason","换掉的原因",
            "requestedRevision",receipt.revision()+1,"transcriptId",view.scoreId()).entrySet())
            rejectMutation("UPDATE prepared_job SET input_json=? WHERE id=?",new Object[]{p.damage(input,change.getKey(),change.getValue()),receipt.jobId()},
                ()->p.service.regrade(p.actor,p.assessment,p.attempt,view.answerId(),command),"原重评命令回执损坏");
        rejectMutation("UPDATE prepared_result_command SET request_json=? WHERE assessment_id=? AND idempotency_key=?",new Object[]{p.damage(encode(command),"reason","换掉的原因"),p.assessment,command.idempotencyKey()},
            ()->p.service.regrade(p.actor,p.assessment,p.attempt,view.answerId(),command),"原管理命令证明损坏");
        assertThat(p.service.regrade(p.actor,p.assessment,p.attempt,view.answerId(),command)).isEqualTo(receipt);
    }
    @Test void recoveredConfirmationCannotSubstituteAnotherAnswerScoreOrRevision(){
        var view=scored();var command=new PreparedProcessing.ConfirmCommand(UUID.randomUUID().toString(),view.revision(),view.scoreId(),"原核分回执不可换绑");
        var receipt=p.service.confirm(p.actor,p.assessment,p.attempt,view.answerId(),command);
        String json=p.jdbc.queryForObject("SELECT receipt_json FROM prepared_result_command WHERE assessment_id=? AND idempotency_key=?",String.class,p.assessment,command.idempotencyKey());
        for(var change:Map.<String,Object>of("answerId",UUID.randomUUID().toString(),"scoreId",view.transcriptId(),"revision",receipt.revision()+1).entrySet())
            rejectMutation("UPDATE prepared_result_command SET receipt_json=? WHERE assessment_id=? AND idempotency_key=?",new Object[]{p.damage(json,change.getKey(),change.getValue()),p.assessment,command.idempotencyKey()},
                ()->p.service.confirm(p.actor,p.assessment,p.attempt,view.answerId(),command),"原核分命令回执损坏");
        assertThat(p.service.confirm(p.actor,p.assessment,p.attempt,view.answerId(),command)).isEqualTo(receipt);assertThat(p.view(view.answerId()).confirmed()).isTrue();
    }
}
