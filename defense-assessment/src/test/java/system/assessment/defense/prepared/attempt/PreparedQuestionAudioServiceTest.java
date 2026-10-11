package system.assessment.defense.prepared.attempt;

import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.audio.*;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.media.PreparedWaveAudio;
import system.assessment.defense.prepared.publication.*;
import system.assessment.defense.prepared.storage.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit repository/storage doubles; not real cloud, device or end-to-end acceptance. */
class PreparedQuestionAudioServiceTest {
    final String attempt=id(),answer=id(),client=id(),version=id(),question=id(),assetId=id(),jobId=id(),part=id();
    final Clock clock=Clock.fixed(Instant.parse("2026-10-05T01:00:00Z"),ZoneOffset.UTC);
    PreparedQuestionGrantService grants;PreparedAudioRepository assets;PreparedJobLedger jobs;
    PreparedPublicationRepository publications;PreparedStoragePolicy policy;PreparedPrivateOssStore store;
    PreparedQuestionAudioService service;PreparedQuestionGrantService.AudioGrant grant;
    PreparedAudioRepository.Asset asset;PreparedJobLedger.Job job;PreparedPrivateOssStore.Stored saved;
    PreparedPublicationRepository.Asset published;
    @BeforeEach void setup()throws Exception {
        grants=mock(PreparedQuestionGrantService.class);assets=mock(PreparedAudioRepository.class);jobs=mock(PreparedJobLedger.class);
        publications=mock(PreparedPublicationRepository.class);policy=mock(PreparedStoragePolicy.class);store=mock(PreparedPrivateOssStore.class);
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service=new PreparedQuestionAudioService(grants,assets,jobs,publications,policy,store,manager,clock);
        String body="请介绍你的项目",bodySha=PreparedJobInput.digest(body),config="c".repeat(64),content="b".repeat(64);
        var descriptor=new PreparedPublication.Audio(assetId,bodySha,config,content,32044,1000,"audio/wav");
        grant=new PreparedQuestionGrantService.AudioGrant(version,question,body,descriptor);
        var scope=new PreparedPrivateOssStore.Broadcast(21,question,assetId);
        var proof=new PreparedWaveAudio.Verified(32044,content,1000,16000,1,44,32000);
        saved=new PreparedPrivateOssStore.Stored(scope,scope.key("prepared-fixture"),proof);
        var view=new PreparedAudio.View(assetId,question,bodySha,jobId,"READY",1,1,1,BigDecimal.ZERO,null,false,32044L,1000L,clock.instant().toString());
        asset=new PreparedAudioRepository.Asset(assetId,21,question,bodySha,config,jobId,"READY",saved.objectKey(),proof,view);
        String input=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new PreparedAudio.WholeInput(1,question,assetId,body,bodySha,config,List.of(part)));
        job=new PreparedJobLedger.Job(jobId,21,7,PreparedJobLedger.Kind.ASSEMBLE,1,input,"{}",PreparedJobLedger.State.SUCCEEDED,1);
        published=new PreparedPublicationRepository.Asset(assetId,21,question,bodySha,config,saved.objectKey(),content,32044,1000,"audio/wav");
        when(grants.audioGrant(8,21,attempt,answer,client)).thenReturn(grant);
        when(assets.get(21,assetId)).thenReturn(Optional.of(asset));when(jobs.get(jobId)).thenReturn(Optional.of(job));
        when(assets.partJobs(21,assetId)).thenReturn(List.of(part));when(publications.verifiedAsset(21,question,saved.objectKey())).thenReturn(Optional.of(published));
        when(policy.snapshot()).thenReturn(new PreparedStoragePolicy.Snapshot("https://oss-cn-beijing.aliyuncs.com","prepared-interview-fixture","prepared-fixture"));
        when(store.preview(saved)).thenReturn(new PreparedPrivateOssStore.Preview(url(),clock.instant().plusSeconds(300).toString()));
    }
    @Test void bindsFrozenOriginalAndRechecksAuthorizationAfterIoWithoutStartingQuestionsOrCallingModels()throws Exception {
        var result=read();assertThat(result.studentId()).isEqualTo(8);assertThat(result.paperVersionId()).isEqualTo(version);
        assertThat(result.questionId()).isEqualTo(question);assertThat(result.assetId()).isEqualTo(assetId);assertThat(result.captureClientId()).isEqualTo(client);
        assertThat(result.sha256()).isEqualTo(saved.proof().sha256());assertThat(result.toString()).doesNotContain("Signature","private");
        var order=inOrder(grants,store);order.verify(grants).audioGrant(8,21,attempt,answer,client);order.verify(store).preview(saved);order.verify(grants).audioGrant(8,21,attempt,answer,client);
        verify(grants,never()).start(anyInt(),anyInt(),anyString(),any());verifyNoMoreInteractions(store);
    }
    @Test void authorizationFailureNeverOpensStorageAndRevocationDuringIoNeverReturnsTheCapability() {
        when(grants.audioGrant(8,21,attempt,answer,client)).thenThrow(new BusinessException(403,"范围撤销"));
        assertThatThrownBy(this::read).isInstanceOf(BusinessException.class);verifyNoInteractions(store);
        doReturn(grant).doThrow(new BusinessException(403,"范围撤销")).when(grants).audioGrant(8,21,attempt,answer,client);
        assertThatThrownBy(this::read).isInstanceOf(BusinessException.class).hasMessageNotContaining("Signature");
    }
    @Test void absentUnverifiedStaleCrossQuestionAndCrossDeploymentAssetsCannotBeSigned() {
        when(assets.get(21,assetId)).thenReturn(Optional.empty());unavailable();
        when(assets.get(21,assetId)).thenReturn(Optional.of(asset));when(publications.verifiedAsset(21,question,saved.objectKey())).thenReturn(Optional.empty());unavailable();
        when(publications.verifiedAsset(21,question,saved.objectKey())).thenReturn(Optional.of(published));
        when(assets.get(21,assetId)).thenReturn(Optional.of(new PreparedAudioRepository.Asset(asset.id(),21,question,asset.bodySha(),asset.configSha(),jobId,"READY",asset.key(),null,asset.view())));unavailable();
        when(assets.get(21,assetId)).thenReturn(Optional.of(asset));when(jobs.get(jobId)).thenReturn(Optional.of(new PreparedJobLedger.Job(jobId,22,7,job.kind(),1,job.inputJson(),"{}",job.state(),1)));unavailable();
        when(jobs.get(jobId)).thenReturn(Optional.of(new PreparedJobLedger.Job(jobId,21,7,PreparedJobLedger.Kind.TTS,1,job.inputJson(),"{}",job.state(),1)));unavailable();
        when(jobs.get(jobId)).thenReturn(Optional.of(job));when(assets.partJobs(21,assetId)).thenReturn(List.of(id()));unavailable();
        when(assets.partJobs(21,assetId)).thenReturn(List.of(part));when(policy.snapshot()).thenReturn(new PreparedStoragePolicy.Snapshot("https://oss-cn-beijing.aliyuncs.com","prepared-interview-fixture","prepared-another"));unavailable();
        verifyNoInteractions(store);
    }
    @Test void failedOrMalformedSigningIsSanitizedAndNeverChangesRecordingOrRetriesAutomatically()throws Exception {
        when(store.preview(saved)).thenThrow(new IOException("secret-signature"));unavailable();
        for(String expiry:List.of("bad",clock.instant().toString(),clock.instant().plusSeconds(301).toString())){
            doReturn(new PreparedPrivateOssStore.Preview(url(),expiry)).when(store).preview(saved);unavailable();
        }
        for(String url:List.of(url().replace("https:","http:"),url().replace("prepared-interview-fixture","evil"),url().replace(question,id()),url()+"#fragment",url().split("\\?")[0],
                url().replace("https://","https://user@"),url().replace(".com/",".com:8443/"),url().split("\\?")[0]+"?",url()+"x".repeat(4096))){
            doReturn(new PreparedPrivateOssStore.Preview(url,clock.instant().plusSeconds(300).toString())).when(store).preview(saved);unavailable();
        }
        verify(grants,never()).start(anyInt(),anyInt(),anyString(),any());
    }
    @Test void changedFrozenProofDuringIoAndAnOuterTransactionFailClosed()throws Exception {
        when(grants.audioGrant(8,21,attempt,answer,client)).thenReturn(grant).thenReturn(new PreparedQuestionGrantService.AudioGrant(id(),question,grant.body(),grant.audio()));
        unavailable();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);}finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void everyFrozenAssetBindingAndCompletedAssemblyFieldIsRequiredBeforePrivateIo()throws Exception {
        for(var change:Map.of("id",id(),"assessment",22,"question",id(),"bodySha","a".repeat(64),"configSha","d".repeat(64),"storedState","BUILDING","key","foreign/key").entrySet()){
            when(assets.get(21,assetId)).thenReturn(Optional.of(alter(asset,change.getKey(),change.getValue())));unavailable();
        }
        for(var change:Map.of("state","GENERATING","partCount",2,"completedParts",0).entrySet()){
            when(assets.get(21,assetId)).thenReturn(Optional.of(alter(asset,"view",alter(asset.view(),change.getKey(),change.getValue()))));unavailable();
        }
        for(var change:Map.of("sha256","a".repeat(64),"byteSize",32046L,"durationMs",1001L).entrySet()){
            when(assets.get(21,assetId)).thenReturn(Optional.of(alter(asset,"proof",alter(asset.proof(),change.getKey(),change.getValue()))));unavailable();
        }
        when(assets.get(21,assetId)).thenReturn(Optional.of(asset));
        for(var change:Map.of("kind",PreparedJobLedger.Kind.TTS,"state",PreparedJobLedger.State.QUEUED,"assessmentId",22,"inputJson","{}").entrySet()){
            when(jobs.get(jobId)).thenReturn(Optional.of(alter(job,change.getKey(),change.getValue())));unavailable();
        }
        var input=PreparedAudio.whole(job.inputJson());
        for(var change:Map.of("assetId",id(),"questionId",id(),"configSha256","d".repeat(64)).entrySet()){
            String changed=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(alter(input,change.getKey(),change.getValue()));
            when(jobs.get(jobId)).thenReturn(Optional.of(alter(job,"inputJson",changed)));unavailable();
        }
        var otherBody=alter(alter(input,"body","另一题正文"),"bodySha256",PreparedJobInput.digest("另一题正文"));
        when(jobs.get(jobId)).thenReturn(Optional.of(alter(job,"inputJson",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(otherBody))));unavailable();
        when(jobs.get(jobId)).thenReturn(Optional.of(job));
        for(var change:Map.of("id",id(),"assessmentId",22,"questionId",id(),"contentSha256","a".repeat(64)).entrySet()){
            when(publications.verifiedAsset(21,question,saved.objectKey())).thenReturn(Optional.of(alter(published,change.getKey(),change.getValue())));unavailable();
        }
        verifyNoInteractions(store);
    }
    @SuppressWarnings("unchecked") static <T>T alter(T source,String field,Object replacement)throws Exception {
        var components=source.getClass().getRecordComponents();var types=new Class<?>[components.length];var values=new Object[components.length];
        for(int i=0;i<components.length;i++){types[i]=components[i].getType();values[i]=components[i].getName().equals(field)?replacement:components[i].getAccessor().invoke(source);}
        return (T)source.getClass().getDeclaredConstructor(types).newInstance(values);
    }
    @Test void expiryDuringFinalReadIsNotDeliveredAndSuccessfulReplyUsesServerDeliveryTime()throws Exception {
        var now=new java.util.concurrent.atomic.AtomicReference<>(clock.instant());var moving=new Clock(){
            @Override public ZoneId getZone(){return ZoneOffset.UTC;}
            @Override public Clock withZone(ZoneId zone){return this;}
            @Override public Instant instant(){return now.get();}
        };
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service=new PreparedQuestionAudioService(grants,assets,jobs,publications,policy,store,manager,moving);
        var calls=new java.util.concurrent.atomic.AtomicInteger();doAnswer(i->{if(calls.incrementAndGet()%2==0)now.set(clock.instant().plusSeconds(300));return grant;}).when(grants).audioGrant(8,21,attempt,answer,client);
        unavailable();now.set(clock.instant());
        doAnswer(i->{if(calls.incrementAndGet()%2==0)now.set(clock.instant().plusSeconds(5));return grant;}).when(grants).audioGrant(8,21,attempt,answer,client);
        var response=read();assertThat(response.serverTime()).isEqualTo(now.get().toString());assertThat(Duration.between(Instant.parse(response.serverTime()),Instant.parse(response.expiresAt()))).isEqualTo(Duration.ofSeconds(295));
    }
    @Test void boundsConcurrentReadbacksAndImmediatelyReleasesCapacityAfterFailure()throws Exception {
        var entered=new CountDownLatch(4);var release=new CountDownLatch(1);
        when(store.preview(saved)).thenAnswer(i->{entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return new PreparedPrivateOssStore.Preview(url(),clock.instant().plusSeconds(300).toString());});
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var pending=new ArrayList<Future<PreparedQuestionAudioService.Audio>>();for(int i=0;i<4;i++)pending.add(executor.submit(this::read));
            try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();unavailable();}finally{release.countDown();}
            for(var call:pending)assertThat(call.get(5,TimeUnit.SECONDS)).isNotNull();
        }
        doThrow(new IOException()).when(store).preview(saved);unavailable();doReturn(new PreparedPrivateOssStore.Preview(url(),clock.instant().plusSeconds(300).toString())).when(store).preview(saved);assertThat(read()).isNotNull();
    }
    void unavailable(){assertThatThrownBy(this::read).isInstanceOf(BusinessException.class).hasMessageNotContaining("Signature").hasMessageNotContaining("secret").extracting("code").isEqualTo(503);}
    PreparedQuestionAudioService.Audio read(){return service.preview(8,21,attempt,answer,client);}
    String url(){return "https://prepared-interview-fixture.oss-cn-beijing.aliyuncs.com/"+saved.objectKey()+"?Signature=private";}
    static String id(){return UUID.randomUUID().toString();}
}
