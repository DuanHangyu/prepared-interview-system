package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Own MySQL transactions and real local codecs. Private cloud is the explicitly labelled
 * SDK double inherited from the media fixture, not real OSS or physical-device acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedSubmissionLiveTest {
    final PreparedAnswerMediaLiveTest m=new PreparedAnswerMediaLiveTest();
    @TempDir Path folder;
    PreparedSubmissionService service;
    @BeforeEach void setup()throws Exception {
        m.folder=folder;m.setup();var f=m.f.f.ends.q.f;
        service=new PreparedSubmissionService(f.access,new PreparedStudentEligibilityRepository(f.jdbc),new PreparedAttemptStartRepository(f.jdbc),
            new PreparedQuestionGrantRepository(f.jdbc),new PreparedAnswerEndRepository(f.jdbc),m.service,m.jobs,f.jdbc,f.tx.getTransactionManager(),
            new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return m.f.f.ends.q.now.get();}});
    }
    @AfterEach void cleanup(){var f=m.f.f.ends.q.f;
        f.jdbc.update("DELETE FROM prepared_submission_command WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM prepared_submission_answer WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM prepared_submission WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM prepared_job_dependency WHERE assessment_id=? AND job_id IN (SELECT id FROM prepared_job WHERE assessment_id=? AND kind IN ('TRANSCRIBE','SCORE'))",f.assessment,f.assessment);
        f.jdbc.update("DELETE FROM prepared_job WHERE assessment_id=? AND kind IN ('TRANSCRIBE','SCORE')",f.assessment);m.cleanup();}
    PreparedSubmission.Summary status(){return service.status(m.f.f.ends.q.student,m.f.f.ends.q.f.assessment,m.f.f.ends.q.attempt.id());}
    PreparedSubmission.Summary submit(PreparedSubmission.Command cmd){return service.submit(m.f.f.ends.q.student,m.f.f.ends.q.f.assessment,m.f.f.ends.q.attempt.id(),cmd);}
    PreparedSubmission.Command command(){return new PreparedSubmission.Command(UUID.randomUUID().toString(),m.f.f.ends.attempt().revision());}
    void savedAll()throws Exception {
        m.stagedCloudDouble();
        for(int ordinal=1;ordinal<=3;ordinal++){
            if(ordinal>1)m.f.f.grant=m.f.f.ends.q.grant(ordinal);
            m.f.f.segment=UUID.randomUUID().toString();m.folder=Files.createDirectory(folder.resolve("q"+ordinal));
            var hash=m.nativeEnd();m.request(hash);new PreparedAnswerMediaWorker(m.jobs,m.service,m.runtime,m.f.f.store,m.f.f.policy).runOnce();
            assertThat(m.status().serverSaved()).isTrue();
        }
    }
    void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);}
    @Test void missingAndDeclarationOnlyAnswersGiveActionableStatusButNoReceiptOrProcessingJobs()throws Exception {
        var current=status();assertThat(current.phase()).isEqualTo("WAITING_ANSWERS");assertThat(current.submitted()).isFalse();assertThat(current.receipt()).isNull();assertThat(current.pending()).hasSize(3);
        for(int ordinal=1;ordinal<=3;ordinal++){if(ordinal>1)m.f.f.grant=m.f.f.ends.q.grant(ordinal);m.f.f.segment=UUID.randomUUID().toString();m.end();}
        assertThat(status().pending()).allMatch(p->p.phase().equals("UPLOAD_PENDING"));conflict(()->submit(command()));
        assertThat(m.f.f.ends.count("prepared_submission")).isZero();assertThat(m.f.f.ends.q.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=? AND kind IN ('TRANSCRIBE','SCORE')",Integer.class,m.f.f.ends.q.f.assessment)).isZero();
    }
    @Test void allRealCodecReceiptsSubmitOnceAndAtomicallyQueueOneTranscriptionAndScorePerOriginalAnswer()throws Exception {
        savedAll();assertThat(status().phase()).isEqualTo("READY");var command=command();var submitted=submit(command);
        assertThat(submitted.submitted()).isTrue();assertThat(submitted.phase()).isEqualTo("SUBMITTED");assertThat(submitted.receipt().answerCount()).isEqualTo(3);assertThat(submitted.pending()).isEmpty();
        for(int n=0;n<100;n++)assertThat(submit(command)).isEqualTo(submitted);
        assertThat(submit(new PreparedSubmission.Command(UUID.randomUUID().toString(),command.expectedRevision()))).isEqualTo(submitted);
        assertThat(status()).isEqualTo(submitted);assertThat(m.f.f.ends.count("prepared_submission")).isEqualTo(1);
        var jdbc=m.f.f.ends.q.f.jdbc;assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=? AND kind IN ('TRANSCRIBE','SCORE')",Integer.class,m.f.f.ends.q.f.assessment)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job_dependency WHERE assessment_id=?",Integer.class,m.f.f.ends.q.f.assessment)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT state FROM student_assessment_record WHERE assessment_id=?",Integer.class,m.f.f.ends.q.f.assessment)).isEqualTo(2);
        String projection=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(submitted);assertThat(projection).doesNotContain("rubric","maxScore","objectKey","url","captureClientId","inputJson");
        assertThat(m.jobs.claim(PreparedJobLedger.Kind.SCORE,"submission-fixture",300)).isEmpty();
        var lease=m.jobs.claim(PreparedJobLedger.Kind.TRANSCRIBE,"submission-fixture",300).orElseThrow();assertThat(lease.job().actorId()).isEqualTo(m.f.f.ends.q.student);
        assertThat(lease.job().inputJson()).contains("answer","paperVersion","mediaJob","submission").doesNotContain("url","rubric","objectKey");
    }
    @Test void sameKeyDifferentRevisionAndStaleFirstCommandAreConflictsNotNewSubmissions()throws Exception {
        savedAll();var command=command();conflict(()->submit(new PreparedSubmission.Command(command.idempotencyKey(),command.expectedRevision()-1)));
        var first=submit(command);conflict(()->submit(new PreparedSubmission.Command(command.idempotencyKey(),command.expectedRevision()+1)));assertThat(status()).isEqualTo(first);
    }
    @Test void parallelOriginalCommandsYieldOneReceiptAndOneOutbox()throws Exception {
        savedAll();var command=command();var latch=new CountDownLatch(1);var results=new ArrayList<Future<PreparedSubmission.Summary>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            for(int n=0;n<12;n++)results.add(pool.submit(()->{latch.await();return submit(command);}));latch.countDown();
            var original=results.getFirst().get(30,TimeUnit.SECONDS);for(var result:results)assertThat(result.get(30,TimeUnit.SECONDS)).isEqualTo(original);
        }
        assertThat(m.f.f.ends.count("prepared_submission")).isEqualTo(1);assertThat(m.f.f.ends.count("prepared_submission_command")).isEqualTo(1);
    }
    @Test void originalCommandRecoversConcurrentCommitEvenAfterAnEarlierRepeatableReadSnapshot()throws Exception {
        concurrentCommitAfterSnapshot(true);
    }
    @Test void currentJobProofCannotHoldLocksOutsideAnExplicitShortTransaction() {
        assertThatThrownBy(()->m.jobs.getForShare(UUID.randomUUID().toString())).isInstanceOf(IllegalStateException.class);
    }
    @Test void receiptQueryRecoversConcurrentCommitEvenAfterAnEarlierRepeatableReadSnapshot()throws Exception {
        concurrentCommitAfterSnapshot(false);
    }
    private void concurrentCommitAfterSnapshot(boolean retryCommand)throws Exception {
        savedAll();var command=command();var f=m.f.f.ends.q.f;
        assertThat(f.jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
        var snapshotCaptured=new CountDownLatch(1);var finalCommitted=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var recovery=pool.submit(()->f.tx.execute(s->{
                // Actual HTTP permission reads can establish this snapshot BEFORE the parent lock.
                // Another request commits the unique submission/outbox while this one waits.
                assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=? AND kind IN ('TRANSCRIBE','SCORE')",Integer.class,f.assessment)).isZero();
                snapshotCaptured.countDown();
                try {assertThat(finalCommitted.await(15,TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                return retryCommand?submit(command):status();
            }));
            assertThat(snapshotCaptured.await(15,TimeUnit.SECONDS)).isTrue();
            PreparedSubmission.Summary original;
            try {original=submit(command);}finally{finalCommitted.countDown();}
            assertThat(recovery.get(15,TimeUnit.SECONDS)).isEqualTo(original);
        }
        assertThat(m.f.f.ends.count("prepared_submission")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=? AND kind IN ('TRANSCRIBE','SCORE')",Integer.class,f.assessment)).isEqualTo(6);
    }
    @Test void receiptReadAndSubmitRecheckActualRoleAssignmentArchiveAndOriginalAttempt()throws Exception {
        savedAll();var command=command();submit(command);var q=m.f.f.ends.q;var jdbc=q.f.jdbc;
        assertThatThrownBy(()->service.status(q.student+1,q.f.assessment,q.attempt.id())).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThatThrownBy(()->service.status(q.student,q.f.assessment,UUID.randomUUID().toString())).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        jdbc.update("UPDATE `user` SET type=1 WHERE id=?",q.student);
        try{assertThatThrownBy(this::status).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);assertThatThrownBy(()->submit(command)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}finally{jdbc.update("UPDATE `user` SET type=0 WHERE id=?",q.student);}
        jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",q.f.assessment);try{assertThatThrownBy(this::status).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}finally{jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",q.f.assessment);}
    }
    @Test void savedFlagWithoutMediaProofOrCorruptedOriginalProofNeverCreatesFalseSubmission()throws Exception {
        savedAll();var jdbc=m.f.f.ends.q.f.jdbc;String answer=m.f.f.grant.answerId(),original=jdbc.queryForObject("SELECT proof_json FROM prepared_answer_media WHERE answer_id=?",String.class,answer);
        jdbc.update("UPDATE prepared_answer_media SET proof_json=NULL,verified_at=NULL WHERE answer_id=?",answer);
        assertThatThrownBy(()->submit(command())).isInstanceOf(IllegalStateException.class);assertThat(m.f.f.ends.count("prepared_submission")).isZero();
        jdbc.update("UPDATE prepared_answer_media SET proof_json=?,verified_at=CURRENT_TIMESTAMP(3) WHERE answer_id=?",m.damaged(original,new PreparedAnswerMediaLiveTest.Damage("audio.durationMs",0)),answer);
        assertThatThrownBy(this::status).isInstanceOf(IllegalStateException.class);assertThat(m.f.f.ends.count("prepared_submission")).isZero();
    }
    @Test void transactionFailureAfterOutboxInsertionRollsEverythingBackAndOriginalKeyCanRecover()throws Exception {
        savedAll();var command=command();var jdbc=m.f.f.ends.q.f.jdbc;
        // A scoped anchor mismatch makes the final compatible-state update fail after enqueue.
        jdbc.update("UPDATE student_assessment_record SET state=1 WHERE assessment_id=?",m.f.f.ends.q.f.assessment);
        conflict(()->submit(command));assertThat(m.f.f.ends.count("prepared_submission")).isZero();assertThat(m.f.f.ends.count("prepared_submission_command")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=? AND kind IN ('TRANSCRIBE','SCORE')",Integer.class,m.f.f.ends.q.f.assessment)).isZero();assertThat(m.f.f.ends.attempt().state()).isEqualTo("UPLOAD_PENDING");
        jdbc.update("UPDATE student_assessment_record SET state=0 WHERE assessment_id=?",m.f.f.ends.q.f.assessment);assertThat(submit(command).submitted()).isTrue();
    }
    @Test void missingAnswerPastFixedWindowCommitsReviewWithoutDeletingOrExtendingOriginals()throws Exception {
        for(int ordinal=1;ordinal<=3;ordinal++){if(ordinal>1)m.f.f.grant=m.f.f.ends.q.grant(ordinal);m.f.f.segment=UUID.randomUUID().toString();m.end();}
        var original=m.f.f.ends.attempt();m.f.f.ends.q.now.set(Instant.parse(original.supplementalUploadDeadline()));conflict(()->submit(command()));
        assertThat(m.f.f.ends.attempt().state()).isEqualTo("REQUIRES_REVIEW");assertThat(m.f.f.ends.attempt().supplementalUploadDeadline()).isEqualTo(original.supplementalUploadDeadline());assertThat(m.f.f.ends.count("prepared_answer_chunk")).isEqualTo(6);
    }
    @Test void completeOriginalsAndLostResponseRemainRecoverableAfterWindowWithoutNewRecordingOrModelCalls()throws Exception {
        savedAll();var command=command();var original=submit(command);m.f.f.ends.q.now.set(m.f.f.ends.q.now.get().plusSeconds(86400*30));assertThat(submit(command)).isEqualTo(original);assertThat(status()).isEqualTo(original);
    }
    @Test void corruptedFinalSnapshotAnswerBindingOrProcessingInputCannotReturnFalseFinalReceipt()throws Exception {
        savedAll();var original=submit(command());var f=m.f.f.ends.q.f;String id=original.receipt().id();
        for(String sql:List.of("UPDATE prepared_submission SET snapshot_sha256=REPEAT('a',64) WHERE id=?",
            "UPDATE prepared_submission SET submitted_revision=999 WHERE id=?","UPDATE prepared_submission SET answer_count=2 WHERE id=?",
            "UPDATE prepared_submission SET submitted_at='2020-01-01' WHERE id=?","UPDATE prepared_submission SET snapshot_json='{}' WHERE id=?",
            "DELETE FROM prepared_submission_answer WHERE submission_id=?")){
            f.tx.executeWithoutResult(s->{f.jdbc.update(sql,id);assertThatThrownBy(this::status).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        }
        String transcription=f.jdbc.queryForObject("SELECT transcribe_job_id FROM prepared_submission_answer WHERE answer_id=?",String.class,m.f.f.grant.answerId());
        for(String sql:List.of("UPDATE prepared_job SET kind='SCORE' WHERE id=?","UPDATE prepared_job SET input_version=999 WHERE id=?","UPDATE prepared_job SET input_json='{}' WHERE id=?","DELETE FROM prepared_job_dependency WHERE job_id=?")){
            f.tx.executeWithoutResult(s->{f.jdbc.update(sql,transcription);assertThatThrownBy(this::status).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
        }
        assertThat(status()).isEqualTo(original);
    }
    @Test void newStudentDependencyCannotMixBroadcastAndAnswerMediaOrCombineMultipleOriginalAnswers()throws Exception {
        savedAll();var q=m.f.f.ends.q;var f=q.f;var media=f.jdbc.queryForList("SELECT job_id FROM prepared_answer_media WHERE assessment_id=? ORDER BY answer_id",String.class,f.assessment);
        assertThatThrownBy(()->m.jobs.enqueueAfter(q.student,f.assessment,PreparedJobLedger.Kind.TRANSCRIBE,2,UUID.randomUUID().toString(),"{}",media)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        var broadcast=m.jobs.enqueue(q.student,f.assessment,PreparedJobLedger.Kind.ASSEMBLE,2,UUID.randomUUID().toString(),"{}");
        assertThatThrownBy(()->m.jobs.enqueueAfter(q.student,f.assessment,PreparedJobLedger.Kind.TRANSCRIBE,2,UUID.randomUUID().toString(),"{}",List.of(media.getFirst(),broadcast.id()))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        f.jdbc.update("DELETE FROM prepared_job WHERE id=?",broadcast.id());assertThat(m.f.f.ends.count("prepared_submission")).isZero();
    }
}
