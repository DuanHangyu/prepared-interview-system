package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.prepared.audio.*;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.*;
import system.assessment.defense.prepared.media.*;
import system.assessment.defense.prepared.paper.*;
import system.assessment.defense.prepared.publication.*;
import system.assessment.defense.prepared.speech.*;
import system.assessment.defense.prepared.storage.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Deliberately corrupt internal records must fail closed. Not a cloud or database acceptance. */
class PreparedAudioBoundaryTest {
    PreparedJobLedger jobs;PreparedAudioRepository repo;PreparedAudioService service;PreparedPaperRepository papers;
    PreparedTtsPolicy policy;PreparedSpeechSpool spool;PreparedPublicationRepository publications;PreparedStoragePolicy storage;
    String q=UUID.randomUUID().toString(),a=UUID.randomUUID().toString(),id=UUID.randomUUID().toString(),child=UUID.randomUUID().toString();
    String body="题目",hash=PreparedJobInput.digest(body);PreparedTtsPolicy.Snapshot config;
    PreparedSpeech.PartInput part;PreparedSpeech.PartOutput output;PreparedAudio.WholeInput input;
    PreparedAudioRepository.Asset asset;PreparedAudio.View view;PreparedPrivateOssStore.Stored saved;PreparedWaveAudio.Verified proof;
    Lease lease;Job childJob;
    @BeforeEach void setup(){
        jobs=mock(PreparedJobLedger.class);repo=mock(PreparedAudioRepository.class);papers=mock(PreparedPaperRepository.class);policy=mock(PreparedTtsPolicy.class);
        spool=mock(PreparedSpeechSpool.class);publications=mock(PreparedPublicationRepository.class);storage=mock(PreparedStoragePolicy.class);
        var access=mock(ResourceAccessControlService.class);config=new PreparedTtsAdapterTest().policy().snapshot();
        when(policy.snapshot()).thenReturn(config);when(spool.configurationAvailable()).thenReturn(true);when(spool.assemblyEnabled()).thenReturn(true);
        when(jobs.initializedBudget(anyString())).thenReturn(Optional.empty());
        when(storage.snapshot()).thenReturn(new PreparedStoragePolicy.Snapshot("https://oss-cn-beijing.aliyuncs.com","prepared-interview-fixture","prepared-fixture"));
        service=new PreparedAudioService(access,papers,publications,repo,jobs,policy,storage,spool);
        part=new PreparedSpeech.PartInput(1,q,UUID.randomUUID().toString(),hash,0,1,body,config);
        proof=new PreparedWaveAudio.Verified(48044,"e".repeat(64),1000,24000,1,44,48000);
        var scope=new PreparedPrivateOssStore.Broadcast(21,q,part.assetId());output=new PreparedSpeech.PartOutput(1,q,part.assetId(),scope.key("prepared-fixture"),proof);
        input=new PreparedAudio.WholeInput(1,q,a,body,hash,PreparedAudio.configuration(config),List.of(child));
        lease=new Lease(new Job(id,21,4,Kind.ASSEMBLE,2,PreparedSpeech.encode(input),null,State.RUNNING,1),1,"audio-assembler");
        childJob=new Job(child,21,4,Kind.TTS,2,PreparedSpeech.encode(part),PreparedSpeech.encode(output),State.SUCCEEDED,1);
        when(jobs.get(child)).thenAnswer(i->Optional.of(childJob));when(jobs.get(id)).thenReturn(Optional.of(lease.job()));when(jobs.heartbeat(lease,300)).thenReturn(true);
        when(jobs.succeed(eq(lease),anyString())).thenReturn(true);
        view=new PreparedAudio.View(a,q,hash,id,"GENERATING",1,1,1,new BigDecimal("0.048"),null,false,null,null,"2026-10-04T08:00:00Z");
        asset=new PreparedAudioRepository.Asset(a,21,q,hash,input.configSha256(),id,"BUILDING",null,null,view);
        when(repo.get(21,a)).thenAnswer(i->Optional.of(asset));when(repo.partJobs(21,a)).thenReturn(List.of(child));
        when(papers.loadForUpdate(21)).thenReturn(new PreparedPaper.Draft(21,2,List.of(new PreparedPaper.Question(q,"MANUAL",body,1,10,120,"标准",true,"PENDING",null))));
        when(papers.replaceDraft(any(),anyLong(),anyInt())).thenReturn(true);when(publications.gate(21)).thenReturn(new PreparedPublicationRepository.Gate(null,false));
        var wholeScope=new PreparedPrivateOssStore.Broadcast(21,q,a);saved=new PreparedPrivateOssStore.Stored(wholeScope,wholeScope.key("prepared-fixture"),proof);
    }
    @Test void validBoundaryReturnsOnlyItsExactOrderedStoredParts(){assertThat(service.assemblyParts(lease)).hasSize(1);}
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7,8,9,10,11}) void invalidChildBindingNeverComposes(int mutation){
        String foreign=UUID.randomUUID().toString();
        var altered=switch(mutation){
            case 3->new PreparedSpeech.PartInput(1,foreign,part.assetId(),hash,0,1,body,config);
            case 4->new PreparedSpeech.PartInput(1,q,part.assetId(),"0".repeat(64),0,1,body,config);
            case 5->new PreparedSpeech.PartInput(1,q,part.assetId(),hash,0,1,body,new PreparedTtsPolicy.Snapshot(config.endpoint(),config.model(),"another-voice",config.language(),config.billingReference(),config.pricingReference(),config.verifiedAt(),config.maxBilledCharacters(),config.characterRate(),config.budgetScope()));
            case 6->new PreparedSpeech.PartInput(1,q,part.assetId(),hash,1,2,body,config);
            case 7->new PreparedSpeech.PartInput(1,q,part.assetId(),hash,0,2,body,config);
            case 8->new PreparedSpeech.PartInput(1,q,part.assetId(),hash,0,1,"另一段文字",config);
            default->part;
        };
        var out=switch(mutation){case 9->new PreparedSpeech.PartOutput(1,foreign,part.assetId(),output.objectKey(),proof);
            case 10->new PreparedSpeech.PartOutput(1,q,foreign,output.objectKey(),proof);
            case 11->new PreparedSpeech.PartOutput(1,q,part.assetId(),"prepared/another/object.wav",proof);default->output;};
        childJob=new Job(child,mutation==0?22:21,4,mutation==1?Kind.SCORE:Kind.TTS,2,PreparedSpeech.encode(altered),PreparedSpeech.encode(out),mutation==2?State.NEEDS_REVIEW:State.SUCCEEDED,1);
        assertThatThrownBy(()->service.assemblyParts(lease)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7,8}) void invalidParentBindingNeverComposes(int mutation){
        if(mutation==0)lease=new Lease(new Job(id,21,4,Kind.TTS,2,PreparedSpeech.encode(input),null,State.RUNNING,1),1,"audio-assembler");
        else if(mutation==7)when(repo.partJobs(21,a)).thenReturn(List.of(UUID.randomUUID().toString()));
        else if(mutation==8)input=new PreparedAudio.WholeInput(1,q,a,body,hash,input.configSha256(),List.of(child,UUID.randomUUID().toString()));
        else asset=new PreparedAudioRepository.Asset(a,mutation==1?22:21,mutation==2?UUID.randomUUID().toString():q,
                    mutation==3?"0".repeat(64):hash,mutation==4?"0".repeat(64):input.configSha256(),mutation==5?UUID.randomUUID().toString():id,
                    mutation==6?"FAILED":"BUILDING",null,null,view);
        if(mutation==8)lease=new Lease(new Job(id,21,4,Kind.ASSEMBLE,2,PreparedSpeech.encode(input),null,State.RUNNING,1),1,"audio-assembler");
        assertThatThrownBy(()->service.assemblyParts(lease)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7,8,9,10}) void invalidCompletionNeverWritesAReadyProof(int mutation){
        if(mutation==0)asset=new PreparedAudioRepository.Asset(a,22,q,hash,input.configSha256(),id,"BUILDING",null,null,view);
        if(mutation==1)asset=new PreparedAudioRepository.Asset(a,21,UUID.randomUUID().toString(),hash,input.configSha256(),id,"BUILDING",null,null,view);
        if(mutation==2)asset=new PreparedAudioRepository.Asset(a,21,q,hash,input.configSha256(),UUID.randomUUID().toString(),"BUILDING",null,null,view);
        if(mutation==3)asset=new PreparedAudioRepository.Asset(a,21,q,"0".repeat(64),input.configSha256(),id,"BUILDING",null,null,view);
        if(mutation==4)asset=new PreparedAudioRepository.Asset(a,21,q,hash,"0".repeat(64),id,"BUILDING",null,null,view);
        if(mutation==5)saved=null;
        if(mutation==6)saved=new PreparedPrivateOssStore.Stored(new PreparedPrivateOssStore.Broadcast(22,q,a),saved.objectKey(),proof);
        if(mutation==7)saved=new PreparedPrivateOssStore.Stored(saved.scope(),"prepared/another/key.wav",proof);
        if(mutation==8)saved=new PreparedPrivateOssStore.Stored(saved.scope(),saved.objectKey(),null);
        if(mutation==9)when(repo.partJobs(21,a)).thenReturn(List.of(UUID.randomUUID().toString()));
        if(mutation==10)when(jobs.heartbeat(lease,300)).thenReturn(false);
        assertThatThrownBy(()->service.complete(lease,saved)).isInstanceOf(RuntimeException.class);verify(repo,never()).ready(anyInt(),anyString(),anyString(),any());
    }
    @Test void pauseAndReadinessAreReadOnlyAndNeverResetTheSharedBudget(){
        assertThat(service.availability().enabled()).isTrue();
        when(jobs.initializedBudget(anyString())).thenReturn(Optional.of(new Budget(config.budgetScope(),new BigDecimal("100"),new BigDecimal("80"),BigDecimal.ZERO,BigDecimal.ZERO,true)));
        assertThat(service.availability().enabled()).isFalse();
        when(jobs.initializedBudget(anyString())).thenReturn(Optional.of(new Budget(config.budgetScope(),new BigDecimal("100"),new BigDecimal("80"),new BigDecimal("70"),new BigDecimal("10"),false)));
        assertThat(service.availability().enabled()).isFalse();
        when(jobs.initializedBudget(anyString())).thenReturn(Optional.of(new Budget(config.budgetScope(),new BigDecimal("100"),new BigDecimal("80"),new BigDecimal("1"),BigDecimal.ZERO,false)));
        assertThat(service.availability().enabled()).isTrue();verify(jobs,never()).initializeBudget(anyString(),any(),any());
        when(spool.configurationAvailable()).thenReturn(false);assertThat(service.availability().enabled()).isFalse();
    }
    @ParameterizedTest @ValueSource(ints={0,1,2}) void invalidPublicCommandsNeverEnqueue(int mutation){
        var c=mutation==0?null:new PreparedAudio.Command(mutation==1?null:-1L,UUID.randomUUID().toString(),q,"e".repeat(64));
        assertThatThrownBy(()->service.prepare(4,21,c)).isInstanceOf(IllegalArgumentException.class);verify(jobs,never()).enqueue(anyInt(),anyInt(),any(),anyLong(),anyString(),anyString());
        var r=mutation==0?null:new PreparedAudio.Recovery(mutation==1?null:-1L,UUID.randomUUID().toString());
        assertThatThrownBy(()->service.recover(4,21,a,r)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingRecoveryStorageIsAnUnavailableServiceNotAnInvalidTeacherCommand(){
        var failed=new PreparedAudio.View(a,q,hash,id,"FAILED",1,1,0,BigDecimal.ZERO,"ASSEMBLY_SAVE_FAILED",true,null,null,view.createdAt());
        asset=new PreparedAudioRepository.Asset(a,21,q,hash,input.configSha256(),id,"BUILDING",null,null,failed);
        when(storage.snapshot()).thenThrow(new IllegalArgumentException("disabled"));
        assertThatThrownBy(()->service.recover(4,21,a,new PreparedAudio.Recovery(2L,UUID.randomUUID().toString())))
                .isInstanceOf(system.assessment.defense.infrastructure.exception.BusinessException.class).extracting("code").isEqualTo(503);
        verify(repo,never()).failed(anyInt(),anyString());
    }
}
