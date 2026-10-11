package system.assessment.defense.prepared.retention;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore.*;
import system.assessment.defense.prepared.media.PreparedWaveAudio;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedOriginalAudioInventoryTest {
    String attempt=UUID.randomUUID().toString(),answer=UUID.randomUUID().toString(),segment=UUID.randomUUID().toString(),deployment="prepared-fixture";
    AnswerChunk chunk(){return new AnswerChunk(4,2,attempt,answer,segment,0,2,"a".repeat(64),"audio/webm");}
    @Test void physicalItemTypesDeriveKeysFromOriginalScopesAndNeverAcceptForeignOrArbitraryLocators(){
        var chunk=chunk();var single=new PreparedOriginalAudioInventory.Single(new StoredChunk(chunk,chunk.key(deployment)),deployment);
        assertThat(single.kind()).isEqualTo("CHUNK");assertThat(single.key()).isEqualTo(chunk.key(deployment));assertThat(single.byteSize()).isEqualTo(2);assertThat(single.sha256()).isEqualTo(chunk.sha256());
        var group=new AnswerBundle("b".repeat(64),List.of(chunk),2,chunk.sha256());var bundle=new PreparedOriginalAudioInventory.Bundle(new StoredBundle(group,group.key(deployment)),deployment);
        assertThat(bundle.kind()).isEqualTo("BUNDLE");assertThat(bundle.byteSize()).isEqualTo(2);assertThat(bundle.sha256()).isEqualTo(chunk.sha256());assertThat(bundle.key()).isEqualTo(group.key(deployment));
        var scope=new AnswerFile(4,2,attempt,answer,"c".repeat(64));var proof=new PreparedWaveAudio.Verified(32044,"d".repeat(64),1000,16000,1,44,32000);
        var whole=new PreparedOriginalAudioInventory.Whole(new StoredAnswer(scope,scope.key(deployment),proof),deployment);
        assertThat(whole.kind()).isEqualTo("ANSWER");assertThat(whole.byteSize()).isEqualTo(32044);assertThat(whole.sha256()).isEqualTo(proof.sha256());assertThat(whole.key()).isEqualTo(scope.key(deployment));
        assertThatThrownBy(()->new PreparedOriginalAudioInventory.Single(new StoredChunk(chunk,"arbitrary"),deployment)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedOriginalAudioInventory.Bundle(new StoredBundle(group,"arbitrary"),deployment)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedOriginalAudioInventory.Whole(new StoredAnswer(scope,"arbitrary",proof),deployment)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedOriginalAudioInventory.Single(null,deployment)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedOriginalAudioInventory.Bundle(null,deployment)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedOriginalAudioInventory.Whole(null,deployment)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedOriginalAudioInventory.Whole(new StoredAnswer(scope,scope.key(deployment),null),deployment)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedOriginalAudioInventory.Single(new StoredChunk(null,"key"),deployment)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedOriginalAudioInventory.Bundle(new StoredBundle(null,"key"),deployment)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedOriginalAudioInventory.Whole(new StoredAnswer(null,"key",proof),deployment)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedOriginalAudioInventory.Single(new StoredChunk(chunk,chunk.key(deployment)),"prepared-other")).isInstanceOf(IllegalArgumentException.class);
        for(var item:List.of(single,bundle,whole))assertThat(item.toString()).doesNotContain(item.key(),"a".repeat(64),"d".repeat(64));
    }
    @Test void snapshotIsImmutableAndDigestBindsActualPhysicalScopeBytesAndType(){
        var original=chunk();var single=new PreparedOriginalAudioInventory.Single(new StoredChunk(original,original.key(deployment)),deployment);var list=new ArrayList<PreparedOriginalAudioInventory.Item>(List.of(single));
        var snapshot=PreparedOriginalAudioInventory.snapshot(list);list.clear();assertThat(snapshot.objects()).containsExactly(single);assertThat(snapshot.byteSize()).isEqualTo(2);
        assertThatThrownBy(()->snapshot.objects().clear()).isInstanceOf(UnsupportedOperationException.class);
        var changed=new AnswerChunk(4,2,attempt,answer,segment,0,3,"b".repeat(64),"audio/webm");
        assertThat(PreparedOriginalAudioInventory.snapshot(List.of(new PreparedOriginalAudioInventory.Single(new StoredChunk(changed,changed.key(deployment)),deployment))).sha256()).isNotEqualTo(snapshot.sha256());
        assertThatThrownBy(()->PreparedOriginalAudioInventory.snapshot(List.of(single,single))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->PreparedOriginalAudioInventory.snapshot(List.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->PreparedOriginalAudioInventory.snapshot(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->PreparedOriginalAudioInventory.snapshot(Collections.nCopies(500051,single))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->PreparedOriginalAudioInventory.snapshot(Arrays.asList((PreparedOriginalAudioInventory.Item)null))).isInstanceOf(IllegalStateException.class);
    }
    @Test void malformedCanonicalMediaProofNeverBecomesAnOriginalWholeObject(){
        var scope=new AnswerFile(4,2,attempt,answer,"c".repeat(64));String key=scope.key(deployment);
        for(var proof:List.of(
            new PreparedWaveAudio.Verified(32044,null,1000,16000,1,44,32000),
            new PreparedWaveAudio.Verified(32044,"bad",1000,16000,1,44,32000),
            new PreparedWaveAudio.Verified(45,"a".repeat(64),1,16000,1,44,1),
            new PreparedWaveAudio.Verified(67108865,"a".repeat(64),1000,16000,1,44,67108821),
            new PreparedWaveAudio.Verified(32044,"a".repeat(64),1000,24000,1,44,32000),
            new PreparedWaveAudio.Verified(32044,"a".repeat(64),1000,16000,2,44,32000),
            new PreparedWaveAudio.Verified(32044,"a".repeat(64),1000,16000,1,45,32000),
            new PreparedWaveAudio.Verified(32044,"a".repeat(64),1000,16000,1,44,32002),
            new PreparedWaveAudio.Verified(32045,"a".repeat(64),1000,16000,1,44,32001),
            new PreparedWaveAudio.Verified(32044,"a".repeat(64),0,16000,1,44,32000),
            new PreparedWaveAudio.Verified(32044,"a".repeat(64),1001,16000,1,44,32000)))
            assertThatThrownBy(()->new PreparedOriginalAudioInventory.Whole(new StoredAnswer(scope,key,proof),deployment)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void physicalDigestIsIndependentOfDatabaseRowOrderingAndChangesAcrossOriginalScopes(){
        var original=chunk();var first=new PreparedOriginalAudioInventory.Single(new StoredChunk(original,original.key(deployment)),deployment);
        var next=new AnswerChunk(4,2,attempt,answer,segment,1,2,"a".repeat(64),"audio/webm");
        var second=new PreparedOriginalAudioInventory.Single(new StoredChunk(next,next.key(deployment)),deployment);
        assertThat(PreparedOriginalAudioInventory.snapshot(List.of(first,second))).isEqualTo(PreparedOriginalAudioInventory.snapshot(List.of(second,first)));
        var foreign=new AnswerChunk(4,2,UUID.randomUUID().toString(),answer,segment,0,2,"a".repeat(64),"audio/webm");
        assertThat(PreparedOriginalAudioInventory.snapshot(List.of(new PreparedOriginalAudioInventory.Single(new StoredChunk(foreign,foreign.key(deployment)),deployment))).sha256())
            .isNotEqualTo(PreparedOriginalAudioInventory.snapshot(List.of(first)).sha256());
    }
    @Test void originalReaderRejectsMissingOrOversizedSourceCollectionsBeforeReadingAnyLayout(){
        var submissions=mock(PreparedSubmissionService.class);var jdbc=mock(JdbcTemplate.class);var reader=new PreparedOriginalAudioInventory(submissions,jdbc);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{
            when(submissions.retentionSources(2,4,attempt)).thenReturn(new PreparedSubmissionService.ReviewSources(null,null,List.of()));
            assertThatThrownBy(()->reader.read(2,4,attempt)).isInstanceOf(IllegalStateException.class);
            var work=new PreparedSubmission.Work(1,2,4,attempt,null,null,answer,null,1,1,"a".repeat(64),null,null);
            var source=new PreparedSubmissionService.ProcessingSource(new PreparedSubmission.Binding(work,null,null),null,null);
            when(submissions.retentionSources(2,4,attempt)).thenReturn(new PreparedSubmissionService.ReviewSources(null,null,Collections.nCopies(51,source)));
            assertThatThrownBy(()->reader.read(2,4,attempt)).isInstanceOf(IllegalStateException.class);verifyNoInteractions(jdbc);
        }finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void originalScopeFieldsCannotBeReplacedByAnOtherwiseWellShapedInput(){
        var submissions=mock(PreparedSubmissionService.class);var jdbc=mock(JdbcTemplate.class);var reader=new PreparedOriginalAudioInventory(submissions,jdbc);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{
            for(var scope:List.of(new Object[]{3,4,attempt},new Object[]{2,5,attempt},new Object[]{2,4,UUID.randomUUID().toString()})){
                var work=new PreparedSubmission.Work(1,(Integer)scope[0],(Integer)scope[1],(String)scope[2],null,null,answer,null,1,1,"a".repeat(64),null,null);
                var source=new PreparedSubmissionService.ProcessingSource(new PreparedSubmission.Binding(work,null,null),null,null);
                when(submissions.retentionSources(2,4,attempt)).thenReturn(new PreparedSubmissionService.ReviewSources(null,null,List.of(source)));
                assertThatThrownBy(()->reader.read(2,4,attempt)).isInstanceOf(IllegalStateException.class);
            }verifyNoInteractions(jdbc);
        }finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
}
