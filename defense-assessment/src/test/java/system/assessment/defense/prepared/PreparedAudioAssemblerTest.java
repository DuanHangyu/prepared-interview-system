package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.prepared.audio.*;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.*;
import system.assessment.defense.prepared.media.*;
import system.assessment.defense.prepared.speech.*;
import system.assessment.defense.prepared.storage.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Composition and recovery orchestration with explicit storage doubles, not cloud acceptance. */
class PreparedAudioAssemblerTest {
    @TempDir Path folder;PreparedJobLedger jobs;PreparedAudioService service;PreparedPrivateOssStore store;PreparedStoragePolicy policy;
    PreparedSpeechSpool spool;PreparedAudioAssembler worker;MockEnvironment env;Lease lease;Path target;PreparedAudio.WholeInput input;
    List<PreparedPrivateOssStore.Stored> parts;byte[] wave;
    @BeforeEach void setup() throws Exception{
        folder=folder.toRealPath();jobs=mock(PreparedJobLedger.class);service=mock(PreparedAudioService.class);store=mock(PreparedPrivateOssStore.class);policy=mock(PreparedStoragePolicy.class);
        String q=UUID.randomUUID().toString(),asset=UUID.randomUUID().toString();String body="题目。".repeat(220);wave=new PreparedSpeechMediaTest().wave();
        input=new PreparedAudio.WholeInput(1,q,asset,body,PreparedJobInput.digest(body),"1".repeat(64),List.of(UUID.randomUUID().toString(),UUID.randomUUID().toString()));
        lease=new Lease(new Job(UUID.randomUUID().toString(),22,6,Kind.ASSEMBLE,2,PreparedSpeech.encode(input),null,State.RUNNING,1),1,"audio-assembler");
        when(jobs.claim(Kind.ASSEMBLE,"audio-assembler",300)).thenReturn(Optional.of(lease));when(jobs.heartbeat(lease,300)).thenReturn(true);
        env=new MockEnvironment().withProperty("prepared.speech.assembly-worker-enabled","true").withProperty("prepared.speech.spool-root",folder.resolve("prepared-tts-spool").toString());
        spool=new PreparedSpeechSpool(env);target=folder.resolve("prepared-tts-spool").resolve(lease.job().id()).resolve("generation-1/broadcast.wav");
        Path source=folder.resolve("fixture.wav");Files.write(source,wave);var proof=PreparedWaveAudio.inspect(source);
        parts=input.orderedPartJobs().stream().map(id->{var scope=new PreparedPrivateOssStore.Broadcast(22,q,id);return new PreparedPrivateOssStore.Stored(scope,scope.key("prepared-fixture"),proof);}).toList();
        when(service.assemblyParts(lease)).thenReturn(parts);when(policy.snapshot()).thenReturn(new PreparedStoragePolicy.Snapshot("https://oss-cn-beijing.aliyuncs.com","prepared-interview-fixture","prepared-fixture"));
        when(store.download(any(),any())).thenAnswer(i->{Path path=i.getArgument(1);Files.write(path,wave,StandardOpenOption.CREATE_NEW);return proof;});
        when(store.putBroadcast(any(),any())).thenAnswer(i->{var scope=i.getArgument(0,PreparedPrivateOssStore.Broadcast.class);Path path=i.getArgument(1);
            var joined=PreparedWaveAudio.inspect(path);assertThat(joined.durationMs()).isEqualTo(2000);assertThat(joined.dataSize()).isEqualTo(96000);
            return new PreparedPrivateOssStore.Stored(scope,scope.key("prepared-fixture"),joined);});
        worker=new PreparedAudioAssembler(jobs,service,store,policy,spool,env);
    }
    @Test void fullQuestionCompositionIsOrderedVerifiedAttachedAndOnlyThenCleaned() throws Exception{
        worker.runOnce();verify(store,times(2)).download(any(),any());verify(service).complete(eq(lease),any());
        assertThat(target).doesNotExist();assertThat(target.getParent()).doesNotExist();assertThat(folder.resolve("fixture.wav")).exists();
        verify(jobs,never()).reserve(any(),anyString(),anyString(),any());
    }
    @Test void disabledEmptyOrInvalidParentNeverDownloadsOrCallsAModel(){
        env.withProperty("prepared.speech.assembly-worker-enabled","false");worker.runOnce();verifyNoInteractions(jobs,service,store);
        env.withProperty("prepared.speech.assembly-worker-enabled","true");when(jobs.claim(Kind.ASSEMBLE,"audio-assembler",300)).thenReturn(Optional.empty());worker.runOnce();verifyNoInteractions(service,store);
        when(jobs.claim(Kind.ASSEMBLE,"audio-assembler",300)).thenReturn(Optional.of(lease));when(service.assemblyParts(lease)).thenThrow(new IllegalArgumentException("invalid"));worker.runOnce();
        verify(jobs).fail(lease,"ASSEMBLY_BINDING_INVALID",false);verifyNoInteractions(store);
    }
    @Test void transientStorageFailureRetainsPaidPartsAndRequestsOnlyBoundedAssemblyRetry() throws Exception{
        doThrow(new java.io.IOException("private-storage-info")).when(store).putBroadcast(any(),any());worker.runOnce();
        verify(jobs).fail(lease,"ASSEMBLY_SAVE_FAILED",true);verify(service,never()).complete(any(),any());assertThat(target).exists();
        verify(jobs,never()).reserve(any(),anyString(),anyString(),any());
    }
    @Test void staleLeaseOrUncommittedProofNeverDeletesSavedStaging() throws Exception{
        when(jobs.heartbeat(lease,300)).thenReturn(false);worker.runOnce();verifyNoInteractions(store);verify(service,never()).complete(any(),any());
        when(jobs.heartbeat(lease,300)).thenReturn(true);doThrow(new IllegalStateException("db")).when(service).complete(any(),any());worker.runOnce();assertThat(target).exists();
        verify(jobs).fail(lease,"ASSEMBLY_SAVE_FAILED",true);
    }
    @Test void storageReturningAnotherAssetOrCorruptProofCannotBecomeReady() throws Exception{
        doReturn(parts.getFirst()).when(store).putBroadcast(any(),any());worker.runOnce();verify(service,never()).complete(any(),any());
        verify(jobs).fail(lease,"ASSEMBLY_SAVE_FAILED",true);assertThat(target).exists();
    }
    @Test void wrongPartCountOrCleanupFailureNeverTurnsIntoAPaidReplay() throws Exception{
        when(service.assemblyParts(lease)).thenReturn(List.of(parts.getFirst()));worker.runOnce();verify(jobs).fail(lease,"ASSEMBLY_BINDING_INVALID",false);verifyNoInteractions(store);
        when(service.assemblyParts(lease)).thenReturn(parts);spool=spy(spool);doThrow(new java.io.IOException("cleanup")).when(spool).releaseComposition(eq(lease),any(),eq(2));
        worker=new PreparedAudioAssembler(jobs,service,store,policy,spool,env);worker.runOnce();verify(service).complete(eq(lease),any());assertThat(target).exists();
        verify(jobs,never()).fail(lease,"ASSEMBLY_SAVE_FAILED",true);
    }
    @ParameterizedTest @ValueSource(ints={2,4,5}) void expiredLeaseBetweenStagesStopsBeforeRemoteSave(int stop) throws Exception{
        int[] calls={0};when(jobs.heartbeat(lease,300)).thenAnswer(i->++calls[0]!=stop);worker.runOnce();
        verify(store,never()).putBroadcast(any(),any());verify(service,never()).complete(any(),any());verify(jobs,never()).fail(any(),anyString(),anyBoolean());
    }
    @Test void ownAssetWithWrongObjectKeyOrFullProofCannotBePublished() throws Exception{
        doAnswer(i->{var scope=i.getArgument(0,PreparedPrivateOssStore.Broadcast.class);return new PreparedPrivateOssStore.Stored(scope,"prepared/wrong/key.wav",parts.getFirst().proof());}).when(store).putBroadcast(any(),any());
        worker.runOnce();verify(service,never()).complete(any(),any());verify(jobs).fail(lease,"ASSEMBLY_SAVE_FAILED",true);
    }
}
