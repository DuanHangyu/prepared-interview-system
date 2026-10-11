package system.assessment.defense.prepared.processing;

import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Browser commands must explicitly include their original revision; no scalar coercion,
 * extra identity/permission fields, duplicate keys, trailing JSON or malformed UTF-8. */
public final class PreparedReviewCommands {
    private PreparedReviewCommands(){}
    private record Confirm(String idempotencyKey,Long expectedRevision,String scoreId,String reason){}
    private record Regrade(String idempotencyKey,Long expectedRevision,String transcriptId,String reason){}
    private record Adjust(String idempotencyKey,Long expectedRevision,java.math.BigDecimal score,String reason){}
    public static PreparedProcessing.AdjustCommand adjust(byte[] bytes){
        var c=PreparedProcessingCodec.decode(raw(bytes),Adjust.class);
        if(c.expectedRevision()==null)throw new IllegalArgumentException("请提供明确的原评阅版本");
        var command=new PreparedProcessing.AdjustCommand(c.idempotencyKey(),c.expectedRevision(),c.score(),c.reason());PreparedManualScoreStore.validate(command);return command;
    }
    public static PreparedProcessing.ConfirmCommand confirm(byte[] bytes){var c=PreparedProcessingCodec.decode(raw(bytes),Confirm.class);validate(c.idempotencyKey(),c.expectedRevision(),c.scoreId(),c.reason());return new PreparedProcessing.ConfirmCommand(c.idempotencyKey(),c.expectedRevision(),c.scoreId(),c.reason());}
    public static PreparedProcessing.RegradeCommand regrade(byte[] bytes){var c=PreparedProcessingCodec.decode(raw(bytes),Regrade.class);validate(c.idempotencyKey(),c.expectedRevision(),c.transcriptId(),c.reason());return new PreparedProcessing.RegradeCommand(c.idempotencyKey(),c.expectedRevision(),c.transcriptId(),c.reason());}
    private static void validate(String key,Long revision,String source,String reason){
        if(revision==null||revision<0||revision>9007199254740991L)throw new IllegalArgumentException("请提供明确的原评阅版本");PreparedJobInput.uuid(key);PreparedJobInput.uuid(source);PreparedProcessingCodec.text(reason,1000);
    }
    private static String raw(byte[] bytes){
        if(bytes==null||bytes.length>16384)throw new IllegalArgumentException("评阅命令过大或为空");
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid){throw new IllegalArgumentException("评阅命令必须为有效 UTF-8");}
    }
}
