package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.PreparedAttemptStartRepository;
import system.assessment.defense.prepared.processing.*;
import system.assessment.defense.prepared.retention.*;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind;

/** Real NEW MySQL/locks and canonical native original receipts. Lifecycle states,
 * provider content and OSS URLs are explicit fixtures, NOT a deletion executor,
 * cloud retirement, physical capture or final QA. No production cleanup is enabled. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAudioReadRetirementLiveTest {
    final PreparedReviewAudioLiveTest r=new PreparedReviewAudioLiveTest();
    @TempDir Path folder;
    @BeforeEach void setup()throws Exception {r.folder=folder;r.setup();}
    @AfterEach void cleanup(){
        r.f.jdbc.update("DELETE FROM prepared_retention_guard WHERE assessment_id=?",r.f.assessment);
        r.f.jdbc.update("DELETE FROM prepared_retention_command WHERE assessment_id=?",r.f.assessment);
        r.cleanup();
    }
    void mark(String audio,String results){
        r.f.jdbc.update("""
            INSERT INTO prepared_retention_guard(attempt_id,assessment_id,student_id,revision,held,audio_state,results_state,created_at,updated_at)
            VALUES (?,?,?,0,0,?,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE audio_state=VALUES(audio_state),results_state=VALUES(results_state),revision=revision+1,updated_at=CURRENT_TIMESTAMP(3)
            """,r.f.attempt,r.f.assessment,r.f.student,audio,results);
    }
    void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,int code){
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(code);
    }
    PreparedRetentionProtectionService protection(){var q=r.f.f.m.f.f.ends.q;
        return new PreparedRetentionProtectionService(q.f.access,new PreparedAttemptStartRepository(r.f.jdbc),r.f.jdbc,q.f.tx.getTransactionManager());}
    PreparedRetentionProtection.Command command(long revision,String action){
        return new PreparedRetentionProtection.Command(UUID.randomUUID().toString(),revision,action,"核验原录音读取边界，不执行清理");}

    @ParameterizedTest @ValueSource(strings={"RETIRING","RETIRED"})
    void retiredOrRetiringOriginalNeverStartsPrivateReadOrCreatesPlaybackAudit(String state){
        var before=r.f.f.status();mark(state,"RETAINED");
        denied(()->r.audio.preview(r.f.actor,r.f.assessment,r.f.attempt,r.answer),state.equals("RETIRED")?410:409);
        denied(()->r.f.service.reviewMedia(r.f.actor,r.f.assessment,r.f.attempt,r.answer),state.equals("RETIRED")?410:409);
        verifyNoInteractions(r.store);assertThat(r.count()).isZero();assertThat(r.f.f.status()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"RETAINED","RETIRED"})
    void resultRetirementNeverGrantsAudioRegardlessOfItsIndependentState(String audio){
        mark(audio,"RETIRING");denied(()->r.audio.preview(r.f.actor,r.f.assessment,r.f.attempt,r.answer),409);
        verifyNoInteractions(r.store);assertThat(r.count()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"RETIRING","RETIRED"})
    void retirementDuringPrivateIoSuppressesGeneratedUrlAndFinalAuthorization(String state)throws Exception {
        doAnswer(i->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();mark(state,"RETAINED");
            return new PreparedPrivateOssStore.Preview("https://private.invalid/?Signature=never-delivered",r.clock.instant().plusSeconds(300).toString());}).when(r.store).previewAnswer(any());
        denied(()->r.audio.preview(r.f.actor,r.f.assessment,r.f.attempt,r.answer),state.equals("RETIRED")?410:409);
        verify(r.store,times(1)).previewAnswer(any());assertThat(r.count()).isZero();assertThat(r.f.f.status().submitted()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"RETIRING","RETIRED"})
    void retirementDuringFailedPrivateIoIsNotMisreportedAsAnIntactFile(String state)throws Exception {
        doAnswer(i->{mark(state,"RETAINED");throw new java.io.IOException("explicit remote fixture unavailable");}).when(r.store).previewAnswer(any());
        denied(()->r.audio.preview(r.f.actor,r.f.assessment,r.f.attempt,r.answer),state.equals("RETIRED")?410:409);
        verify(r.store,times(1)).previewAnswer(any());assertThat(r.count()).isZero();
    }
    @Test void retainedAndManuallyProtectedOriginalRemainsReadableWithoutMutatingTheHold(){
        mark("RETAINED","RETAINED");assertThat(r.audio.preview(r.f.actor,r.f.assessment,r.f.attempt,r.answer).answerId()).isEqualTo(r.answer);
        var held=protection().execute(r.f.actor,r.f.student,r.f.assessment,r.f.attempt,command(0,"PROTECT"));
        var before=protection().status(r.f.actor,r.f.student,r.f.assessment,r.f.attempt);
        assertThat(r.audio.preview(r.f.actor,r.f.assessment,r.f.attempt,r.answer).answerId()).isEqualTo(r.answer);
        assertThat(protection().status(r.f.actor,r.f.student,r.f.assessment,r.f.attempt).latest()).isEqualTo(held);
        assertThat(protection().status(r.f.actor,r.f.student,r.f.assessment,r.f.attempt).revision()).isEqualTo(before.revision());
        assertThat(r.count()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"missing-guard","stale-release","receipt-hash"})
    void missingOrForgedManualProtectionStateFailsClosedBeforePrivateIo(String fault){
        var p=protection();p.execute(r.f.actor,r.f.student,r.f.assessment,r.f.attempt,command(0,"PROTECT"));
        var release=p.execute(r.f.actor,r.f.student,r.f.assessment,r.f.attempt,command(1,"RELEASE"));
        var held=p.execute(r.f.actor,r.f.student,r.f.assessment,r.f.attempt,command(2,"PROTECT"));
        r.f.f.m.f.f.ends.q.f.tx.executeWithoutResult(s->{
            switch(fault){
                case "missing-guard"->r.f.jdbc.update("DELETE FROM prepared_retention_guard WHERE attempt_id=?",r.f.attempt);
                case "stale-release"->r.f.jdbc.update("UPDATE prepared_retention_guard SET last_command_id=?,held=0 WHERE attempt_id=?",release.id(),r.f.attempt);
                case "receipt-hash"->r.f.jdbc.update("UPDATE prepared_retention_command SET receipt_sha256=REPEAT('f',64) WHERE id=?",held.id());
                default->throw new AssertionError();
            }
            assertThatThrownBy(()->r.audio.preview(r.f.actor,r.f.assessment,r.f.attempt,r.answer)).isInstanceOf(IllegalStateException.class);
            s.setRollbackOnly();
        });verifyNoInteractions(r.store);assertThat(r.count()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"RETIRING","RETIRED"})
    void backgroundTranscriptCannotReadOrPublishFromExitedAudioAndKeepsOriginalReceipt(String state){
        var lease=r.f.claim(Kind.TRANSCRIBE);var plan=r.f.service.plan(lease);var before=r.f.f.status();mark(state,"RETAINED");
        var jobBefore=r.f.f.m.jobs.get(lease.job().id()).orElseThrow();
        denied(()->r.f.service.plan(lease),state.equals("RETIRED")?410:409);
        denied(()->r.f.service.completeTranscript(lease,PreparedProcessingCodec.encode(r.f.transcript(plan)),r.f.asr),state.equals("RETIRED")?410:409);
        assertThat(r.f.f.m.jobs.get(lease.job().id()).orElseThrow()).isEqualTo(jobBefore);
        assertThat(r.f.count("prepared_processing_result")).isZero();assertThat(r.f.f.status()).isEqualTo(before);verifyNoInteractions(r.store);
    }
    @ParameterizedTest @ValueSource(strings={"RETIRING","RETIRED"})
    void audioExitPreservesVisibleResultTeacherReviewAndVersionCoherentReportWithoutFileIo(String state){
        for(String score:List.of("0","7.5","9")){r.f.transcribe(r.f.claim(Kind.TRANSCRIBE));r.f.score(r.f.claim(Kind.SCORE),score);}
        r.f.jdbc.update("UPDATE assessment SET show_result=1 WHERE id=?",r.f.assessment);var manager=r.f.f.m.f.f.ends.q.f.tx.getTransactionManager();
        var results=new PreparedStudentResultService(r.f.f.service,r.f.service,r.f.jdbc,manager);
        var teacher=new PreparedReviewSummaryService(r.f.f.service,r.f.service,r.f.jdbc,manager);
        var summary=results.summary(r.f.student,r.f.assessment);var report=results.report(r.f.student,r.f.assessment,summary.resultVersion());
        var review=r.f.service.reviewPage(r.f.actor,r.f.assessment,r.f.attempt,r.answer,null,20);var teachers=teacher.summary(r.f.actor,r.f.assessment,r.f.attempt);
        var anchor=r.f.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,r.f.attempt);
        mark(state,"RETAINED");
        assertThat(results.summary(r.f.student,r.f.assessment)).isEqualTo(summary);
        assertThat(results.report(r.f.student,r.f.assessment,summary.resultVersion())).isEqualTo(report);
        assertThat(results.exportReport(r.f.student,r.f.assessment,summary.resultVersion())).isEqualTo(report);
        assertThat(r.f.service.reviewPage(r.f.actor,r.f.assessment,r.f.attempt,r.answer,null,20)).isEqualTo(review);
        assertThat(teacher.summary(r.f.actor,r.f.assessment,r.f.attempt)).isEqualTo(teachers);
        assertThat(r.f.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,r.f.attempt)).isEqualTo(anchor);
        denied(()->r.audio.preview(r.f.actor,r.f.assessment,r.f.attempt,r.answer),state.equals("RETIRED")?410:409);
        verifyNoInteractions(r.store);assertThat(r.count()).isZero();
    }
    @Test void committedRetirementWinsAgainstWaitingPlaybackAuthorizationUnderOriginalParentLock()throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var readerStarted=new CountDownLatch(1);var tx=r.f.f.m.f.f.ends.q.f.tx;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var writer=pool.submit(()->tx.executeWithoutResult(s->{r.f.jdbc.queryForObject("SELECT id FROM assessment WHERE id=? FOR UPDATE",Integer.class,r.f.assessment);mark("RETIRING","RETAINED");locked.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("bounded fixture lock wait");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();var reader=pool.submit(()->{readerStarted.countDown();denied(()->r.audio.preview(r.f.actor,r.f.assessment,r.f.attempt,r.answer),409);});
            assertThat(readerStarted.await(10,TimeUnit.SECONDS)).isTrue();try{assertThatThrownBy(()->reader.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
            writer.get(10,TimeUnit.SECONDS);reader.get(10,TimeUnit.SECONDS);verifyNoInteractions(r.store);assertThat(r.count()).isZero();
        }finally{release.countDown();}
    }
    @Test void internalFenceRequiresActualOriginalTransactionAndRejectsInvalidOrForeignScope(){
        var guard=new PreparedOriginalAudioReadGuard(r.f.jdbc);
        assertThatThrownBy(()->guard.requireRetained(r.f.student,r.f.assessment,r.f.attempt)).isInstanceOf(IllegalStateException.class).hasMessageContaining("事务");
        assertThatThrownBy(()->new PreparedOriginalAudioReadGuard(null)).isInstanceOf(NullPointerException.class);
        r.f.f.m.f.f.ends.q.f.tx.executeWithoutResult(s->{
            for(int[] ids:new int[][]{{0,r.f.assessment},{r.f.student,0}})
                assertThatThrownBy(()->guard.requireRetained(ids[0],ids[1],r.f.attempt)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->guard.requireRetained(r.f.student,r.f.assessment,"bad")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->guard.requireRetained(r.f.student,Integer.MAX_VALUE,r.f.attempt)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->guard.requireRetained(r.f.student+1000000,r.f.assessment,r.f.attempt)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->guard.requireRetained(r.f.student,r.f.assessment,UUID.randomUUID().toString())).isInstanceOf(IllegalStateException.class);
            guard.requireRetained(r.f.student,r.f.assessment,r.f.attempt);
        });verifyNoInteractions(r.store);assertThat(r.count()).isZero();
    }
    @Test void anotherHistoricalOriginalExitDoesNotPoisonRetainedCurrentAudio(){
        String other=UUID.randomUUID().toString();var q=r.f.f.m.f.f.ends.q;
        r.f.jdbc.update("INSERT INTO prepared_attempt(id,record_id,assessment_id,student_id,attempt_no,paper_version_id,started_at) VALUES (?,?,?,?,2,?,?)",other,q.attempt.recordId(),r.f.assessment,r.f.student,q.attempt.paperVersionId(),java.sql.Timestamp.from(q.now.get()));
        r.f.jdbc.update("INSERT INTO prepared_retention_guard(attempt_id,assessment_id,student_id,audio_state,created_at,updated_at) VALUES (?,?,?,'RETIRED',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",other,r.f.assessment,r.f.student);
        assertThat(r.audio.preview(r.f.actor,r.f.assessment,r.f.attempt,r.answer).answerId()).isEqualTo(r.answer);assertThat(r.count()).isEqualTo(1);
    }
    @Test void aLaterManualHoldCannotRestoreAnExitedFileOrChangeItsPlaybackDecision(){
        mark("RETIRED","RETAINED");var held=protection().execute(r.f.actor,r.f.student,r.f.assessment,r.f.attempt,command(0,"PROTECT"));
        assertThat(held.audioState()).isEqualTo("RETIRED");assertThat(held.held()).isTrue();
        denied(()->r.audio.preview(r.f.actor,r.f.assessment,r.f.attempt,r.answer),410);verifyNoInteractions(r.store);assertThat(r.count()).isZero();
    }
    @Test void existingTranscriptBasedRegradeRemainsIndependentOfActualAudioFileIo(){
        for(int n=0;n<3;n++){r.f.transcribe(r.f.claim(Kind.TRANSCRIBE));r.f.score(r.f.claim(Kind.SCORE),"7");}
        var anchor=r.f.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,r.f.attempt);
        mark("RETIRED","RETAINED");var before=r.f.view(r.answer);
        var receipt=r.f.service.regrade(r.f.actor,r.f.assessment,r.f.attempt,r.answer,new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),before.revision(),before.transcriptId(),"复用保留的原转写核验标准"));
        var lease=r.f.claim(Kind.SCORE);assertThat(lease.job().id()).isEqualTo(receipt.jobId());var score=r.f.score(lease,"8");
        assertThat(r.f.view(r.answer).scoreId()).isEqualTo(score.id());assertThat(r.f.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,r.f.attempt)).isEqualTo(anchor);
        verifyNoInteractions(r.store);denied(()->r.audio.preview(r.f.actor,r.f.assessment,r.f.attempt,r.answer),410);
    }
}
