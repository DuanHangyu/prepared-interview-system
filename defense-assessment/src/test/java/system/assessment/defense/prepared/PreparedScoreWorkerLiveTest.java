package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.processing.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;

/** Real original receipt/native media/MySQL. Model and cloud are explicit test doubles. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedScoreWorkerLiveTest {
    final PreparedProcessingLiveTest f=new PreparedProcessingLiveTest();@TempDir Path folder;
    MockEnvironment env;PreparedScorePolicy policy;PreparedScoreClient client;String scope,scoreJob,answer;
    @BeforeEach void setup()throws Exception {
        f.folder=folder;f.setup();var transcript=f.transcribe(f.claim(Kind.TRANSCRIBE));answer=transcript.answerId();
        scoreJob=f.jdbc.queryForObject("SELECT score_job_id FROM prepared_submission_answer WHERE answer_id=?",String.class,answer);
        env=new PreparedScoreAdapterTest().config().withProperty("prepared.scoring.pricing-verified-at",Instant.now().minusSeconds(60).toString());
        scope="prepared-score-"+UUID.randomUUID();env.withProperty("prepared.models.budget-scope",scope);policy=new PreparedScorePolicy(env,Clock.systemUTC());client=mock(PreparedScoreClient.class);
        when(client.score(any(),any(),anyString())).thenAnswer(i->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var in=i.getArgument(1,PreparedScoreClient.Input.class);return new PreparedScoreClient.Reply("fixture-score-request",12,34,content(in),"stop");});
    }
    String content(PreparedScoreClient.Input in){return PreparedProcessingCodec.encode(new PreparedProcessing.Score(1,in.questionId(),in.transcriptId(),in.transcriptSha256(),in.maxScore(),new java.math.BigDecimal("8.50"),"明确标记的合成评分",List.of(new PreparedProcessing.Evidence(1,"保留原录音"))));}
    @AfterEach void cleanup(){f.jdbc.update("DELETE FROM prepared_score_call WHERE job_id IN (SELECT id FROM prepared_job WHERE assessment_id=?)",f.assessment);
        f.jdbc.update("DELETE FROM prepared_budget_event WHERE scope_id=?",scope);f.jdbc.update("DELETE FROM prepared_model_call WHERE scope_id=?",scope);f.jdbc.update("DELETE FROM prepared_budget WHERE scope_id=?",scope);f.cleanup();}
    PreparedScoreWorker worker(){return new PreparedScoreWorker(f.f.m.jobs,f.service,policy,client);}
    Job job(){return f.f.m.jobs.get(scoreJob).orElseThrow();}
    @Test void completeFrozenScoreHasOriginalSentenceEvidenceAndNeverChangesReliableReceipt()throws Exception {
        var receipt=f.f.status();worker().runOnce();assertThat(job().state()).isEqualTo(State.SUCCEEDED);assertThat(f.view(answer).history()).hasSize(2);
        assertThat(f.f.status()).isEqualTo(receipt);assertThat(f.f.m.jobs.budget(scope).spent()).isEqualByComparingTo("0.000080");assertThat(f.f.m.jobs.budget(scope).reserved()).isZero();
        verify(client,times(1)).score(any(),argThat(in->in.rubric().startsWith("不应泄漏的教师标准")&&in.sentences().size()==1),anyString());
        assertThat(f.jdbc.queryForObject("SELECT input_json FROM prepared_score_call WHERE job_id=?",String.class,scoreJob)).doesNotContain("fixture-score-key","Signature");
    }
    @Test void unknownPaidResponsePausesAndPreservesOriginalWithoutReplayingOrZero()throws Exception {
        doThrow(new java.io.IOException("fixture response lost")).when(client).score(any(),any(),anyString());worker().runOnce();
        assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);assertThat(f.f.m.jobs.budget(scope).paused()).isTrue();assertThat(f.f.m.jobs.budget(scope).reserved()).isEqualByComparingTo(policy.snapshot().maximumCost());
        for(int n=0;n<4;n++)worker().runOnce();verify(client,times(1)).score(any(),any(),anyString());assertThat(f.view(answer).scoreId()).isNull();assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void knownFeesSettleBeforeRejectingBadEvidenceOrTruncatedResult()throws Exception {
        doAnswer(i->{var in=i.getArgument(1,PreparedScoreClient.Input.class);return new PreparedScoreClient.Reply("fixture",12,34,content(in).replace("保留原录音","伪造证据"),"stop");}).when(client).score(any(),any(),anyString());
        worker().runOnce();assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);assertThat(f.f.m.jobs.budget(scope).spent()).isEqualByComparingTo("0.000080");assertThat(f.f.m.jobs.budget(scope).reserved()).isZero();assertThat(f.view(answer).scoreId()).isNull();assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void settledReplyCheckpointRecoversAfterPublicationCrashWithoutAnotherModelCall()throws Exception {
        var failing=spy(f.service);doThrow(new org.springframework.dao.TransientDataAccessResourceException("fixture db publication outage")).when(failing).completeScore(any(),anyString(),any());
        new PreparedScoreWorker(f.f.m.jobs,failing,policy,client).runOnce();assertThat(job().state()).isEqualTo(State.RETRY_WAIT);
        f.jdbc.update("UPDATE prepared_job SET next_run_at=CURRENT_TIMESTAMP(3) WHERE id=?",scoreJob);clearInvocations(client);worker().runOnce();
        assertThat(job().state()).isEqualTo(State.SUCCEEDED);verifyNoInteractions(client);assertThat(job().attempts()).isEqualTo(1);assertThat(f.view(answer).history()).hasSize(2);
    }
    @Test void expiredOriginalLeaseStillCheckpointsKnownPaidReplyAndNewLeasePublishesWithoutReplay()throws Exception {
        doAnswer(i->{var in=i.getArgument(1,PreparedScoreClient.Input.class);f.jdbc.update("UPDATE prepared_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",scoreJob);
            return new PreparedScoreClient.Reply("fixture-late",12,34,content(in),"stop");}).when(client).score(any(),any(),anyString());
        worker().runOnce();assertThat(f.view(answer).scoreId()).isNull();worker().runOnce();assertThat(job().state()).isEqualTo(State.SUCCEEDED);verify(client,times(1)).score(any(),any(),anyString());assertThat(f.f.m.jobs.budget(scope).paused()).isFalse();
    }
    @Test void roleRevokedDuringModelCallCannotWriteLateScoreButSettlesKnownFee()throws Exception {
        doAnswer(i->{var in=i.getArgument(1,PreparedScoreClient.Input.class);f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.student);return new PreparedScoreClient.Reply("fixture",12,34,content(in),"stop");}).when(client).score(any(),any(),anyString());
        try{worker().runOnce();assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);assertThat(f.f.m.jobs.budget(scope).spent()).isEqualByComparingTo("0.000080");}
        finally{f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.student);}assertThat(f.view(answer).scoreId()).isNull();assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void disabledUnverifiedOrPausedConfigurationDoesNotSend(){
        env.withProperty("prepared.models.enabled","false");worker().runOnce();assertThat(job().state()).isEqualTo(State.QUEUED);verifyNoInteractions(client);
        env.withProperty("prepared.models.enabled","true").withProperty("prepared.scoring.pricing-verified-at","2026-01-01T00:00:00Z");worker().runOnce();assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);verifyNoInteractions(client);
    }
    @Test void publicationFailuresPersistAcrossRestartsAndEscalateAfterThreeWithoutPaidReplay()throws Exception {
        var failing=spy(f.service);doThrow(new org.springframework.dao.TransientDataAccessResourceException("fixture outage")).when(failing).completeScore(any(),anyString(),any());
        for(int n=0;n<3;n++){new PreparedScoreWorker(f.f.m.jobs,failing,policy,client).runOnce();f.jdbc.update("UPDATE prepared_job SET next_run_at=CURRENT_TIMESTAMP(3) WHERE id=?",scoreJob);}
        assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);assertThat(f.f.m.jobs.scoreCall(scoreJob).orElseThrow().publicationFailures()).isEqualTo(3);
        verify(client,times(1)).score(any(),any(),anyString());assertThat(f.view(answer).scoreId()).isNull();assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void overBoundCredibleUsagePausesSharedBudgetButRetainsValidBoundScore()throws Exception {
        doAnswer(i->new PreparedScoreClient.Reply("fixture-over",2000000,34,content(i.getArgument(1)),"stop")).when(client).score(any(),any(),anyString());
        worker().runOnce();assertThat(job().state()).isEqualTo(State.SUCCEEDED);assertThat(f.f.m.jobs.budget(scope).paused()).isTrue();assertThat(f.f.m.jobs.budget(scope).spent()).isEqualByComparingTo("2.000068");assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void fullOriginalThreeQuestionReceiptProducesFirstResultsAnchorWithoutChangingDeadlines()throws Exception {
        var original=f.f.status();f.transcribe(f.claim(Kind.TRANSCRIBE));f.transcribe(f.claim(Kind.TRANSCRIBE));for(int n=0;n<3;n++)worker().runOnce();
        assertThat(f.count("prepared_processing_result")).isEqualTo(6);assertThat(f.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,f.attempt)).isNotNull();assertThat(f.f.status()).isEqualTo(original);
        verify(client,times(3)).score(any(),any(),anyString());
    }
    @Test void truncatedOutputDoesNotTurnIntoZeroAndPausedBudgetCannotBeBypassed()throws Exception {
        doAnswer(i->new PreparedScoreClient.Reply("fixture-truncated",12,34,content(i.getArgument(1)),"length")).when(client).score(any(),any(),anyString());worker().runOnce();
        assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);assertThat(f.view(answer).scoreId()).isNull();assertThat(f.f.m.jobs.budget(scope).spent()).isEqualByComparingTo("0.000080");
        f.transcribe(f.claim(Kind.TRANSCRIBE));f.jdbc.update("UPDATE prepared_budget SET paused=TRUE WHERE scope_id=?",scope);worker().runOnce();verify(client,times(1)).score(any(),any(),anyString());assertThat(f.f.m.jobs.budget(scope).reserved()).isZero();
    }
    @Test void newerConfirmedRegradeCannotBeOverwrittenByOlderReply()throws Exception {
        worker().runOnce();var initial=f.view(answer);var request=f.service.regrade(f.actor,f.assessment,f.attempt,answer,new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),initial.revision(),initial.transcriptId(),"合成重评"));
        doAnswer(i->{var in=i.getArgument(1,PreparedScoreClient.Input.class);var v=f.view(answer);f.service.confirm(f.actor,f.assessment,f.attempt,answer,new PreparedProcessing.ConfirmCommand(UUID.randomUUID().toString(),v.revision(),v.scoreId(),"保留教师已核分结果"));return new PreparedScoreClient.Reply("fixture-history",12,34,content(in),"stop");}).when(client).score(any(),any(),anyString());
        worker().runOnce();var current=f.view(answer);assertThat(current.confirmed()).isTrue();assertThat(current.scoreId()).isEqualTo(initial.scoreId());assertThat(current.history()).hasSize(3);assertThat(f.f.m.jobs.get(request.jobId()).orElseThrow().state()).isEqualTo(State.SUCCEEDED);
    }
    @Test void historicalReplyPublicationDoesNotRepriceOrUseChangedCredentials()throws Exception {
        var failing=spy(f.service);doThrow(new org.springframework.dao.TransientDataAccessResourceException("fixture publication")).when(failing).completeScore(any(),anyString(),any());new PreparedScoreWorker(f.f.m.jobs,failing,policy,client).runOnce();
        env.withProperty("prepared.scoring.api-key","").withProperty("prepared.scoring.input-cny-per-million","100").withProperty("prepared.scoring.billing-reference","different-account");
        f.jdbc.update("UPDATE prepared_job SET next_run_at=CURRENT_TIMESTAMP(3) WHERE id=?",scoreJob);clearInvocations(client);worker().runOnce();assertThat(job().state()).isEqualTo(State.SUCCEEDED);verifyNoInteractions(client);assertThat(f.f.m.jobs.budget(scope).spent()).isEqualByComparingTo("0.000080");
    }
    @Test void tamperedSnapshotOrRemovedStudentAssignmentNeverPublishesOrResends()throws Exception {
        var failing=spy(f.service);doThrow(new org.springframework.dao.TransientDataAccessResourceException("fixture publication")).when(failing).completeScore(any(),anyString(),any());new PreparedScoreWorker(f.f.m.jobs,failing,policy,client).runOnce();
        var saved=f.f.m.jobs.scoreCall(scoreJob).orElseThrow();var input=PreparedScoreClient.decode(saved.inputJson(),PreparedScoreWorker.Input.class);
        String changed=PreparedScoreClient.encode(new PreparedScoreWorker.Input(1,input.original(),input.expectedRevision()+1,input.regrade(),input.verifiedContextTokenLimit(),input.request(),input.policy()));
        f.jdbc.update("UPDATE prepared_score_call SET input_json=?,input_sha256=? WHERE job_id=?",changed,PreparedJobInput.digest(changed),scoreJob);f.jdbc.update("UPDATE prepared_job SET next_run_at=CURRENT_TIMESTAMP(3) WHERE id=?",scoreJob);worker().runOnce();
        assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);assertThat(f.view(answer).scoreId()).isNull();verify(client,times(1)).score(any(),any(),anyString());assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void currentStudentRoleIsCheckedBeforeAnyPaidRequest(){
        f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.student);try{worker().runOnce();assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);verifyNoInteractions(client);}
        finally{f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.student);}assertThat(f.f.status().submitted()).isTrue();
    }
}
