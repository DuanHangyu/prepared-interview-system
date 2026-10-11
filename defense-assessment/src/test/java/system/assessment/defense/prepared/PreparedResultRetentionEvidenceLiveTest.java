package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.prepared.retention.*;
import system.assessment.defense.prepared.processing.*;
import system.assessment.defense.prepared.jobs.*;

import java.nio.file.Path;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.retention.PreparedAudioRetentionPolicy.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind;

/** Actual ORIGINAL MySQL evidence and locks when enabled. Existing native media,
 * synthetic grading and explicit SDK doubles are fixtures, NOT cloud/deletion/QA. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedResultRetentionEvidenceLiveTest {
    final PreparedRetentionEvidenceLiveTest e=new PreparedRetentionEvidenceLiveTest();
    @TempDir Path folder;
    @BeforeEach void setup()throws Exception {e.folder=folder;e.setup();}
    @AfterEach void cleanup(){e.cleanup();}
    PreparedResultRetentionPolicy.Decision inspect(){return e.service.inspectResults(e.p.student,e.p.assessment,e.p.attempt);}
    Instant due(){return PreparedYearRetentionPolicy.inspect(e.first(),e.first()).dueAt();}
    void completed(){e.completed();e.now.set(due());}
    void releasedGuard(){var protection=e.protection();protection.execute(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,e.command(0,"PROTECT"));protection.execute(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,e.command(1,"RELEASE"));}

    @Test void exactYearBoundaryUsesOriginalCompletedResultsAndNeverMutatesAnyLifecycleOrReadsRemoteBytes(){
        completed();var before=e.rows();var anchor=e.first();
        int remote=org.mockito.Mockito.mockingDetails(e.p.f.m.f.f.store).getInvocations().size();
        e.now.set(due().minusMillis(1));var prior=inspect();
        assertThat(prior.state()).isEqualTo(State.WITHIN_RETENTION);assertThat(prior.resultDeleteCandidate()).isFalse();
        e.now.set(due());var at=inspect();assertThat(at.state()).isEqualTo(State.DUE);
        assertThat(at.resultDueAt()).isEqualTo(due());assertThat(at.protections()).isEmpty();
        for(int n=0;n<5;n++)assertThat(inspect()).isEqualTo(at);
        assertThat(at.resultDeleteCandidate()).isTrue();assertThat(e.first()).isEqualTo(anchor);
        assertThat(e.rows()).isEqualTo(before);assertThat(org.mockito.Mockito.mockingDetails(e.p.f.m.f.f.store).getInvocations()).hasSize(remote);
    }
    @Test void audio180DayCandidateCannotExpireResultsOrReanchorThem(){
        e.completed();var first=e.first();assertThat(e.inspect().audioDeleteCandidate()).isTrue();
        var result=inspect();assertThat(result.state()).isEqualTo(State.WITHIN_RETENTION);
        assertThat(result.resultDueAt()).isEqualTo(due());assertThat(result.resultDeleteCandidate()).isFalse();
        assertThat(e.first()).isEqualTo(first);
    }
    @Test void originalPendingResultsAndMissingAnchorRemainProtectedWithoutInventingCompletion(){
        var before=e.rows();assertThat(inspect().protections()).contains(Protection.NO_COMPLETION_ANCHOR,Protection.RESULTS_INCOMPLETE,Protection.PROCESSING);
        assertThat(inspect().resultDueAt()).isNull();assertThat(e.rows()).isEqualTo(before);
        completed();e.rollback(()->{e.p.jdbc.update("UPDATE prepared_attempt SET first_results_completed_at=NULL WHERE id=?",e.p.attempt);
            assertThat(inspect().protections()).contains(Protection.NO_COMPLETION_ANCHOR);assertThat(inspect().resultDeleteCandidate()).isFalse();});
    }
    @Test void completedRegradeCannotResetTheFirstAnchorAndUnfinishedRegradeOrCostRemainProtected(){
        completed();var first=e.first();String answer=e.answer();var view=e.p.view(answer);
        e.p.service.regrade(e.p.actor,e.p.assessment,e.p.attempt,answer,
            new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),view.revision(),view.transcriptId(),"年度留存原评分核验"));
        var lease=e.p.claim(Kind.SCORE);String budget="prepared-retention-evidence-"+UUID.randomUUID(),call=UUID.randomUUID().toString();e.budgets.add(budget);
        e.p.f.m.jobs.initializeBudget(budget,new BigDecimal("100"),new BigDecimal("80"));
        assertThat(e.p.f.m.jobs.reserve(lease,budget,call,BigDecimal.ONE).approved()).isTrue();
        var before=e.p.jdbc.queryForMap("SELECT * FROM prepared_budget WHERE scope_id=?",budget);
        assertThat(inspect().protections()).contains(Protection.PROCESSING,Protection.UNRESOLVED_COST);
        assertThat(e.p.jdbc.queryForMap("SELECT * FROM prepared_budget WHERE scope_id=?",budget)).isEqualTo(before);
        assertThat(e.first()).isEqualTo(first);assertThat(e.p.f.m.jobs.cancelBeforeDispatch(call)).isTrue();e.p.score(lease,"8");
        assertThat(inspect().resultDeleteCandidate()).isTrue();assertThat(inspect().resultDueAt()).isEqualTo(due());assertThat(e.first()).isEqualTo(first);
    }
    @Test void latestManualHoldProtectsAndReleasingItDoesNotCancelOtherPendingWork(){
        completed();var protection=e.protection();protection.execute(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,e.command(0,"PROTECT"));
        assertThat(inspect().protections()).contains(Protection.EXPLICIT_HOLD);
        protection.execute(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,e.command(1,"RELEASE"));assertThat(inspect().resultDeleteCandidate()).isTrue();
        var view=e.p.view(e.answer());e.p.service.regrade(e.p.actor,e.p.assessment,e.p.attempt,e.answer(),
            new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),view.revision(),view.transcriptId(),"待处理不由解除保护取消"));
        assertThat(inspect().protections()).contains(Protection.PROCESSING).doesNotContain(Protection.EXPLICIT_HOLD);
        assertThat(inspect().resultDeleteCandidate()).isFalse();
    }
    @Test void aStaleReleasePointerCannotHideTheLatestOriginalProtection(){
        completed();var protection=e.protection();protection.execute(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,e.command(0,"PROTECT"));
        var release=protection.execute(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,e.command(1,"RELEASE"));
        protection.execute(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,e.command(2,"PROTECT"));
        e.rollback(()->{e.p.jdbc.update("UPDATE prepared_retention_guard SET last_command_id=?,held=0 WHERE attempt_id=?",release.id(),e.p.attempt);
            assertThatThrownBy(this::inspect).isInstanceOf(IllegalStateException.class);});
        assertThat(inspect().protections()).contains(Protection.EXPLICIT_HOLD);
    }
    @Test void ongoingOriginalIoIsNotReleasedByCalendarExpiryServiceRestartOrClockAdvance(){
        completed();var media=e.p.service.reviewMedia(e.p.actor,e.p.assessment,e.p.attempt,e.answer());
        var handle=e.p.service.beginReviewAudioIo(e.p.actor,e.p.assessment,e.p.attempt,e.answer(),media);
        e.now.set(e.now.get().plusSeconds(30L*86400));var before=e.rows();
        var other=new PreparedRetentionEvidenceService(e.p.f.service,e.p.service,e.p.jdbc,e.p.f.m.f.f.ends.q.f.tx.getTransactionManager(),Clock.fixed(e.now.get(),ZoneOffset.UTC));
        assertThat(other.inspectResults(e.p.student,e.p.assessment,e.p.attempt).protections()).contains(Protection.ACTIVE_AUDIO_IO);
        assertThat(inspect().resultDeleteCandidate()).isFalse();assertThat(e.rows()).isEqualTo(before);
        e.p.service.endAudioIo(handle);assertThat(inspect().resultDeleteCandidate()).isTrue();
    }
    @Test void issuedOriginalReadAuthorizationProtectsResultsUntilExactExpiry(){
        completed();String answer=e.answer();var expires=e.now.get().plusSeconds(300);
        e.p.service.recordAudioAccess(e.p.actor,e.p.assessment,e.p.attempt,answer,e.p.service.reviewMedia(e.p.actor,e.p.assessment,e.p.attempt,answer),expires);
        assertThat(inspect().protections()).contains(Protection.ACTIVE_READ_AUTHORIZATION);
        e.now.set(expires.minusMillis(1));assertThat(inspect().resultDeleteCandidate()).isFalse();
        e.now.set(expires);assertThat(inspect().resultDeleteCandidate()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"audio-retiring","results-retiring"})
    void incompleteRetirementCannotBeAnOrdinaryResultCandidate(String lifecycle){
        completed();releasedGuard();e.rollback(()->{
            switch(lifecycle){
                case "audio-retiring"->e.p.jdbc.update("UPDATE prepared_retention_guard SET audio_state='RETIRING',revision=revision+1 WHERE attempt_id=?",e.p.attempt);
                case "results-retiring"->e.p.jdbc.update("UPDATE prepared_retention_guard SET results_state='RETIRING',revision=revision+1 WHERE attempt_id=?",e.p.attempt);
                default->throw new AssertionError();
            }
            assertThat(inspect().protections()).contains(Protection.UNVERIFIED_BINDINGS);assertThat(inspect().resultDeleteCandidate()).isFalse();
        });assertThat(inspect().resultDeleteCandidate()).isTrue();
    }
    @Test void unimplementedResultExitCannotBeFabricatedByBypassingTheExistingDatabaseConstraint(){
        completed();releasedGuard();var before=e.rows();
        // Result content exit is not implemented. The current schema intentionally
        // rejects RETIRED; do not relax it merely to make a candidate fixture valid.
        assertThatThrownBy(()->e.p.jdbc.update("UPDATE prepared_retention_guard SET results_state='RETIRED',revision=revision+1 WHERE attempt_id=?",e.p.attempt))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(e.rows()).isEqualTo(before);assertThat(inspect().resultDeleteCandidate()).isTrue();
    }
    @Test void retiredAudioDoesNotEraseTheRetainedResultsOriginOrRequireReadingDeletedAudio(){
        completed();releasedGuard();e.p.jdbc.update("UPDATE prepared_retention_guard SET audio_state='RETIRED',revision=revision+1 WHERE attempt_id=?",e.p.attempt);
        // Structural DB lifecycle fixture only, NOT real object/content-copy exit.
        var before=e.rows();int remote=org.mockito.Mockito.mockingDetails(e.p.f.m.f.f.store).getInvocations().size();
        assertThat(e.inspect().audioDeleteCandidate()).isFalse();assertThat(inspect().resultDeleteCandidate()).isTrue();
        assertThat(inspect().resultDueAt()).isEqualTo(due());assertThat(e.rows()).isEqualTo(before);
        assertThat(org.mockito.Mockito.mockingDetails(e.p.f.m.f.f.store).getInvocations()).hasSize(remote);
    }
    @ParameterizedTest @ValueSource(strings={"submission-hash","media-proof","result-hash","result-input","head-missing","record-binding","anchor-missing","task-binding"})
    void corruptOriginalProofCannotBeReplacedByAnOldEnoughTimestamp(String fault){
        completed();e.rollback(()->{
            switch(fault){
                case "submission-hash"->e.p.jdbc.update("UPDATE prepared_submission SET snapshot_sha256=REPEAT('a',64) WHERE attempt_id=?",e.p.attempt);
                case "media-proof"->e.p.jdbc.update("UPDATE prepared_answer_media SET proof_json='{}' WHERE answer_id=?",e.answer());
                case "result-hash"->e.p.jdbc.update("UPDATE prepared_processing_result SET content_sha256=REPEAT('a',64) WHERE answer_id=? AND kind='SCORE'",e.answer());
                case "result-input"->e.p.jdbc.update("UPDATE prepared_processing_result SET input_json=NULL WHERE answer_id=? AND kind='TRANSCRIBE'",e.answer());
                case "head-missing"->e.p.jdbc.update("DELETE FROM prepared_processing_head WHERE answer_id=?",e.answer());
                case "record-binding"->e.p.jdbc.update("UPDATE student_assessment_record SET state=0 WHERE assessment_id=?",e.p.assessment);
                case "anchor-missing"->e.p.jdbc.update("DELETE FROM prepared_attempt_anchor WHERE assessment_id=? AND student_id=?",e.p.assessment,e.p.student);
                case "task-binding"->e.p.jdbc.update("UPDATE prepared_job SET input_json='{}' WHERE id=(SELECT transcribe_job_id FROM prepared_submission_answer WHERE answer_id=?)",e.answer());
                default->throw new AssertionError();
            }
            assertThatThrownBy(this::inspect).isInstanceOf(RuntimeException.class);
        });assertThat(inspect().resultDeleteCandidate()).isTrue();
    }
    @Test void unrelatedTeacherTtsIsNotStudentWorkButUnregisteredStudentTaskCannotBeIgnored(){
        completed();var teacher=e.p.f.m.jobs.enqueue(e.p.actor,e.p.assessment,Kind.TTS,1,UUID.randomUUID().toString(),"{}");
        assertThat(inspect().resultDeleteCandidate()).isTrue();
        var unbound=e.p.f.m.jobs.enqueue(e.p.student,e.p.assessment,Kind.SCORE,1,UUID.randomUUID().toString(),"{}");
        assertThat(inspect().protections()).contains(Protection.UNVERIFIED_BINDINGS);assertThat(inspect().resultDeleteCandidate()).isFalse();
        e.p.jdbc.update("DELETE FROM prepared_job WHERE id=?",unbound.id());e.p.jdbc.update("DELETE FROM prepared_job WHERE id=?",teacher.id());
    }
    @Test void originalArchivedOrHistoricalDataIsVerifiedWithoutRevivingStudentOrTeacherPermissions(){
        completed();String active=UUID.randomUUID().toString();var q=e.p.f.m.f.f.ends.q;
        e.p.jdbc.update("INSERT INTO prepared_attempt(id,record_id,assessment_id,student_id,attempt_no,paper_version_id,started_at) VALUES (?,?,?,?,2,?,?)",
            active,q.attempt.recordId(),e.p.assessment,e.p.student,q.attempt.paperVersionId(),java.sql.Timestamp.from(q.now.get()));
        e.p.jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",active,e.p.assessment,e.p.student);
        e.p.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",e.p.assessment,e.p.student);
        e.p.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",e.p.actor);e.p.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",e.p.assessment);
        try{assertThat(inspect().resultDeleteCandidate()).isTrue();assertThatThrownBy(()->e.p.view(e.answer())).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->e.p.f.status()).isInstanceOf(RuntimeException.class);}
        finally{e.p.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",e.p.actor);e.p.jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",e.p.assessment);}
    }
    @Test void candidateSharesTheOriginalParentLockWithManualProtectionAndCannotReturnAStaleHold(){
        completed();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=e.p.f.m.f.f.ends.q.f.tx;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var writer=pool.submit(()->tx.executeWithoutResult(s->{e.p.jdbc.queryForObject("SELECT id FROM assessment WHERE id=? FOR UPDATE",Integer.class,e.p.assessment);
                e.protection().execute(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,e.command(0,"PROTECT"));entered.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("bounded original lock wait");}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var reader=pool.submit(this::inspect);
            try{assertThatThrownBy(()->reader.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
            writer.get(10,TimeUnit.SECONDS);assertThat(reader.get(10,TimeUnit.SECONDS).protections()).contains(Protection.EXPLICIT_HOLD);
        }catch(Exception failure){throw new AssertionError(failure);}finally{release.countDown();}
    }
    @Test void invalidOrForeignScopeCannotBeResolvedFromTheCurrentAttemptPointer(){
        completed();for(String wrong:List.of(UUID.randomUUID().toString(),"bad"))
            assertThatThrownBy(()->e.service.inspectResults(e.p.student,e.p.assessment,wrong)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->e.service.inspectResults(e.p.student+1,e.p.assessment,e.p.attempt)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->e.service.inspectResults(0,e.p.assessment,e.p.attempt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->e.service.inspectResults(e.p.student,0,e.p.attempt)).isInstanceOf(IllegalArgumentException.class);
        assertThat(inspect().resultDeleteCandidate()).isTrue();
    }
}
