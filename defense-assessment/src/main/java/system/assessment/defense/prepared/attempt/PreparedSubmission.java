package system.assessment.defense.prepared.attempt;

import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.util.List;

/** Student receipt contains no grading rules, storage addresses or task inputs. */
public final class PreparedSubmission {
    private PreparedSubmission() {}
    public record Command(String idempotencyKey,Long expectedRevision) {
        public Command {PreparedJobInput.uuid(idempotencyKey);if(expectedRevision==null||expectedRevision<0)throw new IllegalArgumentException("提交状态版本缺失或无效");}
    }
    public record Pending(int ordinal,String answerId,String phase) {}
    public record Receipt(String id,String attemptId,String paperVersionId,long revision,int answerCount,String submittedAt) {}
    public record Summary(String attemptId,String paperVersionId,long revision,String phase,boolean submitted,List<Pending> pending,Receipt receipt) {
        public Summary {pending=List.copyOf(pending);}
    }
    /** Immutable server-derived outbox input. Workers must reload scoped original media and
     * the frozen publication; the browser/model cannot supply a URL, rubric or new recording. */
    public record Work(int schemaVersion,int student,int assessment,String attempt,String paperVersion,String submission,
                       String answer,String question,int ordinal,long answerRevision,String manifestSha256,String mediaJob,String mediaProofSha256) {}
    public record Binding(Work input,String transcribeJob,String scoreJob) {}
}
