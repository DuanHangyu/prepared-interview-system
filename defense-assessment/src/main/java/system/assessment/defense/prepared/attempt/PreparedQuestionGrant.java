package system.assessment.defense.prepared.attempt;

import system.assessment.defense.prepared.jobs.PreparedJobInput;

/** Student-only single-question projection. captureClientId coordinates writers; it is NOT an authentication secret. */
public final class PreparedQuestionGrant {
    private PreparedQuestionGrant() {}
    public record Command(String idempotencyKey,String captureClientId,int ordinal) {
        public void validate() {
            PreparedJobInput.uuid(idempotencyKey);PreparedJobInput.uuid(captureClientId);
            if(ordinal<1||ordinal>50)throw new IllegalArgumentException("题序无效");
        }
    }
    public record Grant(int assessmentId,String attemptId,String paperVersionId,String answerId,String questionId,
                        int ordinal,String body,int answerSeconds,String state,String startedAt,String answerDeadline,
                        String serverTime,boolean mayRecord) {}
}
