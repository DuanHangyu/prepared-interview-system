package system.assessment.defense.prepared.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.util.*;

/** Immutable, bounded metadata declarations. No byte/format verification, object URLs or offline authenticity proof. */
public final class PreparedAnswerEnd {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> MIMES=Set.of("audio/webm;codecs=opus","audio/webm","audio/ogg;codecs=opus","audio/ogg","audio/mp4");
    private static final Set<String> FAULTS=Set.of("PAGE_REFRESH","DEVICE_ENDED","CAPTURE_ERROR","LOCAL_WRITE_FAILED","SESSION_CHANGED");
    private PreparedAnswerEnd() {}
    public record Chunk(int index,int byteSize,String sha256) {}
    public record Segment(String id,int ordinal,String mimeType,Long startedAt,Long endedAt,String state,String interruption,
                          int chunkCount,long byteSize,List<Chunk> chunks) {
        public Segment{if(chunks!=null)chunks=Collections.unmodifiableList(new ArrayList<>(chunks));}
    }
    public record Manifest(int schemaVersion,List<Segment> segments) {
        public Manifest{if(segments!=null)segments=Collections.unmodifiableList(new ArrayList<>(segments));}
    }
    public record Command(String idempotencyKey,String captureClientId,Manifest manifest) {
        public void validate(){PreparedJobInput.uuid(idempotencyKey);PreparedJobInput.uuid(captureClientId);}
    }
    public record Proof(String json,String sha256,long byteSize,List<String> reviewReasons) {public Proof{reviewReasons=List.copyOf(reviewReasons);}}
    /** declarationState/revision describe the original end command, NOT the latest storage/submission result. */
    public record Summary(String answerId,String manifestSha256,String declarationState,long revision,String effectiveEndedAt,String acceptedAt,
                          List<String> reviewReasons,boolean serverSaved,String attemptState,long attemptRevision,String answeringEndedAt,String supplementalUploadDeadline) {
        public Summary{reviewReasons=List.copyOf(reviewReasons);}
    }
    public static Proof inspect(Manifest manifest,int answerSeconds) {
        require(manifest!=null&&manifest.schemaVersion()==1&&manifest.segments()!=null&&!manifest.segments().isEmpty()&&manifest.segments().size()<=1000);
        require(answerSeconds>=1&&answerSeconds<=1800);
        var ids=new HashSet<String>();var reasons=new LinkedHashSet<String>();long bytes=0,priorEnd=-1,firstStart=-1;int ordinal=1,chunks=0;
        for(var segment:manifest.segments()) {
            require(segment!=null);PreparedJobInput.uuid(segment.id());require(ids.add(segment.id())&&segment.ordinal()==ordinal++);
            require(segment.mimeType()!=null&&MIMES.contains(segment.mimeType()));timestamp(segment.startedAt());timestamp(segment.endedAt());
            require(segment.endedAt()>=segment.startedAt()&&segment.startedAt()>=priorEnd);if(firstStart<0)firstStart=segment.startedAt();priorEnd=segment.endedAt();
            require(segment.chunks()!=null&&segment.chunkCount()==segment.chunks().size()&&segment.chunkCount()>=0&&segment.chunkCount()<=10000);
            require(segment.byteSize()>=0&&segment.byteSize()<=67108864);long segmentBytes=0;int index=0;
            for(var chunk:segment.chunks()) {
                require(chunk!=null&&chunk.index()==index++&&chunk.byteSize()>0&&chunk.byteSize()<=2097152&&chunk.sha256()!=null&&chunk.sha256().matches("[a-f0-9]{64}"));
                segmentBytes+=chunk.byteSize();require(++chunks<=10000);
            }
            require(segmentBytes==segment.byteSize());bytes+=segmentBytes;require(bytes<=67108864);
            if("SEALED".equals(segment.state()))require(segment.interruption()==null&&segment.chunkCount()>0);
            else {require("INTERRUPTED".equals(segment.state())&&segment.interruption()!=null&&FAULTS.contains(segment.interruption()));reasons.add("CAPTURE_INTERRUPTED");}
        }
        if(bytes==0)reasons.add("EMPTY_AUDIO");
        if(priorEnd-firstStart>(long)answerSeconds*1000)reasons.add("DECLARED_TIME_EXCEEDS_LIMIT");
        String json=encode(manifest);return new Proof(json,PreparedJobInput.digest(json),bytes,List.copyOf(reasons));
    }
    public static String encode(Manifest manifest) {
        try{return PreparedJobInput.json(JSON.writeValueAsString(manifest),2*1024*1024);}
        catch(java.io.IOException failure){throw new IllegalArgumentException("录音结束清单格式无效");}
    }
    public static Manifest decode(String json) {
        try{return JSON.readValue(PreparedJobInput.json(json,2*1024*1024),Manifest.class);}
        catch(java.io.IOException failure){throw new IllegalStateException("原录音清单无法核验");}
    }
    private static void timestamp(Long value){require(value!=null&&value>=0&&value<=8640000000000000L);}
    private static void require(boolean value){if(!value)throw new IllegalArgumentException("录音结束清单无效，请保留原内容");}
}
