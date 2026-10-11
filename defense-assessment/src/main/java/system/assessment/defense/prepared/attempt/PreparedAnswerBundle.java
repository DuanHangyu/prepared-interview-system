package system.assessment.defense.prepared.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore.*;
import java.util.*;

/** Immutable bounded transport command; it does not replace the original end manifest. */
public final class PreparedAnswerBundle {
    private static final ObjectMapper JSON=new ObjectMapper();
    private PreparedAnswerBundle(){}
    public record Chunk(int index,int byteSize,String sha256){}
    public record Command(String captureClientId,String segmentId,int segmentOrdinal,String mimeType,List<Chunk> chunks,int byteSize,String sha256) {
        public Command {if(chunks!=null)chunks=Collections.unmodifiableList(new ArrayList<>(chunks));}
        public AnswerBundle scope(int student,int assessment,String attempt,String answer) {
            PreparedJobInput.uuid(captureClientId);
            if(segmentOrdinal<1||segmentOrdinal>1000||chunks==null||chunks.isEmpty()||chunks.size()>32)throw new IllegalArgumentException("原录音批次清单无效");
            var scopes=chunks.stream().map(c->{if(c==null)throw new IllegalArgumentException("原录音批次清单无效");return new AnswerChunk(assessment,student,attempt,answer,segmentId,c.index(),c.byteSize(),c.sha256(),mimeType);}).toList();
            String id=PreparedJobInput.digest("prepared-answer-bundle-v1\n"+student+"\n"+assessment+"\n"+attempt+"\n"+answer+"\n"+encode(this));
            return new AnswerBundle(id,scopes,byteSize,sha256);
        }
        public List<PreparedAnswerUpload.Command> originalChunks(){return chunks.stream().map(c->new PreparedAnswerUpload.Command(captureClientId,segmentId,segmentOrdinal,mimeType,c.index(),c.byteSize(),c.sha256())).toList();}
    }
    public record Summary(String bundleId,String answerId,String commandSha256,int byteSize,String sha256,boolean confirmed,List<PreparedAnswerUpload.Receipt> receipts) {
        public Summary{receipts=List.copyOf(receipts);}
    }
    public static String encode(Command command){try{return PreparedJobInput.json(JSON.writeValueAsString(command),32768);}catch(java.io.IOException error){throw new IllegalArgumentException("原录音批次清单无效");}}
    public static Command decode(String json){try{return JSON.readValue(PreparedJobInput.json(json,32768),Command.class);}catch(java.io.IOException|IllegalArgumentException error){throw new IllegalStateException("原录音批次无法核验");}}
    public static void id(String id){if(id==null||!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("原录音批次标识无效");}
}
