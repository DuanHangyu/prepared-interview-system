package system.assessment.defense.prepared.retention;

import system.assessment.defense.prepared.jobs.PreparedJobInput;

/** Internal metadata-only preparation. There is deliberately no execute/delete
 * command or object locator. Cancelling is safe ONLY before a separate irreversible
 * execution intent exists; future actual IO must revalidate all copies, work,
 * costs and protection under locks. Original preparation receipts never change. */
public final class PreparedAudioRetirementPreparation {
    private PreparedAudioRetirementPreparation(){}
    public record Command(String idempotencyKey,long expectedRevision){
        public Command{
            PreparedJobInput.uuid(idempotencyKey);
            // Cancel may use the penultimate safe revision; stage reserves it.
            if(expectedRevision<0||expectedRevision>=9007199254740991L)throw new IllegalArgumentException("原录音准备版本无效");
        }
    }
    public record Receipt(String id,int studentId,int assessmentId,String attemptId,String idempotencyKey,
            long expectedRevision,long revision,String audioDueAt,String preparedAt,String evidenceSha256){}
    public record Cancellation(String preparationId,int studentId,int assessmentId,String attemptId,String idempotencyKey,
            long expectedRevision,long revision,String cancelledAt){}
    public record View(String state,Receipt preparation,Cancellation cancellation){}
}
