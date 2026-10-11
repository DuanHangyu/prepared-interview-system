package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.attempt.PreparedAnswerEnd.*;

class PreparedAnswerManifestTest {
    Segment segment(){return new Segment(UUID.randomUUID().toString(),1,"audio/webm;codecs=opus",1000L,2000L,"SEALED",null,1,3L,List.of(new Chunk(0,3,"a".repeat(64))));}
    @Test void canonicalManifestIsImmutableAndHasBoundedTypedMetadataNotUrlsOrServerSavedClaims(){
        var mutable=new ArrayList<>(List.of(segment()));var manifest=new Manifest(1,mutable);mutable.clear();
        assertThat(inspect(manifest,120).byteSize()).isEqualTo(3);assertThat(inspect(manifest,120).reviewReasons()).isEmpty();
        assertThat(inspect(manifest,120).json()).doesNotContain("objectKey","url","serverSaved");
    }
    @Test void rejectsEmptyWrongVersionOpenFakeMimeMissingOrInconsistentChunksDuplicateIdsAndBadTiming(){
        var good=segment();var wrong=new ArrayList<Manifest>();wrong.add(new Manifest(2,List.of(good)));wrong.add(new Manifest(1,List.of()));
        wrong.add(new Manifest(1,List.of(good,good)));
        for(String state:List.of("OPEN","SAVED"))wrong.add(new Manifest(1,List.of(new Segment(good.id(),1,good.mimeType(),1000L,2000L,state,null,1,3L,good.chunks()))));
        wrong.add(new Manifest(1,List.of(new Segment(good.id(),2,good.mimeType(),1000L,2000L,"SEALED",null,1,3L,good.chunks()))));
        wrong.add(new Manifest(1,List.of(new Segment(good.id(),1,"video/webm",1000L,2000L,"SEALED",null,1,3L,good.chunks()))));
        wrong.add(new Manifest(1,List.of(new Segment(good.id(),1,good.mimeType(),2000L,1000L,"SEALED",null,1,3L,good.chunks()))));
        wrong.add(new Manifest(1,List.of(new Segment(good.id(),1,good.mimeType(),1000L,2000L,"SEALED",null,2,3L,good.chunks()))));
        wrong.add(new Manifest(1,List.of(new Segment(good.id(),1,good.mimeType(),1000L,2000L,"SEALED",null,1,4L,good.chunks()))));
        wrong.add(new Manifest(1,List.of(new Segment(good.id(),1,good.mimeType(),1000L,2000L,"SEALED","DEVICE_ENDED",1,3L,good.chunks()))));
        for(var invalid:wrong)assertThatThrownBy(()->inspect(invalid,120)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->inspect(null,120)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void interruptedSegmentsAndDeclaredOverrunAreReviewEvidenceNotAnExcuseToDeleteData(){
        var interrupted=new Segment(UUID.randomUUID().toString(),1,"audio/webm",1000L,2000L,"INTERRUPTED","PAGE_REFRESH",0,0L,List.of());
        var finalSegment=new Segment(UUID.randomUUID().toString(),2,"audio/webm",3000L,122001L,"SEALED",null,1,3L,List.of(new Chunk(0,3,"a".repeat(64))));
        var proof=inspect(new Manifest(1,List.of(interrupted,finalSegment)),120);assertThat(proof.reviewReasons()).contains("CAPTURE_INTERRUPTED","DECLARED_TIME_EXCEEDS_LIMIT");assertThat(proof.byteSize()).isEqualTo(3);
    }
    @Test void rejectsWrongChunkOrderSizeDigestOrExcessiveSegmentCount(){
        var good=segment();for(var chunk:List.of(new Chunk(1,3,"a".repeat(64)),new Chunk(0,0,"a".repeat(64)),new Chunk(0,2097153,"a".repeat(64)),new Chunk(0,3,"not-a-hash")))
            assertThatThrownBy(()->inspect(new Manifest(1,List.of(new Segment(good.id(),1,good.mimeType(),1000L,2000L,"SEALED",null,1,3L,List.of(chunk)))),120)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->inspect(new Manifest(1,Collections.nCopies(1001,good)),120)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsMissingFieldsIllegalTimestampsOverlapsAndFalseCompleteEmptyDeclarations(){
        var good=segment();var invalid=new ArrayList<Segment>();
        invalid.add(null);
        invalid.add(new Segment(good.id(),1,null,1000L,2000L,"SEALED",null,1,3L,good.chunks()));
        for(Long start:Arrays.asList(null,-1L,8640000000000001L))invalid.add(new Segment(good.id(),1,good.mimeType(),start,2000L,"SEALED",null,1,3L,good.chunks()));
        invalid.add(new Segment(good.id(),1,good.mimeType(),1000L,null,"SEALED",null,1,3L,good.chunks()));
        invalid.add(new Segment(good.id(),1,good.mimeType(),1000L,2000L,"SEALED",null,1,3L,null));
        for(long size:new long[]{-1,67108865})invalid.add(new Segment(good.id(),1,good.mimeType(),1000L,2000L,"SEALED",null,1,size,good.chunks()));
        invalid.add(new Segment(good.id(),1,good.mimeType(),1000L,2000L,"SEALED",null,1,3L,Collections.singletonList(null)));
        invalid.add(new Segment(good.id(),1,good.mimeType(),1000L,2000L,"SEALED",null,1,3L,List.of(new Chunk(0,3,null))));
        invalid.add(new Segment(good.id(),1,good.mimeType(),1000L,2000L,"SEALED",null,0,0L,List.of()));
        for(String reason:Arrays.asList(null,"UNKNOWN"))invalid.add(new Segment(good.id(),1,good.mimeType(),1000L,2000L,"INTERRUPTED",reason,1,3L,good.chunks()));
        for(var value:invalid)assertThatThrownBy(()->inspect(new Manifest(1,Collections.singletonList(value)),120)).isInstanceOf(IllegalArgumentException.class);
        var overlap=new Segment(UUID.randomUUID().toString(),2,good.mimeType(),1500L,3000L,"SEALED",null,1,3L,good.chunks());
        assertThatThrownBy(()->inspect(new Manifest(1,List.of(good,overlap)),120)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->inspect(new Manifest(1,null),120)).isInstanceOf(IllegalArgumentException.class);
        for(int seconds:new int[]{0,1801})assertThatThrownBy(()->inspect(new Manifest(1,List.of(good)),seconds)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void enforcesWholeAnswerChunkAndByteCapsAcrossSegments(){
        var small=new ArrayList<Chunk>();for(int i=0;i<6000;i++)small.add(new Chunk(i,1,"a".repeat(64)));
        var first=new Segment(UUID.randomUUID().toString(),1,"audio/webm",0L,1000L,"SEALED",null,6000,6000,small);
        var second=new Segment(UUID.randomUUID().toString(),2,"audio/webm",1000L,2000L,"SEALED",null,6000,6000,small);
        final var tooManyChunks=new Manifest(1,List.of(first,second));
        assertThatThrownBy(()->inspect(tooManyChunks,120)).isInstanceOf(IllegalArgumentException.class);
        var big=new ArrayList<Chunk>();for(int i=0;i<32;i++)big.add(new Chunk(i,2097152,"a".repeat(64)));
        first=new Segment(UUID.randomUUID().toString(),1,"audio/webm",0L,1000L,"SEALED",null,32,67108864,big);
        second=new Segment(UUID.randomUUID().toString(),2,"audio/webm",1000L,2000L,"SEALED",null,1,3,List.of(new Chunk(0,3,"a".repeat(64))));
        final var oversized=new Manifest(1,List.of(first,second));assertThatThrownBy(()->inspect(oversized,120)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->decode("{\"schemaVersion\":1,\"segments\":\"not-an-array\"}")).isInstanceOf(IllegalStateException.class);
    }
}
