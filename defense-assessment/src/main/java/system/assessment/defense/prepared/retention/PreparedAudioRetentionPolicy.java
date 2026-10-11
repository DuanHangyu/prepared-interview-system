package system.assessment.defense.prepared.retention;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;

/** Read-only 180-day AUDIO candidate policy. This is neither deletion authority nor a
 * result-retention policy. An eventual executor must assemble verified original-scope
 * evidence under the existing transaction locks, recheck protections and fence all IO.
 * Never infer these booleans from a client, legacy state or completion timestamp alone.
 * No scheduler, database mutation, storage operation or result-proof removal is exposed. */
public final class PreparedAudioRetentionPolicy {
    public static final int AUDIO_RETENTION_DAYS=180;
    private PreparedAudioRetentionPolicy() {}

    public enum State { PROTECTED, WITHIN_RETENTION, DUE }
    public enum Protection {
        NO_COMPLETION_ANCHOR, INVALID_TIMELINE, NOT_RELIABLY_SUBMITTED,
        RESULTS_INCOMPLETE, UNVERIFIED_BINDINGS, PROCESSING, UNRESOLVED_COST,
        DISPUTED, EXPLICIT_HOLD, ACTIVE_READ_AUTHORIZATION, ACTIVE_AUDIO_IO
    }
    /** All job/call states refer ONLY to this verified original attempt, not shared TTS.
     * Historical failed work and unresolved costs need disposition, not automatic clearing. */
    public record Evidence(String attemptState,Instant startedAt,Instant submittedAt,Instant firstResultsCompletedAt,
            boolean reliableSubmission,boolean completeResults,boolean verifiedBindings,boolean disputed,
            boolean explicitHold,Set<String> jobStates,Set<String> modelCallStates,Instant readAuthorizationExpiresAt,boolean originalIoInFlight) {
        public Evidence(String attemptState,Instant startedAt,Instant submittedAt,Instant firstResultsCompletedAt,
                boolean reliableSubmission,boolean completeResults,boolean verifiedBindings,boolean disputed,
                boolean explicitHold,Set<String> jobStates,Set<String> modelCallStates,Instant readAuthorizationExpiresAt){
            this(attemptState,startedAt,submittedAt,firstResultsCompletedAt,reliableSubmission,completeResults,verifiedBindings,disputed,explicitHold,jobStates,modelCallStates,readAuthorizationExpiresAt,false);
        }
        public Evidence {
            try {jobStates=Set.copyOf(jobStates);modelCallStates=Set.copyOf(modelCallStates);}
            catch(NullPointerException failure){throw new IllegalArgumentException("录音留存保护依据不完整");}
        }
    }
    public record Decision(State state,Instant audioDueAt,Set<Protection> protections) {
        public Decision {protections=Set.copyOf(protections);}
        /** Candidate ONLY; the full retirement transaction/lifecycle is not implemented here. */
        public boolean audioDeleteCandidate(){return state==State.DUE&&protections.isEmpty()&&audioDueAt!=null;}
    }

    public static Decision inspect(Evidence value,Instant now){
        if(value==null||now==null)throw new IllegalArgumentException("录音留存核验条件无效");
        var protections=EnumSet.noneOf(Protection.class);
        if(!"SUBMITTED".equals(value.attemptState())||!value.reliableSubmission())protections.add(Protection.NOT_RELIABLY_SUBMITTED);
        if(!value.completeResults())protections.add(Protection.RESULTS_INCOMPLETE);
        if(!value.verifiedBindings()||value.jobStates().isEmpty())protections.add(Protection.UNVERIFIED_BINDINGS);
        if(value.disputed())protections.add(Protection.DISPUTED);
        if(value.explicitHold())protections.add(Protection.EXPLICIT_HOLD);
        if(value.originalIoInFlight())protections.add(Protection.ACTIVE_AUDIO_IO);
        for(String state:value.jobStates())switch(state){
            case "SUCCEEDED" -> { }
            case "QUEUED","RUNNING","RETRY_WAIT","NEEDS_REVIEW" -> protections.add(Protection.PROCESSING);
            default -> protections.add(Protection.UNVERIFIED_BINDINGS);
        }
        for(String state:value.modelCallStates())switch(state){
            case "SETTLED","CANCELLED" -> { }
            case "RESERVED","SENT","UNCERTAIN" -> protections.add(Protection.UNRESOLVED_COST);
            default -> protections.add(Protection.UNVERIFIED_BINDINGS);
        }
        if(value.readAuthorizationExpiresAt()!=null&&value.readAuthorizationExpiresAt().isAfter(now))
            protections.add(Protection.ACTIVE_READ_AUTHORIZATION);

        Instant first=value.firstResultsCompletedAt(),due=null;
        if(first==null)protections.add(Protection.NO_COMPLETION_ANCHOR);
        if(value.startedAt()==null||value.submittedAt()==null||value.submittedAt().isBefore(value.startedAt())
                ||value.submittedAt().isAfter(now)||(first!=null&&(first.isBefore(value.submittedAt())||first.isAfter(now))))
            protections.add(Protection.INVALID_TIMELINE);
        if(first!=null&&!protections.contains(Protection.INVALID_TIMELINE)){
            try {due=first.plus(AUDIO_RETENTION_DAYS,ChronoUnit.DAYS);}
            catch(DateTimeException|ArithmeticException failure){protections.add(Protection.INVALID_TIMELINE);}
        }
        if(!protections.isEmpty())return new Decision(State.PROTECTED,due,protections);
        return new Decision(now.isBefore(due)?State.WITHIN_RETENTION:State.DUE,due,protections);
    }
}
