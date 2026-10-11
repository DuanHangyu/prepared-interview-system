package system.assessment.defense.prepared.attempt;

import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.processing.PreparedProcessingCodec;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;

/** A teacher approval is permission to backfill an immutable original, never a recording or storage receipt. */
public final class PreparedSupplement {
    private PreparedSupplement(){}
    public record Command(String idempotencyKey,long expectedRevision,long expectedAttemptRevision,String manifestSha256,String uploadDeadline,String reason){
        public Command{
            PreparedJobInput.uuid(idempotencyKey);PreparedProcessingCodec.sha(manifestSha256);PreparedProcessingCodec.text(reason,1000);
            if(expectedRevision<0||expectedRevision>=9007199254740991L||expectedAttemptRevision<0||expectedAttemptRevision>=9007199254740991L)throw invalid();
            uploadDeadline=deadline(uploadDeadline).toString();
        }
    }
    private record Raw(String idempotencyKey,Long expectedRevision,Long expectedAttemptRevision,String manifestSha256,String uploadDeadline,String reason){}
    public record Receipt(String id,int actorId,int studentId,int assessmentId,String attemptId,String answerId,String paperVersionId,
                          long approvalRevision,long attemptRevision,String manifestSha256,String answerDeadline,String answeringEndedAt,
                          String automaticDeadline,String approvedAt,String uploadDeadline,String reason,String idempotencyKey){}
    public record View(int actorId,int studentId,int assessmentId,String attemptId,String answerId,long approvalRevision,long attemptRevision,
                       String answerState,String manifestSha256,String answerDeadline,String automaticDeadline,String serverNow,
                       String phase,boolean mayApprove,String eligibilityIssue,Receipt latest){}
    public static Command decode(byte[] bytes){
        if(bytes==null||bytes.length>16384)throw invalid();
        try{
            String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var c=PreparedProcessingCodec.decode(raw,Raw.class);
            if(c.expectedRevision()==null||c.expectedAttemptRevision()==null)throw invalid();
            return new Command(c.idempotencyKey(),c.expectedRevision(),c.expectedAttemptRevision(),c.manifestSha256(),c.uploadDeadline(),c.reason());
        }catch(CharacterCodingException error){throw invalid();}
    }
    static Instant deadline(String value){
        try{var time=Instant.parse(value);int year=time.atOffset(ZoneOffset.UTC).getYear();
            if(year<1000||year>9999||time.getNano()%1000000!=0||!value.endsWith("Z"))throw invalid();return time;
        }catch(RuntimeException failure){throw invalid();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("补传审批必须提供原版本、原清单、明确截止和核验原因");}
}
