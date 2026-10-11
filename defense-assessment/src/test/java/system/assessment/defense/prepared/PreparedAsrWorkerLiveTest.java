package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.processing.*;
import system.assessment.defense.prepared.storage.*;
import java.nio.file.Path;
import java.time.*;
import java.net.URI;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;

/** Actual submission, native media, MySQL and Worker restart. Supplier/OSS are explicit doubles. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAsrWorkerLiveTest {
    final PreparedProcessingLiveTest f=new PreparedProcessingLiveTest();@TempDir Path folder;
    MockEnvironment env;PreparedAsrPolicy policy;PreparedAsrClient client;PreparedPrivateOssStore store;PreparedStoragePolicy storage;
    String scope;String task=UUID.randomUUID().toString();String inputUrl;PreparedPrivateOssStore.StoredAnswer submitted;
    @BeforeEach void setup()throws Exception {
        f.folder=folder;f.setup();env=new PreparedAsrAdapterTest().config().withProperty("prepared.asr.pricing-verified-at",Instant.now().minusSeconds(60).toString());
        scope="prepared-asr-"+UUID.randomUUID();env.withProperty("prepared.models.budget-scope",scope);policy=new PreparedAsrPolicy(env,Clock.systemUTC());
        store=mock(PreparedPrivateOssStore.class);storage=mock(PreparedStoragePolicy.class);when(storage.availability()).thenReturn(new PreparedStoragePolicy.Availability(true,"SDK fixture only"));
        when(store.asrInput(any())).thenAnswer(i->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();submitted=i.getArgument(0);inputUrl="https://prepared-interview-fixture.oss-cn-beijing.aliyuncs.com/"+submitted.objectKey()+"?Signature=fixture-private-original";return new PreparedPrivateOssStore.Preview(inputUrl,Instant.now().plusSeconds(86400).toString());});
        client=mock(PreparedAsrClient.class);when(client.submit(any(),any(),any(),anyString())).thenReturn(task);
        when(client.poll(any(),eq(task),anyString())).thenReturn(new PreparedAsrClient.Poll(task,"PENDING",null,null));
        when(client.download(any(),any(),any(),anyString())).thenAnswer(i->{var p=i.getArgument(1,PreparedAsrPolicy.Snapshot.class);var m=i.getArgument(2,PreparedPrivateOssStore.StoredAnswer.class);
            return new PreparedProcessing.Transcript(1,"dashscope",p.model(),m.proof().sha256(),m.proof().durationMs(),List.of(new PreparedProcessing.Sentence(0,m.proof().durationMs(),"明确标记的合成供应商转写。")));});
    }
    @AfterEach void cleanup(){
        f.jdbc.update("DELETE FROM prepared_async_model_task WHERE job_id IN (SELECT id FROM prepared_job WHERE assessment_id=?)",f.assessment);
        f.jdbc.update("DELETE FROM prepared_budget_event WHERE scope_id=?",scope);f.jdbc.update("DELETE FROM prepared_model_call WHERE scope_id=?",scope);f.jdbc.update("DELETE FROM prepared_budget WHERE scope_id=?",scope);f.cleanup();
    }
    PreparedAsrWorker worker(){return new PreparedAsrWorker(f.f.m.jobs,f.service,policy,client,store,storage,Clock.systemUTC());}
    Job job(){String id=f.jdbc.queryForObject("SELECT job_id FROM prepared_async_model_task WHERE job_id IN (SELECT id FROM prepared_job WHERE assessment_id=?)",String.class,f.assessment);return f.f.m.jobs.get(id).orElseThrow();}
    void isolateQueued(){f.jdbc.update("UPDATE prepared_job SET next_run_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 DAY) WHERE assessment_id=? AND state='QUEUED'",f.assessment);}
    void due(){f.jdbc.update("UPDATE prepared_job SET next_run_at=CURRENT_TIMESTAMP(3) WHERE id=?",job().id());}
    void success()throws Exception {when(client.poll(any(),eq(task),anyString())).thenReturn(new PreparedAsrClient.Poll(task,"SUCCEEDED",1L,URI.create("https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/fixture.json?Signature=fixture")));}
    @Test void restartContinuesKnownTaskWithoutSecondSubmitAndFeeSettlesBeforeImmutableResult()throws Exception {
        var original=f.f.status();worker().runOnce();isolateQueued();assertThat(job().state()).isEqualTo(State.RETRY_WAIT);var id=job().id();
        for(int n=0;n<4;n++){due();worker().runOnce();}assertThat(job().attempts()).isEqualTo(1);
        success();due();worker().runOnce();assertThat(job().state()).isEqualTo(State.SUCCEEDED);
        verify(client,times(1)).submit(any(),any(),any(),anyString());verify(store,times(1)).asrInput(any());verify(client,times(1)).download(any(),any(),any(),eq(PreparedJobInput.digest(inputUrl)));
        assertThat(f.f.status()).isEqualTo(original);assertThat(f.count("prepared_processing_result")).isEqualTo(1);assertThat(f.f.m.jobs.budget(scope).spent()).isEqualByComparingTo("0.001");
        assertThat(f.jdbc.queryForObject("SELECT input_json FROM prepared_async_model_task WHERE job_id=?",String.class,id)).doesNotContain("fixture-key","Signature","fixture-private-original");
    }
    @Test void unknownSubmitRetainsFeeReservationPausesAndNeverClaimsFakeResult()throws Exception {
        when(client.submit(any(),any(),any(),anyString())).thenThrow(new java.io.IOException("fixture network loss"));worker().runOnce();isolateQueued();
        assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);assertThat(f.f.m.jobs.budget(scope).paused()).isTrue();assertThat(f.f.m.jobs.budget(scope).reserved()).isEqualByComparingTo("0.001");
        for(int n=0;n<4;n++)worker().runOnce();verify(client,times(1)).submit(any(),any(),any(),anyString());assertThat(f.count("prepared_processing_result")).isZero();assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void temporaryPollAndDownloadFailuresOnlyRetryGetsNotPaidPostAndOriginalReceiptStaysValid()throws Exception {
        worker().runOnce();isolateQueued();when(client.poll(any(),eq(task),anyString())).thenThrow(new java.io.IOException("temporary GET"));due();worker().runOnce();assertThat(job().state()).isEqualTo(State.RETRY_WAIT);
        success();doThrow(new java.io.IOException("temporary download")).when(client).download(any(),any(),any(),anyString());due();worker().runOnce();assertThat(job().state()).isEqualTo(State.RETRY_WAIT);
        assertThat(f.f.m.jobs.budget(scope).spent()).isEqualByComparingTo("0.001");assertThat(f.f.m.jobs.budget(scope).reserved()).isZero();verify(client,times(1)).submit(any(),any(),any(),anyString());
        assertThat(f.count("prepared_processing_result")).isZero();assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void terminalUnknownFeeAndExpiredTaskRequireReviewWithoutZeroOrNewRecording()throws Exception {
        worker().runOnce();isolateQueued();when(client.poll(any(),eq(task),anyString())).thenReturn(new PreparedAsrClient.Poll(task,"FAILED",null,null));due();worker().runOnce();
        assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);assertThat(f.f.m.jobs.budget(scope).paused()).isTrue();assertThat(f.count("prepared_processing_result")).isZero();assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void changedIndependentIdentityCannotPollOriginalTask()throws Exception {
        worker().runOnce();isolateQueued();env.withProperty("prepared.asr.billing-reference","other-identity");due();worker().runOnce();assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);verify(client,never()).poll(any(),anyString(),anyString());
        assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void normalizedCheckpointPublishesAfterLeaseExpiryWithoutAnySupplierGetOrPost()throws Exception {
        worker().runOnce();isolateQueued();due();var lease=f.f.m.jobs.claim(Kind.TRANSCRIBE,"checkpoint-fixture",300).orElseThrow();var t=f.f.m.jobs.async(lease.job().id()).orElseThrow();
        var input=PreparedProcessingCodec.decode(t.inputJson(),PreparedAsrWorker.Input.class);var audio=input.media().proof();
        f.f.m.jobs.settle(t.callId(),new BigDecimal("0.001"),task);
        var text=new PreparedProcessing.Transcript(1,"dashscope",input.policy().model(),audio.sha256(),audio.durationMs(),List.of(new PreparedProcessing.Sentence(0,audio.durationMs(),"合成原结果检查点")));
        assertThat(f.f.m.jobs.saveAsyncResult(lease,PreparedProcessingCodec.encode(text))).isTrue();f.jdbc.update("UPDATE prepared_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",lease.job().id());
        clearInvocations(client,store);worker().runOnce();assertThat(job().state()).isEqualTo(State.SUCCEEDED);verifyNoInteractions(client,store);assertThat(f.count("prepared_processing_result")).isEqualTo(1);
    }
    @Test void expiredKnownTaskPausesUnknownFeeButKeepsOriginalReceipt()throws Exception {
        worker().runOnce();isolateQueued();f.jdbc.update("UPDATE prepared_async_model_task SET recovery_deadline=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE job_id=?",job().id());due();worker().runOnce();
        assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);assertThat(f.f.m.jobs.budget(scope).paused()).isTrue();verify(client,never()).poll(any(),anyString(),anyString());assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void knownFailedChargeAndInvalidContentDoNotBecomeZeroOrLoseSubmission()throws Exception {
        worker().runOnce();isolateQueued();when(client.poll(any(),eq(task),anyString())).thenReturn(new PreparedAsrClient.Poll(task,"FAILED",1L,null));due();worker().runOnce();
        assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);assertThat(f.f.m.jobs.budget(scope).spent()).isEqualByComparingTo("0.001");assertThat(f.f.m.jobs.budget(scope).reserved()).isZero();assertThat(f.count("prepared_processing_result")).isZero();assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void unexpectedActualCostPausesAllNewPaidWorkYetUsesHistoricalPrice()throws Exception {
        worker().runOnce();isolateQueued();env.withProperty("prepared.asr.cny-per-second","0.002");
        when(client.poll(any(),eq(task),anyString())).thenReturn(new PreparedAsrClient.Poll(task,"SUCCEEDED",2L,URI.create("https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/fixture.json?Signature=fixture")));due();worker().runOnce();
        assertThat(f.f.m.jobs.budget(scope).spent()).isEqualByComparingTo("0.002");assertThat(f.f.m.jobs.budget(scope).paused()).isTrue();assertThat(job().state()).isEqualTo(State.SUCCEEDED);verify(client,times(1)).submit(any(),any(),any(),anyString());
    }
    @Test void emptyDecodedTextIsRejectedAfterFeeSettlementWithoutAnotherTranscription()throws Exception {
        worker().runOnce();isolateQueued();success();doAnswer(i->{var m=i.getArgument(2,PreparedPrivateOssStore.StoredAnswer.class);return new PreparedProcessing.Transcript(1,"dashscope","qwen3-asr-flash-filetrans",m.proof().sha256(),m.proof().durationMs(),List.of());}).when(client).download(any(),any(),any(),anyString());due();worker().runOnce();
        assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);assertThat(f.f.m.jobs.budget(scope).spent()).isEqualByComparingTo("0.001");assertThat(f.count("prepared_processing_result")).isZero();assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void missingStorageOldPricesShortFetchAndPausedBudgetNeverSend()throws Exception {
        when(storage.availability()).thenReturn(new PreparedStoragePolicy.Availability(false,"not real cloud"));worker().runOnce();verifyNoInteractions(client,store);
        when(storage.availability()).thenReturn(new PreparedStoragePolicy.Availability(true,"fixture only"));env.withProperty("prepared.asr.pricing-verified-at","2026-01-01T00:00:00Z");worker().runOnce();verifyNoInteractions(client,store);
        env.withProperty("prepared.asr.pricing-verified-at",Instant.now().minusSeconds(60).toString());doReturn(new PreparedPrivateOssStore.Preview("https://prepared-interview-fixture.oss-cn-beijing.aliyuncs.com/invalid.wav?Signature=fixture",Instant.now().toString())).when(store).asrInput(any());worker().runOnce();verifyNoInteractions(client);assertThat(f.count("prepared_processing_result")).isZero();
    }
    @Test void revokedStudentDuringPrivateReadCannotTriggerPaidSubmission()throws Exception {
        doAnswer(i->{submitted=i.getArgument(0);inputUrl="https://prepared-interview-fixture.oss-cn-beijing.aliyuncs.com/"+submitted.objectKey()+"?Signature=fixture";
            f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.student);return new PreparedPrivateOssStore.Preview(inputUrl,Instant.now().plusSeconds(86400).toString());}).when(store).asrInput(any());
        try{worker().runOnce();verify(client,never()).submit(any(),any(),any(),anyString());assertThat(f.count("prepared_processing_result")).isZero();}
        finally{f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.student);}assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void leaseExpiresAfterProviderAcceptsYetKnownTaskCanRecoverWithoutSecondPost()throws Exception {
        doAnswer(i->{f.jdbc.update("UPDATE prepared_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE assessment_id=? AND kind='TRANSCRIBE' AND state='RUNNING'",f.assessment);return task;}).when(client).submit(any(),any(),any(),anyString());
        worker().runOnce();isolateQueued();success();worker().runOnce();assertThat(job().state()).isEqualTo(State.SUCCEEDED);verify(client,times(1)).submit(any(),any(),any(),anyString());assertThat(f.f.m.jobs.budget(scope).paused()).isFalse();
    }
    @Test void sharedPausedBudgetCannotBeBypassedByFileAsr()throws Exception {
        f.f.m.jobs.initializeBudget(scope,new BigDecimal("100"),new BigDecimal("80"));f.jdbc.update("UPDATE prepared_budget SET paused=TRUE WHERE scope_id=?",scope);worker().runOnce();verifyNoInteractions(client);assertThat(f.f.m.jobs.budget(scope).spent()).isZero();assertThat(f.f.m.jobs.budget(scope).reserved()).isZero();
    }
    @Test void repeatedStatusFailuresEscalateAfterThreeGetsInsteadOfWaitingForever()throws Exception {
        worker().runOnce();isolateQueued();when(client.poll(any(),eq(task),anyString())).thenThrow(new java.io.IOException("persistent GET outage"));
        for(int n=0;n<3;n++){due();worker().runOnce();}assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);
        assertThat(f.f.m.jobs.budget(scope).paused()).isTrue();verify(client,times(1)).submit(any(),any(),any(),anyString());assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void repeatedResultFetchFailuresAreBoundedAfterKnownFeesAndNeverCreateZero()throws Exception {
        worker().runOnce();isolateQueued();success();doThrow(new java.io.IOException("persistent result outage")).when(client).download(any(),any(),any(),anyString());
        for(int n=0;n<3;n++){due();worker().runOnce();}assertThat(job().state()).isEqualTo(State.NEEDS_REVIEW);
        assertThat(f.f.m.jobs.budget(scope).spent()).isEqualByComparingTo("0.001");assertThat(f.f.m.jobs.budget(scope).reserved()).isZero();assertThat(f.count("prepared_processing_result")).isZero();assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void disabledConfigOrPrivateStorageFailureNeverSendsPaidRequest()throws Exception {
        env.withProperty("prepared.models.enabled","false");worker().runOnce();verifyNoInteractions(client,store);
        env.withProperty("prepared.models.enabled","true");doThrow(new java.io.IOException("SDK fixture failure")).when(store).asrInput(any());worker().runOnce();verifyNoInteractions(client);assertThat(f.count("prepared_processing_result")).isZero();assertThat(f.f.status().submitted()).isTrue();
    }
}
