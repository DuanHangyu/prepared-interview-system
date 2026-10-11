package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.processing.*;
import system.assessment.defense.prepared.retention.*;
import system.assessment.defense.prepared.jobs.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.retention.PreparedAudioRetentionPolicy.*;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind;

/** Real NEW MySQL, original submission/media/provenance and locking. Native audio
 * and synthetic grading reuse existing fixtures; OSS remains an explicit SDK double.
 * No physical device, cloud bytes, paid model, retirement or final QA claim. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedRetentionEvidenceLiveTest {
    final PreparedProcessingLiveTest p=new PreparedProcessingLiveTest();
    @TempDir Path folder;
    final java.util.concurrent.atomic.AtomicReference<Instant> now=new java.util.concurrent.atomic.AtomicReference<>();
    final List<String> budgets=new ArrayList<>();
    PreparedRetentionEvidenceService service;
    @BeforeEach void setup()throws Exception {
        p.folder=folder;p.setup();var q=p.f.m.f.f.ends.q;
        service=new PreparedRetentionEvidenceService(p.f.service,p.service,p.jdbc,q.f.tx.getTransactionManager(),
            new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}});
        now.set(q.now.get().plusSeconds(200L*86400));
    }
    @AfterEach void cleanup(){
        for(String budget:budgets){p.jdbc.update("DELETE FROM prepared_budget_event WHERE scope_id=?",budget);p.jdbc.update("DELETE FROM prepared_model_call WHERE scope_id=?",budget);p.jdbc.update("DELETE FROM prepared_budget WHERE scope_id=?",budget);}
        p.jdbc.update("DELETE FROM prepared_retention_guard WHERE assessment_id=?",p.assessment);
        p.jdbc.update("DELETE FROM prepared_retention_command WHERE assessment_id=?",p.assessment);p.cleanup();
    }
    Decision inspect(){return service.inspect(p.student,p.assessment,p.attempt);}
    void completed(){for(int n=0;n<3;n++){p.transcribe(p.claim(Kind.TRANSCRIBE));p.score(p.claim(Kind.SCORE),n==0?"0":"7.5");}now.set(first().plusSeconds(180L*86400));}
    Instant first(){return p.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,p.attempt).toInstant();}
    String answer(){return p.jdbc.queryForObject("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal LIMIT 1",String.class,p.assessment);}
    PreparedRetentionProtectionService protection(){var q=p.f.m.f.f.ends.q;return new PreparedRetentionProtectionService(q.f.access,new PreparedAttemptStartRepository(p.jdbc),p.jdbc,q.f.tx.getTransactionManager(),Clock.fixed(now.get(),ZoneOffset.UTC));}
    PreparedRetentionProtection.Command command(long revision,String action){return new PreparedRetentionProtection.Command(UUID.randomUUID().toString(),revision,action,"原尝试留存核验，不修改答题");}
    void rollback(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){p.f.m.f.f.ends.q.f.tx.executeWithoutResult(s->{try{action.call();}catch(Throwable error){throw new AssertionError(error);}finally{s.setRollbackOnly();}});}
    Map<String,List<Map<String,Object>>> rows(){var data=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:List.of("prepared_attempt","prepared_answer","prepared_submission","prepared_submission_command","prepared_submission_answer","prepared_answer_media","prepared_processing_result","prepared_processing_head","prepared_retention_guard","prepared_retention_command","prepared_job","prepared_audit"))data.put(table,p.jdbc.queryForList("SELECT * FROM "+table+" WHERE assessment_id=?",p.assessment));return data;}

    @Test void exactBoundaryUsesVerifiedOriginalsAndZeroGradeAndReadsNeverMutateAnyLifecycle(){
        completed();var before=rows();int remoteInteractions=org.mockito.Mockito.mockingDetails(p.f.m.f.f.store).getInvocations().size();now.set(first().plusSeconds(180L*86400).minusMillis(1));
        assertThat(inspect().state()).isEqualTo(State.WITHIN_RETENTION);assertThat(inspect().audioDeleteCandidate()).isFalse();
        now.set(first().plusSeconds(180L*86400));var due=inspect();assertThat(due.state()).isEqualTo(State.DUE);assertThat(due.audioDueAt()).isEqualTo(now.get());assertThat(due.protections()).isEmpty();
        for(int n=0;n<10;n++)assertThat(inspect()).isEqualTo(due);assertThat(rows()).isEqualTo(before);assertThat(org.mockito.Mockito.mockingDetails(p.f.m.f.f.store).getInvocations()).hasSize(remoteInteractions);
    }
    @Test void pendingResultsAndMissingCompletionAnchorAreProtectedWithoutInventingCompletion(){
        var before=rows();assertThat(inspect().protections()).contains(Protection.NO_COMPLETION_ANCHOR,Protection.RESULTS_INCOMPLETE,Protection.PROCESSING);assertThat(rows()).isEqualTo(before);
        completed();rollback(()->{p.jdbc.update("UPDATE prepared_attempt SET first_results_completed_at=NULL WHERE id=?",p.attempt);assertThat(inspect().protections()).contains(Protection.NO_COMPLETION_ANCHOR);});
    }
    @Test void historicalArchivedAndRevokedIdentitiesDoNotPreventInternalOriginalProofOrRevivePublicPermissions(){
        completed();String active=UUID.randomUUID().toString();var q=p.f.m.f.f.ends.q;
        p.jdbc.update("INSERT INTO prepared_attempt(id,record_id,assessment_id,student_id,attempt_no,paper_version_id,started_at) VALUES (?,?,?,?,2,?,?)",active,q.attempt.recordId(),p.assessment,p.student,q.attempt.paperVersionId(),java.sql.Timestamp.from(q.now.get()));
        p.jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",active,p.assessment,p.student);
        p.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",p.assessment,p.student);
        p.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",p.actor);p.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",p.assessment);
        try{assertThat(inspect().audioDeleteCandidate()).isTrue();assertThatThrownBy(()->p.service.review(p.actor,p.assessment,p.attempt,answer())).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->p.f.status()).isInstanceOf(RuntimeException.class);}
        finally{p.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",p.actor);p.jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",p.assessment);}
    }
    @ParameterizedTest @ValueSource(strings={"submission-hash","media-proof","result-hash","result-input","head-missing","record-binding","anchor-missing","task-binding"})
    void corruptOriginalProofNeverProducesAnEligibleCandidate(String fault){
        completed();rollback(()->{
            switch(fault){
                case "submission-hash"->p.jdbc.update("UPDATE prepared_submission SET snapshot_sha256=REPEAT('a',64) WHERE attempt_id=?",p.attempt);
                case "media-proof"->p.jdbc.update("UPDATE prepared_answer_media SET proof_json='{}' WHERE answer_id=?",answer());
                case "result-hash"->p.jdbc.update("UPDATE prepared_processing_result SET content_sha256=REPEAT('a',64) WHERE answer_id=? AND kind='SCORE'",answer());
                case "result-input"->p.jdbc.update("UPDATE prepared_processing_result SET input_json=NULL WHERE answer_id=? AND kind='TRANSCRIBE'",answer());
                case "head-missing"->p.jdbc.update("DELETE FROM prepared_processing_head WHERE answer_id=?",answer());
                case "record-binding"->p.jdbc.update("UPDATE student_assessment_record SET state=0 WHERE assessment_id=?",p.assessment);
                case "anchor-missing"->p.jdbc.update("DELETE FROM prepared_attempt_anchor WHERE assessment_id=? AND student_id=?",p.assessment,p.student);
                case "task-binding"->p.jdbc.update("UPDATE prepared_job SET input_json='{}' WHERE id=(SELECT transcribe_job_id FROM prepared_submission_answer WHERE answer_id=?)",answer());
                default->throw new AssertionError();
            }
            assertThatThrownBy(this::inspect).isInstanceOf(RuntimeException.class);
        });assertThat(inspect().audioDeleteCandidate()).isTrue();
    }
    @Test void activeRegradeAndUncertainCostsRemainProtectedWithoutChangingSharedBudget(){
        completed();String answer=answer();var view=p.view(answer);var request=p.service.regrade(p.actor,p.assessment,p.attempt,answer,new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),view.revision(),view.transcriptId(),"核验保留期间的原评分"));
        var lease=p.claim(Kind.SCORE);assertThat(lease.job().id()).isEqualTo(request.jobId());String budget="prepared-retention-evidence-"+UUID.randomUUID(),call=UUID.randomUUID().toString();budgets.add(budget);
        p.f.m.jobs.initializeBudget(budget,new BigDecimal("100"),new BigDecimal("80"));assertThat(p.f.m.jobs.reserve(lease,budget,call,BigDecimal.ONE).approved()).isTrue();
        var budgetBefore=p.jdbc.queryForMap("SELECT * FROM prepared_budget WHERE scope_id=?",budget);var callBefore=p.jdbc.queryForMap("SELECT * FROM prepared_model_call WHERE id=?",call);
        assertThat(inspect().protections()).contains(Protection.PROCESSING,Protection.UNRESOLVED_COST);
        assertThat(p.jdbc.queryForMap("SELECT * FROM prepared_budget WHERE scope_id=?",budget)).isEqualTo(budgetBefore);assertThat(p.jdbc.queryForMap("SELECT * FROM prepared_model_call WHERE id=?",call)).isEqualTo(callBefore);
        assertThat(p.f.m.jobs.cancelBeforeDispatch(call)).isTrue();p.score(lease,"8");assertThat(inspect().audioDeleteCandidate()).isTrue();
    }
    @Test void manualProtectionReleaseCannotCancelProcessingAndCorruptGuardCannotLookUnprotected(){
        completed();var protections=protection();var protectedReceipt=protections.execute(p.actor,p.student,p.assessment,p.attempt,command(0,"PROTECT"));
        assertThat(inspect().protections()).contains(Protection.EXPLICIT_HOLD);assertThat(inspect().audioDeleteCandidate()).isFalse();
        rollback(()->{p.jdbc.update("UPDATE prepared_retention_guard SET last_command_id=NULL,held=0 WHERE attempt_id=?",p.attempt);assertThatThrownBy(this::inspect).isInstanceOf(IllegalStateException.class);});
        rollback(()->{p.jdbc.update("UPDATE prepared_retention_command SET receipt_sha256=REPEAT('a',64) WHERE id=?",protectedReceipt.id());assertThatThrownBy(this::inspect).isInstanceOf(IllegalStateException.class);});
        protections.execute(p.actor,p.student,p.assessment,p.attempt,command(1,"RELEASE"));assertThat(inspect().audioDeleteCandidate()).isTrue();
        var v=p.view(answer());p.service.regrade(p.actor,p.assessment,p.attempt,answer(),new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),v.revision(),v.transcriptId(),"仍需处理"));assertThat(inspect().protections()).contains(Protection.PROCESSING);
    }
    @Test void staleReleasePointerCannotHideALaterOriginalProtectionFromEitherReader(){
        completed();var protections=protection();protections.execute(p.actor,p.student,p.assessment,p.attempt,command(0,"PROTECT"));
        var release=protections.execute(p.actor,p.student,p.assessment,p.attempt,command(1,"RELEASE"));
        protections.execute(p.actor,p.student,p.assessment,p.attempt,command(2,"PROTECT"));assertThat(inspect().protections()).contains(Protection.EXPLICIT_HOLD);
        rollback(()->{
            p.jdbc.update("UPDATE prepared_retention_guard SET last_command_id=?,held=0 WHERE attempt_id=?",release.id(),p.attempt);
            assertThatThrownBy(this::inspect).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->protections.status(p.actor,p.student,p.assessment,p.attempt)).isInstanceOf(IllegalStateException.class);
        });assertThat(inspect().protections()).contains(Protection.EXPLICIT_HOLD);
    }
    @Test void existingReadAuthorizationProtectsUntilExactExpiryIncludingChangedTeacherRole(){
        completed();String answer=answer();var media=p.service.reviewMedia(p.actor,p.assessment,p.attempt,answer);p.service.recordAudioAccess(p.actor,p.assessment,p.attempt,answer,media,now.get().plusSeconds(300));
        assertThat(inspect().protections()).contains(Protection.ACTIVE_READ_AUTHORIZATION);p.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",p.actor);
        try{now.set(now.get().plusSeconds(300).minusMillis(1));assertThat(inspect().audioDeleteCandidate()).isFalse();now.set(now.get().plusMillis(1));assertThat(inspect().audioDeleteCandidate()).isTrue();}
        finally{p.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",p.actor);}
    }
    @Test void unknownUnboundJobAndRetirementFixtureNeverBecomeCandidateAndSharedTeacherJobsAreExcluded(){
        completed();var other=p.f.m.jobs.enqueue(p.actor,p.assessment,Kind.TTS,1,UUID.randomUUID().toString(),"{}");assertThat(inspect().audioDeleteCandidate()).isTrue();
        var unbound=p.f.m.jobs.enqueue(p.student,p.assessment,Kind.SCORE,1,UUID.randomUUID().toString(),"{}");assertThat(inspect().audioDeleteCandidate()).isFalse();p.jdbc.update("DELETE FROM prepared_job WHERE id=?",unbound.id());
        var protect=protection();protect.execute(p.actor,p.student,p.assessment,p.attempt,command(0,"PROTECT"));protect.execute(p.actor,p.student,p.assessment,p.attempt,command(1,"RELEASE"));
        for(String column:List.of("audio_state","results_state"))rollback(()->{p.jdbc.update("UPDATE prepared_retention_guard SET "+column+"='RETIRING',revision=revision+1 WHERE attempt_id=?",p.attempt);assertThat(inspect().audioDeleteCandidate()).isFalse();});
        p.jdbc.update("DELETE FROM prepared_job WHERE id=?",other.id());assertThat(inspect().audioDeleteCandidate()).isTrue();
    }
    @Test void parentLockSerializesCandidateWithProtectionAndNeitherReturnsStaleHold(){
        completed();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=p.f.m.f.f.ends.q.f.tx;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var writer=pool.submit(()->tx.executeWithoutResult(s->{p.jdbc.queryForObject("SELECT id FROM assessment WHERE id=? FOR UPDATE",Integer.class,p.assessment);protection().execute(p.actor,p.student,p.assessment,p.attempt,command(0,"PROTECT"));entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("bounded own lock wait");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var reader=pool.submit(this::inspect);try{assertThatThrownBy(()->reader.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
            writer.get(10,TimeUnit.SECONDS);assertThat(reader.get(10,TimeUnit.SECONDS).protections()).contains(Protection.EXPLICIT_HOLD);
        }catch(Exception failure){throw new AssertionError(failure);}finally{release.countDown();}
    }
    @Test void invalidOrForeignOriginalScopeCannotBeResolvedFromCurrentPointer(){
        completed();for(String wrong:List.of(UUID.randomUUID().toString(),"bad"))assertThatThrownBy(()->service.inspect(p.student,p.assessment,wrong)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.inspect(p.student+1,p.assessment,p.attempt)).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->service.inspect(0,p.assessment,p.attempt)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.inspect(p.student,0,p.attempt)).isInstanceOf(IllegalArgumentException.class);assertThat(inspect().audioDeleteCandidate()).isTrue();
    }
    String[] otherOriginal(){
        String attempt=UUID.randomUUID().toString(),answer=UUID.randomUUID().toString();var q=p.f.m.f.f.ends.q;
        p.jdbc.update("INSERT INTO prepared_attempt(id,record_id,assessment_id,student_id,attempt_no,paper_version_id,started_at) VALUES (?,?,?,?,2,?,?)",attempt,q.attempt.recordId(),p.assessment,p.student,q.attempt.paperVersionId(),java.sql.Timestamp.from(q.now.get()));
        p.jdbc.update("""
            INSERT INTO prepared_answer(id,assessment_id,student_id,attempt_id,paper_version_id,question_id,ordinal,capture_client_id,answer_seconds,state,revision,started_at,answer_deadline)
            SELECT ?,assessment_id,student_id,?,paper_version_id,question_id,ordinal,capture_client_id,answer_seconds,'RECORDING',0,started_at,answer_deadline FROM prepared_answer WHERE id=?
            """,answer,attempt,answer());return new String[]{attempt,answer};
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void merelyClaimingAnExistingOtherAnswerCannotHideAnUnregisteredJobOrOverflowItsScope(boolean overflow){
        completed();rollback(()->{
            String[] other=otherOriginal();String id=p.jdbc.queryForObject("SELECT transcribe_job_id FROM prepared_submission_answer WHERE answer_id=?",String.class,answer());
            var work=PreparedProcessingCodec.decode(p.f.m.jobs.get(id).orElseThrow().inputJson(),PreparedSubmission.Work.class);
            var foreign=new PreparedSubmission.Work(work.schemaVersion(),p.student,p.assessment,other[0],work.paperVersion(),work.submission(),other[1],work.question(),work.ordinal(),work.answerRevision(),work.manifestSha256(),work.mediaJob(),work.mediaProofSha256());
            String input=PreparedProcessingCodec.encode(foreign);if(overflow)input=p.damage(input,"student",p.student+(1L<<32));
            p.f.m.jobs.enqueue(p.student,p.assessment,Kind.SCORE,work.answerRevision(),UUID.randomUUID().toString(),input);
            assertThat(inspect().audioDeleteCandidate()).isFalse();
        });assertThat(inspect().audioDeleteCandidate()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"settled-missing-cost","cancelled-with-cost","future-generation","forged-settled-disposition"})
    void malformedCostDispositionCannotMasqueradeAsReconciledAndDoesNotReleaseBudget(String fault){
        var lease=p.claim(Kind.TRANSCRIBE);String budget="prepared-retention-evidence-"+UUID.randomUUID(),call=UUID.randomUUID().toString();budgets.add(budget);
        p.f.m.jobs.initializeBudget(budget,new BigDecimal("100"),new BigDecimal("80"));assertThat(p.f.m.jobs.reserve(lease,budget,call,BigDecimal.ONE).approved()).isTrue();assertThat(p.f.m.jobs.cancelBeforeDispatch(call)).isTrue();
        p.transcribe(lease);p.score(p.claim(Kind.SCORE),"7");for(int n=0;n<2;n++){p.transcribe(p.claim(Kind.TRANSCRIBE));p.score(p.claim(Kind.SCORE),"7");}now.set(first().plusSeconds(180L*86400));assertThat(inspect().audioDeleteCandidate()).isTrue();
        rollback(()->{
            switch(fault){case "settled-missing-cost"->p.jdbc.update("UPDATE prepared_model_call SET state='SETTLED' WHERE id=?",call);case "cancelled-with-cost"->p.jdbc.update("UPDATE prepared_model_call SET actual_cny=1,provider_request_id='synthetic' WHERE id=?",call);case "future-generation"->p.jdbc.update("UPDATE prepared_model_call SET lease_generation=999 WHERE id=?",call);case "forged-settled-disposition"->p.jdbc.update("UPDATE prepared_model_call SET state='SETTLED',actual_cny=1,provider_request_id='synthetic' WHERE id=?",call);default->throw new AssertionError();}
            var before=p.jdbc.queryForMap("SELECT * FROM prepared_budget WHERE scope_id=?",budget);assertThat(inspect().audioDeleteCandidate()).isFalse();assertThat(p.jdbc.queryForMap("SELECT * FROM prepared_budget WHERE scope_id=?",budget)).isEqualTo(before);
        });
    }
    @Test void excessEvidenceIsBoundedAndRetainedInsteadOfBeingPartiallyAccepted(){
        completed();var parameters=new ArrayList<Object[]>();for(int n=0;n<1001;n++)parameters.add(new Object[]{UUID.randomUUID().toString(),p.assessment,p.student,UUID.randomUUID().toString()});
        p.jdbc.batchUpdate("INSERT INTO prepared_job(id,assessment_id,created_by,kind,input_version,idempotency_key,request_sha256,input_json) VALUES (?,?,?,'SCORE',1,?,REPEAT('a',64),'{\"syntheticFixture\":true}')",parameters);
        assertThatThrownBy(this::inspect).isInstanceOf(IllegalStateException.class).hasMessageContaining("有界");
    }
    @Test void contradictoryReviewFlagIsRejectedAndMalformedAudioAuditCannotBeIgnored(){
        completed();assertThatThrownBy(()->p.jdbc.update("UPDATE prepared_attempt SET requires_supplement_review=1 WHERE id=?",p.attempt)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        String answer=answer();p.service.recordAudioAccess(p.actor,p.assessment,p.attempt,answer,p.service.reviewMedia(p.actor,p.assessment,p.attempt,answer),now.get().minusSeconds(1));
        rollback(()->{p.jdbc.update("UPDATE prepared_audit SET resource_sha256=REPEAT('a',64) WHERE assessment_id=? AND action='ORIGINAL_AUDIO_AUTHORIZED'",p.assessment);assertThatThrownBy(this::inspect).isInstanceOf(IllegalStateException.class);});
    }
    @Test void processingReadersRequireAnActualTransactionAndDefaultClockKeepsNewResults(){
        completed();assertThatThrownBy(()->p.service.retentionResultsComplete(List.of())).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->p.service.retentionJobMatches(null,null)).isInstanceOf(IllegalStateException.class);
        p.f.m.f.f.ends.q.f.tx.executeWithoutResult(s->{assertThat(p.service.retentionResultsComplete(null)).isFalse();assertThatThrownBy(()->p.service.retentionJobMatches(null,null)).isInstanceOf(IllegalArgumentException.class);});
        var defaultClock=new PreparedRetentionEvidenceService(p.f.service,p.service,p.jdbc,p.f.m.f.f.ends.q.f.tx.getTransactionManager());assertThat(defaultClock.inspect(p.student,p.assessment,p.attempt).state()).isEqualTo(State.WITHIN_RETENTION);
    }
    @Test void malformedUnregisteredScopeShapesAreNeverSilentlyExcluded(){
        completed();String id=p.jdbc.queryForObject("SELECT transcribe_job_id FROM prepared_submission_answer WHERE answer_id=?",String.class,answer());
        var original=PreparedProcessingCodec.decode(p.f.m.jobs.get(id).orElseThrow().inputJson(),PreparedSubmission.Work.class);
        var foreign=new PreparedSubmission.Work(1,p.student,p.assessment,UUID.randomUUID().toString(),original.paperVersion(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),original.question(),1,original.answerRevision(),original.manifestSha256(),UUID.randomUUID().toString(),original.mediaProofSha256());
        String json=PreparedProcessingCodec.encode(foreign);var inputs=new LinkedHashMap<String,String>();
        inputs.put("non-object-root","[]");inputs.put("non-object-work","{\"work\":1}");inputs.put("null-work","{\"work\":null}");
        inputs.put("text-student",p.damage(json,"student","1"));inputs.put("other-student",p.damage(json,"student",p.student+1));
        inputs.put("text-assessment",p.damage(json,"assessment","1"));inputs.put("overflow-assessment",p.damage(json,"assessment",p.assessment+(1L<<32)));inputs.put("other-assessment",p.damage(json,"assessment",p.assessment+1));
        inputs.put("non-text-attempt",p.damage(json,"attempt",1));inputs.put("non-text-answer",p.damage(json,"answer",false));
        inputs.put("invalid-attempt",p.damage(json,"attempt","bad"));inputs.put("invalid-answer",p.damage(json,"answer","bad"));
        inputs.put("unsupported-work",p.damage(json,"schemaVersion",2));
        inputs.put("incomplete-work",json.replaceAll("\"paperVersion\":\"[^\"]*\",?",""));
        var regrade=new PreparedProcessingService.RegradeInput(1,foreign,UUID.randomUUID().toString(),1,p.actor,"unregistered scope fixture");
        inputs.put("unregistered-regrade",PreparedProcessingCodec.encode(regrade));inputs.put("unsupported-regrade",p.damage(PreparedProcessingCodec.encode(regrade),"schemaVersion",2));
        inputs.put("incomplete-regrade",PreparedProcessingCodec.encode(regrade).replaceAll("\"reason\":\"[^\"]*\",?",""));
        for(var input:inputs.entrySet())rollback(()->{
            // Direct JSON fixture permits a non-object root; normal enqueue rejects it.
            var job=p.f.m.jobs.enqueue(p.student,p.assessment,Kind.SCORE,original.answerRevision(),UUID.randomUUID().toString(),"{}");
            p.jdbc.update("UPDATE prepared_job SET input_json=? WHERE id=?",input.getValue(),job.id());
            assertThat(inspect().audioDeleteCandidate()).as(input.getKey()).isFalse();
        });
        rollback(()->{p.f.m.jobs.enqueue(p.student,p.assessment,Kind.TRANSCRIBE,original.answerRevision(),UUID.randomUUID().toString(),PreparedProcessingCodec.encode(regrade));assertThat(inspect().audioDeleteCandidate()).isFalse();});
        rollback(()->{var media=p.f.m.jobs.enqueue(p.student,p.assessment,Kind.ANSWER_MEDIA,1,UUID.randomUUID().toString(),PreparedProcessingCodec.encode(new PreparedAnswerMediaService.Input(p.student,p.assessment,foreign.attempt(),foreign.answer(),null,foreign.manifestSha256(),1,"fixture")));assertThat(inspect().audioDeleteCandidate()).isFalse();p.jdbc.update("UPDATE prepared_job SET input_json=JSON_REMOVE(input_json,'$.client') WHERE id=?",media.id());assertThat(inspect().audioDeleteCandidate()).isFalse();});
        assertThat(inspect().audioDeleteCandidate()).isTrue();
    }
    @Test void incompleteOrContradictoryPlaybackAuthorizationNeverLooksExpired(){
        completed();String answer=answer();p.service.recordAudioAccess(p.actor,p.assessment,p.attempt,answer,p.service.reviewMedia(p.actor,p.assessment,p.attempt,answer),now.get().plusSeconds(60));
        assertThatThrownBy(()->p.jdbc.update("UPDATE prepared_audit SET authorization_expires_at=NULL WHERE assessment_id=? AND action='ORIGINAL_AUDIO_AUTHORIZED'",p.assessment)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        for(String update:List.of("authorization_expires_at=DATE_SUB(created_at,INTERVAL 1 SECOND)","resource_version=resource_version+1"))
            rollback(()->{p.jdbc.update("UPDATE prepared_audit SET "+update+" WHERE assessment_id=? AND action='ORIGINAL_AUDIO_AUTHORIZED'",p.assessment);assertThatThrownBy(this::inspect).isInstanceOf(IllegalStateException.class);});
        assertThat(inspect().protections()).contains(Protection.ACTIVE_READ_AUTHORIZATION);
    }
    @Test void realLedgerSettledDispositionAndItsEvidenceRemainUnchanged(){
        var lease=p.claim(Kind.TRANSCRIBE);String budget="prepared-retention-evidence-"+UUID.randomUUID(),call=UUID.randomUUID().toString();budgets.add(budget);
        p.f.m.jobs.initializeBudget(budget,new BigDecimal("100"),new BigDecimal("80"));assertThat(p.f.m.jobs.reserve(lease,budget,call,BigDecimal.ONE).approved()).isTrue();assertThat(p.f.m.jobs.markDispatched(lease,call)).isTrue();p.f.m.jobs.settle(call,BigDecimal.ZERO,"fixture-zero-cost");
        p.transcribe(lease);p.score(p.claim(Kind.SCORE),"7");for(int n=0;n<2;n++){p.transcribe(p.claim(Kind.TRANSCRIBE));p.score(p.claim(Kind.SCORE),"7");}now.set(first().plusSeconds(180L*86400));
        var before=p.jdbc.queryForList("SELECT * FROM prepared_budget_event WHERE scope_id=? ORDER BY id",budget);assertThat(inspect().audioDeleteCandidate()).isTrue();assertThat(p.jdbc.queryForList("SELECT * FROM prepared_budget_event WHERE scope_id=? ORDER BY id",budget)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"ANSWER_MEDIA","TRANSCRIBE","SCORE","REGRADE","CROSS_ORIGINAL"})
    void registeredOtherOriginalWorkIsExcludedButReferencesBackToTargetAreNot(String kind){
        completed();rollback(()->{
            String[] other=otherOriginal();String answer=answer();String mediaId=p.jdbc.queryForObject("SELECT job_id FROM prepared_answer_media WHERE answer_id=?",String.class,answer);
            var media=PreparedProcessingCodec.decode(p.f.m.jobs.get(mediaId).orElseThrow().inputJson(),PreparedAnswerMediaService.Input.class);
            var otherInput=new PreparedAnswerMediaService.Input(p.student,p.assessment,other[0],other[1],media.client(),media.manifestSha256(),media.declarationRevision(),media.deployment());
            var otherMedia=p.f.m.jobs.enqueue(p.student,p.assessment,Kind.ANSWER_MEDIA,media.declarationRevision(),UUID.randomUUID().toString(),PreparedProcessingCodec.encode(otherInput));
            p.jdbc.update("INSERT INTO prepared_answer_media(answer_id,assessment_id,student_id,attempt_id,manifest_sha256,declaration_revision,deployment_id,job_id) VALUES (?,?,?,?,?,?,?,?)",other[1],p.assessment,p.student,other[0],media.manifestSha256(),media.declarationRevision(),media.deployment(),otherMedia.id());
            if(!kind.equals("ANSWER_MEDIA")){
                String originalJob=p.jdbc.queryForObject("SELECT transcribe_job_id FROM prepared_submission_answer WHERE answer_id=?",String.class,answer);var original=PreparedProcessingCodec.decode(p.f.m.jobs.get(originalJob).orElseThrow().inputJson(),PreparedSubmission.Work.class);String submission=UUID.randomUUID().toString();
                var work=new PreparedSubmission.Work(1,p.student,p.assessment,other[0],original.paperVersion(),submission,other[1],original.question(),1,original.answerRevision(),original.manifestSha256(),kind.equals("CROSS_ORIGINAL")?mediaId:otherMedia.id(),original.mediaProofSha256());
                var transcribe=p.f.m.jobs.enqueueAfter(p.student,p.assessment,Kind.TRANSCRIBE,work.answerRevision(),UUID.randomUUID().toString(),PreparedProcessingCodec.encode(work),List.of(work.mediaJob()));
                var score=p.f.m.jobs.enqueueAfter(p.student,p.assessment,Kind.SCORE,work.answerRevision(),UUID.randomUUID().toString(),PreparedProcessingCodec.encode(work),List.of(transcribe.id()));
                p.jdbc.update("INSERT INTO prepared_submission(id,assessment_id,student_id,attempt_id,paper_version_id,submitted_revision,answer_count,submitted_at,snapshot_json,snapshot_sha256) VALUES (?,?,?,?,?,1,1,CURRENT_TIMESTAMP(3),'{\"syntheticScopeFixture\":true}',REPEAT('a',64))",submission,p.assessment,p.student,other[0],work.paperVersion());
                p.jdbc.update("INSERT INTO prepared_submission_answer(assessment_id,student_id,attempt_id,submission_id,answer_id,ordinal,transcribe_job_id,score_job_id) VALUES (?,?,?,?,?,1,?,?)",p.assessment,p.student,other[0],submission,other[1],transcribe.id(),score.id());
                if(kind.equals("REGRADE")){
                    // Scope-registration fixture only, not successful grading of this other answer.
                    String transcript=p.jdbc.queryForObject("SELECT transcript_id FROM prepared_processing_head WHERE answer_id=?",String.class,answer);
                    // The FK intentionally forbids a regrade referencing another original's transcript.
                    var input=new PreparedProcessingService.RegradeInput(1,work,transcript,1,p.actor,"scope fixture");var regrade=p.f.m.jobs.enqueueAfter(p.student,p.assessment,Kind.SCORE,work.answerRevision(),UUID.randomUUID().toString(),PreparedProcessingCodec.encode(input),List.of(originalJob));
                    assertThatThrownBy(()->p.jdbc.update("INSERT INTO prepared_score_request(job_id,assessment_id,student_id,attempt_id,answer_id,transcript_id,requested_revision,requested_by,reason) VALUES (?,?,?,?,?,?,1,?,'scope fixture')",regrade.id(),p.assessment,p.student,other[0],other[1],transcript,p.actor)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                    assertThat(inspect().audioDeleteCandidate()).isFalse();return;
                }
            }
            assertThat(inspect().audioDeleteCandidate()).isEqualTo(!kind.equals("CROSS_ORIGINAL"));
        });assertThat(inspect().audioDeleteCandidate()).isTrue();
    }
}
