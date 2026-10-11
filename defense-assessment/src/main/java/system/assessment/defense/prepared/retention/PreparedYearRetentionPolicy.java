package system.assessment.defense.prepared.retention;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;

/** Read-only natural-anniversary calculation, NOT deletion authority. The caller must
 * separately verify the original result/event/cleanup-operation anchor, ownership,
 * protections and retirement fencing. This class selects no origin, accepts no client
 * permission and exposes no database, storage operation or scheduler. */
public final class PreparedYearRetentionPolicy {
    public static final ZoneId RETENTION_ZONE=ZoneId.of("Asia/Shanghai");
    public static final Period RETENTION_PERIOD=Period.ofYears(1);
    private PreparedYearRetentionPolicy() {}

    public enum State { PROTECTED, WITHIN_RETENTION, DUE }
    public enum Protection { NO_ANCHOR, INVALID_TIMELINE }
    /** DUE means only the date boundary is reached; never permission to delete. */
    public record Decision(State state,Instant dueAt,Protection protection) {}

    public static Decision inspect(Instant originalAnchor,Instant trustedNow){
        if(trustedNow==null)throw new IllegalArgumentException("年度留存核验时间无效");
        if(originalAnchor==null)return new Decision(State.PROTECTED,null,Protection.NO_ANCHOR);
        if(originalAnchor.isAfter(trustedNow))return new Decision(State.PROTECTED,null,Protection.INVALID_TIMELINE);
        try{
            // Calendar arithmetic preserves local wall time and input nanosecond
            // precision; Feb29 maps to Feb28. Never replace this with 365 days,
            // the JVM default timezone or rounding to midnight.
            var anniversary=LocalDateTime.ofInstant(originalAnchor,RETENTION_ZONE).plus(RETENTION_PERIOD);
            var offsets=RETENTION_ZONE.getRules().getValidOffsets(anniversary);
            // Historical gaps/overlaps (or changed zone rules) must not silently
            // shift the anniversary or select a guessed earlier deletion boundary.
            if(offsets.size()!=1)return new Decision(State.PROTECTED,null,Protection.INVALID_TIMELINE);
            var due=anniversary.toInstant(offsets.getFirst());
            return new Decision(trustedNow.isBefore(due)?State.WITHIN_RETENTION:State.DUE,due,null);
        }catch(DateTimeException|ArithmeticException failure){
            return new Decision(State.PROTECTED,null,Protection.INVALID_TIMELINE);
        }
    }
}
