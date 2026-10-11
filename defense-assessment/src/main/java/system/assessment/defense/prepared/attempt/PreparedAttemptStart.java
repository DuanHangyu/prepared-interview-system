package system.assessment.defense.prepared.attempt;

import system.assessment.defense.prepared.jobs.PreparedJobInput;

/** Durable start metadata only. Not a question grant, writer lease, upload confirmation or result. */
public final class PreparedAttemptStart {
    private PreparedAttemptStart() {}
    public record Command(String idempotencyKey) {
        public void validate() { PreparedJobInput.uuid(idempotencyKey); }
    }
    public record Summary(String id, int recordId, int assessmentId, int attemptNo, String paperVersionId,
                          String state, long revision, String startedAt, String answeringEndedAt,
                          String supplementalUploadDeadline) {}
}
