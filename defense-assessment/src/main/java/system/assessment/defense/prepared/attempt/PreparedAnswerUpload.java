package system.assessment.defense.prepared.attempt;

import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore.AnswerChunk;

/** Whitelisted original byte claims; client input never includes an object path, user identity or saved flag. */
public final class PreparedAnswerUpload {
    private PreparedAnswerUpload() {}
    public record Command(String captureClientId,String segmentId,int segmentOrdinal,String mimeType,int index,int byteSize,String sha256) {
        public AnswerChunk scope(int student,int assessment,String attempt,String answer) {
            PreparedJobInput.uuid(captureClientId);
            if(segmentOrdinal<1||segmentOrdinal>1000)throw new IllegalArgumentException("原录音分段题序无效");
            return new AnswerChunk(assessment,student,attempt,answer,segmentId,index,byteSize,sha256,mimeType);
        }
    }
    /** A committed receipt for ONE byte chunk. Never implies decodable media, answer SAVED or final submission. */
    public record Receipt(String answerId,String segmentId,int index,int byteSize,String sha256,boolean confirmed,String confirmedAt) {}
}
