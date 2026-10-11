package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.*;
import system.assessment.defense.prepared.media.*;
import system.assessment.defense.prepared.paper.*;
import system.assessment.defense.prepared.speech.*;
import system.assessment.defense.prepared.storage.*;
import system.assessment.defense.prepared.audio.PreparedAudioRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Budget/dispatch/storage orchestration doubles; never calls a cloud model or accepts a paper. */
class PreparedTtsWorkerTest {
    @TempDir Path folder;
    PreparedJobLedger ledger;PreparedTtsPolicy policy;PreparedTtsClient client;PreparedPrivateOssStore store;
    PreparedStoragePolicy storagePolicy;PreparedSpeechSpool spool;PreparedPaperRepository papers;
    ResourceAccessControlService access;PreparedTtsWorker worker;Lease lease;PreparedSpeech.PartInput input;
    PreparedAudioRepository audio;
    PreparedWaveAudio.Verified proof;Path target;String qid=UUID.randomUUID().toString();
    @BeforeEach void setup() throws Exception {
        ledger=mock(PreparedJobLedger.class);policy=mock(PreparedTtsPolicy.class);client=mock(PreparedTtsClient.class);
        store=mock(PreparedPrivateOssStore.class);storagePolicy=mock(PreparedStoragePolicy.class);spool=mock(PreparedSpeechSpool.class);
        papers=mock(PreparedPaperRepository.class);access=mock(ResourceAccessControlService.class);
        var actual=new PreparedTtsAdapterTest().policy().snapshot();input=new PreparedSpeech.PartInput(1,qid,UUID.randomUUID().toString(),
                PreparedJobInput.digest("题目文字"),0,1,"题目文字",actual);
        lease=new Lease(new Job(UUID.randomUUID().toString(),21,4,Kind.TTS,0,PreparedSpeech.encode(input),null,State.RUNNING,1),1,"tts-worker");
        when(policy.workerEnabled()).thenReturn(true);when(policy.snapshot()).thenReturn(actual);when(policy.apiKey()).thenReturn("fixture-key");
        when(policy.budgetCap()).thenReturn(new BigDecimal("100"));when(policy.pauseAt()).thenReturn(new BigDecimal("80"));
        when(policy.actualCost(any(),anyLong())).thenReturn(new BigDecimal("0.00096"));
        when(storagePolicy.snapshot()).thenReturn(new PreparedStoragePolicy.Snapshot("https://oss-cn-beijing.aliyuncs.com","prepared-interview-fixture","prepared-fixture"));
        when(ledger.claim(Kind.TTS,"tts-worker",300)).thenReturn(Optional.of(lease));
        when(ledger.reserve(eq(lease),anyString(),anyString(),any())).thenReturn(new Decision(true,""));when(ledger.markDispatched(eq(lease),anyString())).thenReturn(true);
        when(ledger.heartbeat(lease,300)).thenReturn(true);when(ledger.succeed(eq(lease),anyString())).thenReturn(true);
        target=folder.resolve("broadcast.wav");Files.write(target,new PreparedSpeechMediaTest().wave());proof=PreparedWaveAudio.inspect(target);
        when(spool.stage(lease)).thenReturn(target);when(client.download(any(),eq(target))).thenReturn(proof);
        when(client.synthesize(any(),anyString(),anyString())).thenReturn(new PreparedTtsClient.Reply("fixture-provider",12,"stop",URI.create("https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/file.wav"),1791108000));
        when(store.putBroadcast(any(),eq(target))).thenAnswer(i->{var scope=i.getArgument(0,PreparedPrivateOssStore.Broadcast.class);
            return new PreparedPrivateOssStore.Stored(scope,scope.key("prepared-fixture"),proof);});
        when(papers.load(21)).thenReturn(new PreparedPaper.Draft(21,0,List.of(new PreparedPaper.Question(qid,"MANUAL","题目文字",1,10,120,"评分标准",true,"PENDING",null))));
        audio=mock(PreparedAudioRepository.class);when(audio.partIsWanted(lease.job().id())).thenReturn(true);
        worker=new PreparedTtsWorker(ledger,policy,client,storagePolicy,store,spool,papers,access,audio);
    }
    @Test void disabledEmptyOrStorageUnavailableDoesNotTriggerACharge() {
        when(policy.workerEnabled()).thenReturn(false);worker.runOnce();verifyNoInteractions(ledger,client,store);
        when(policy.workerEnabled()).thenReturn(true);when(ledger.claim(Kind.TTS,"tts-worker",300)).thenReturn(Optional.empty());worker.runOnce();verifyNoInteractions(client,store);
        when(ledger.claim(Kind.TTS,"tts-worker",300)).thenReturn(Optional.of(lease));when(storagePolicy.snapshot()).thenThrow(new IllegalArgumentException("disabled"));
        worker.runOnce();verifyNoInteractions(client,store);verify(ledger).fail(lease,"CONFIG_OR_ACCESS_CHANGED",false);
    }
    @Test void authenticatesBoundsReservesDispatchesSettlesThenDownloadsAndVerifiesStorage() throws Exception {
        worker.runOnce();var order=inOrder(access,ledger,spool,client,store);
        order.verify(access).requireAssessmentManager(4,21);order.verify(spool).stage(lease);
        order.verify(ledger).initializeBudget(anyString(),eq(new BigDecimal("100")),eq(new BigDecimal("80")));
        order.verify(ledger).reserve(eq(lease),anyString(),anyString(),eq(input.policy().maximumCost()));
        order.verify(ledger).markDispatched(eq(lease),anyString());order.verify(client).synthesize(eq(input.policy()),eq("题目文字"),eq("fixture-key"));
        order.verify(ledger).settle(anyString(),eq(new BigDecimal("0.00096")),eq("fixture-provider"));
        order.verify(client).download(any(),eq(target));order.verify(store).putBroadcast(any(),eq(target));
        order.verify(ledger).succeed(eq(lease),anyString());order.verify(spool).release(lease,target);
        verify(papers,never()).replaceDraft(any(),anyLong(),anyInt());
    }
    @Test void unknownResponseKeepsPossibleChargeAndDoesNotAttemptDownloadOrReplay() throws Exception {
        when(client.synthesize(any(),anyString(),anyString())).thenThrow(new java.io.IOException("private-provider-data"));worker.runOnce();
        verify(ledger).uncertain(anyString());verify(ledger).fail(lease,"AMBIGUOUS_CALL",false);
        verify(ledger,never()).cancelBeforeDispatch(anyString());verify(client,never()).download(any(),any());verifyNoInteractions(store);
    }
    @Test void chargedBadOutputDownloadOrStorageFailureIsNeverAutomaticallyPaidAgain() throws Exception {
        when(client.download(any(),any())).thenThrow(new java.io.IOException("invalid media"));worker.runOnce();
        verify(ledger).settle(anyString(),any(),eq("fixture-provider"));verify(ledger).fail(lease,"AUDIO_SAVE_FAILED",false);
        verify(ledger,never()).uncertain(anyString());verify(spool,never()).release(any(),any());
        reset(client,ledger);when(ledger.claim(Kind.TTS,"tts-worker",300)).thenReturn(Optional.of(lease));
        when(ledger.reserve(eq(lease),anyString(),anyString(),any())).thenReturn(new Decision(true,""));when(ledger.markDispatched(eq(lease),anyString())).thenReturn(true);
        when(client.synthesize(any(),anyString(),anyString())).thenReturn(new PreparedTtsClient.Reply("fixture-provider",12,"length",null,0));worker.runOnce();
        verify(ledger).settle(anyString(),any(),eq("fixture-provider"));verify(ledger).fail(lease,"INVALID_OUTPUT",false);
        verify(client,never()).download(any(),any());
    }
    @Test void budgetPauseOrDispatchFenceDeniesCallingTheProvider() {
        when(ledger.reserve(eq(lease),anyString(),anyString(),any())).thenReturn(new Decision(false,"PAUSED"));worker.runOnce();verifyNoInteractions(client);
        verify(ledger).fail(lease,"BUDGET_PAUSED",false);
        when(ledger.reserve(eq(lease),anyString(),anyString(),any())).thenReturn(new Decision(true,""));when(ledger.markDispatched(eq(lease),anyString())).thenReturn(false);
        worker.runOnce();verifyNoInteractions(client);verify(ledger).cancelBeforeDispatch(anyString());
    }
    @Test void editedDeletedOrRevokedQuestionCannotCauseAPaidCall() {
        when(papers.load(21)).thenReturn(new PreparedPaper.Draft(21,1,List.of()));worker.runOnce();verifyNoInteractions(client,store);
        verify(ledger).fail(lease,"CONFIG_OR_ACCESS_CHANGED",false);
        doThrow(new IllegalArgumentException("revoked")).when(access).requireAssessmentManager(4,21);worker.runOnce();verifyNoInteractions(client);
    }
    @Test void staleWorkerAfterAChargeCannotUploadOrPublishAndRetainsItsStagedContent() throws Exception {
        when(ledger.heartbeat(lease,300)).thenReturn(false);worker.runOnce();verifyNoInteractions(store);
        verify(spool,never()).release(any(),any());verify(ledger,never()).succeed(any(),anyString());
    }
    @Test void partInputAndProofEncodingAreStrictAndKeepSensitiveDataOutOfDiagnostics() {
        assertThat(PreparedSpeech.part(PreparedSpeech.encode(input))).isEqualTo(input);
        assertThat(input.toString()).doesNotContain("题目文字");
        assertThatThrownBy(()->PreparedSpeech.part("{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedSpeech.part(PreparedSpeech.encode(new PreparedSpeech.PartInput(2,qid,input.assetId(),input.bodySha256(),0,1,"题目文字",input.policy())))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void malformedPartBoundariesHashesPoliciesAndTextNeverPassPersistedInputValidation() {
        for(var invalid:List.of(new PreparedSpeech.PartInput(1,qid,input.assetId(),null,0,1,"题目文字",input.policy()),
                new PreparedSpeech.PartInput(1,qid,input.assetId(),"bad",0,1,"题目文字",input.policy()),
                new PreparedSpeech.PartInput(1,qid,input.assetId(),input.bodySha256(),0,0,"题目文字",input.policy()),
                new PreparedSpeech.PartInput(1,qid,input.assetId(),input.bodySha256(),0,51,"题目文字",input.policy()),
                new PreparedSpeech.PartInput(1,qid,input.assetId(),input.bodySha256(),-1,1,"题目文字",input.policy()),
                new PreparedSpeech.PartInput(1,qid,input.assetId(),input.bodySha256(),1,1,"题目文字",input.policy()),
                new PreparedSpeech.PartInput(1,qid,input.assetId(),input.bodySha256(),0,1,"题目文字",null),
                new PreparedSpeech.PartInput(1,qid,input.assetId(),input.bodySha256(),0,1,"x".repeat(601),input.policy())))
            assertThatThrownBy(()->PreparedSpeech.part(PreparedSpeech.encode(invalid))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void changedPartContentCannotSilentlyBecomeAPaidRequest() {
        var other=new PreparedSpeech.PartInput(1,qid,input.assetId(),input.bodySha256(),0,2,"不同文字",input.policy());
        lease=new Lease(new Job(lease.job().id(),21,4,Kind.TTS,0,PreparedSpeech.encode(other),null,State.RUNNING,1),1,"tts-worker");
        when(ledger.claim(Kind.TTS,"tts-worker",300)).thenReturn(Optional.of(lease));worker.runOnce();verifyNoInteractions(client,store);
        verify(ledger).fail(lease,"CONFIG_OR_ACCESS_CHANGED",false);
    }
    @Test void lostCompletionReceiptDoesNotDeleteTheKnownSavedStage() throws Exception {
        when(ledger.succeed(eq(lease),anyString())).thenReturn(false);worker.runOnce();verify(store).putBroadcast(any(),eq(target));
        verify(spool,never()).release(any(),any());
    }
    @Test void abandonedWholeTaskOrFailedSiblingCannotTriggerAdditionalPaidParts(){
        when(audio.partIsWanted(lease.job().id())).thenReturn(false);worker.runOnce();verifyNoInteractions(client,store,spool);
        verify(ledger).fail(lease,"CONFIG_OR_ACCESS_CHANGED",false);verify(ledger,never()).reserve(any(),anyString(),anyString(),any());
    }
}
