package system.assessment.defense.prepared.retention;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static system.assessment.defense.prepared.retention.PreparedAudioRetentionPolicy.*;

/** Read-only original RESULT candidate policy. Uses the verified first complete result
 * anchor and the approved Beijing natural anniversary, never the audio's 180-day due
 * date. Shared submission, binding, processing, cost, dispute, hold and actual IO
 * protections remain required. The caller must assemble ORIGINAL evidence under the
 * existing locks; a returned snapshot is neither authority nor fencing to delete.
 * This class has no origin selection, database, storage operation or scheduler. */
public final class PreparedResultRetentionPolicy {
    private PreparedResultRetentionPolicy() {}

    public record Decision(State state,Instant resultDueAt,Set<Protection> protections) {
        public Decision {protections=Set.copyOf(protections);}
        /** Candidate only. Content exit, minimal markers and cleanup audit are separate. */
        public boolean resultDeleteCandidate(){return state==State.DUE&&resultDueAt!=null&&protections.isEmpty();}
    }

    public static Decision inspect(Evidence original,Instant trustedNow){
        if(original==null||trustedNow==null)throw new IllegalArgumentException("结果留存核验条件无效");
        // Reuse protection checks, not the audio date or its eligibility decision.
        var shared=PreparedAudioRetentionPolicy.inspect(original,trustedNow);
        var protections=EnumSet.noneOf(Protection.class);protections.addAll(shared.protections());
        var anniversary=PreparedYearRetentionPolicy.inspect(original.firstResultsCompletedAt(),trustedNow);
        if(anniversary.protection()!=null){
            protections.add(anniversary.protection()==PreparedYearRetentionPolicy.Protection.NO_ANCHOR
                ?Protection.NO_COMPLETION_ANCHOR:Protection.INVALID_TIMELINE);
        }
        Instant due=protections.contains(Protection.INVALID_TIMELINE)?null:anniversary.dueAt();
        if(!protections.isEmpty())return new Decision(State.PROTECTED,due,protections);
        return new Decision(trustedNow.isBefore(due)?State.WITHIN_RETENTION:State.DUE,due,protections);
    }
}
