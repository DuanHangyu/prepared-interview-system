package system.assessment.defense.prepared.retention;

import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.processing.PreparedProcessingCodec;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Set;

/** Manual protection of an original attempt, never permission to delete or restart it. */
public final class PreparedRetentionProtection {
    private PreparedRetentionProtection() {}
    public record Command(String idempotencyKey,long expectedRevision,String action,String reason){
        public Command {
            PreparedJobInput.uuid(idempotencyKey);PreparedProcessingCodec.text(reason,1000);
            if(expectedRevision<0||expectedRevision>=9007199254740991L||action==null||!Set.of("PROTECT","RELEASE").contains(action))throw invalid();
        }
    }
    private record Raw(String idempotencyKey,Long expectedRevision,String action,String reason) {}
    public record Receipt(String id,int actorId,int studentId,int assessmentId,String attemptId,String action,
            long expectedRevision,long revision,boolean held,String executedAt,String reason,String idempotencyKey,
            String audioState,String resultsState) {}
    public record View(int actorId,int studentId,int assessmentId,String attemptId,long revision,boolean held,
            String audioState,String resultsState,boolean mayChange,String serverNow,Receipt latest) {}
    public static Command decode(byte[] bytes){
        if(bytes==null||bytes.length>16384)throw invalid();
        try {
            var raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var input=PreparedProcessingCodec.decode(raw,Raw.class);
            if(input.expectedRevision()==null)throw invalid();
            return new Command(input.idempotencyKey(),input.expectedRevision(),input.action(),input.reason());
        }catch(CharacterCodingException failure){throw invalid();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("留存保护必须提供原版本、明确操作与核验原因");}
}
