package system.assessment.defense.prepared.attempt;

import java.time.Instant;
import static system.assessment.defense.prepared.attempt.PreparedStudentEligibility.Phase.*;

/** First-entry checks only. Resuming a durable attempt must not reuse or reset this appointment window. */
public final class PreparedStudentEligibilityPolicy {
    public static final int FIRST_START_WINDOW_SECONDS=600;
    private PreparedStudentEligibilityPolicy() {}
    public record Conditions(boolean published, boolean hasLegacyRecord, boolean appointmentRequired,
                             Integer appointmentState, Instant appointmentAt) {}
    public static PreparedStudentEligibility.Phase inspect(Conditions conditions, Instant now) {
        if (conditions==null || now==null) throw new IllegalArgumentException("开考条件无效");
        if (conditions.hasLegacyRecord()) return HISTORY_REQUIRES_REVIEW;
        if (!conditions.published()) return PAPER_NOT_PUBLISHED;
        if (!conditions.appointmentRequired()) return READY_FOR_PREFLIGHT;
        if (conditions.appointmentAt()==null) return APPOINTMENT_REQUIRED;
        if (conditions.appointmentState()!=null && conditions.appointmentState()!=0) return APPOINTMENT_STATE_CONFLICT;
        if (now.isBefore(conditions.appointmentAt())) return BEFORE_APPOINTMENT;
        if (now.isAfter(conditions.appointmentAt().plusSeconds(FIRST_START_WINDOW_SECONDS))) return APPOINTMENT_WINDOW_EXPIRED;
        return READY_FOR_PREFLIGHT;
    }
}
