package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.prepared.retention.PreparedAudioRetentionPolicy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.retention.PreparedAudioRetentionPolicy.*;

/** Pure candidate policy, not a deletion command or real storage/retention acceptance. */
class PreparedAudioRetentionPolicyTest {
    private final Instant first=Instant.parse("2026-10-04T03:00:00.123Z");
    private final Instant due=first.plus(180,ChronoUnit.DAYS);
    private Evidence evidence(String state,Instant started,Instant submitted,Instant completed,
            boolean reliable,boolean results,boolean bindings,boolean dispute,boolean hold,
            Set<String> jobs,Set<String> calls,Instant readExpires){
        return new Evidence(state,started,submitted,completed,reliable,results,bindings,dispute,hold,jobs,calls,readExpires);
    }
    private Evidence ready(){return evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,
        true,true,true,false,false,Set.of("SUCCEEDED"),Set.of("SETTLED","CANCELLED"),null);}
    private void protectedBy(Evidence value,Protection reason){
        var decision=inspect(value,due.plusSeconds(86400));
        assertThat(decision.state()).isEqualTo(State.PROTECTED);
        assertThat(decision.audioDeleteCandidate()).isFalse();
        assertThat(decision.protections()).contains(reason);
    }
    @Test void exact180DayBoundaryIsMeasuredFromFirstCompleteResultsNotStartOrUpload(){
        assertThat(inspect(ready(),due.minusNanos(1)).state()).isEqualTo(State.WITHIN_RETENTION);
        var at=inspect(ready(),due);assertThat(at.audioDeleteCandidate()).isTrue();
        assertThat(at.state()).isEqualTo(State.DUE);assertThat(at.audioDueAt()).isEqualTo(due);
        assertThat(inspect(ready(),due.plusNanos(1)).state()).isEqualTo(State.DUE);
        assertThat(inspect(ready(),first).audioDeleteCandidate()).isFalse();
    }
    @Test void aCandidateIsNotAuthorityToDeleteOrToExpireResultProofs(){
        var decision=inspect(ready(),due);
        assertThat(decision.protections()).isEmpty();
        assertThat(PreparedAudioRetentionPolicy.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
            .doesNotContain("delete","deleteResults","execute","schedule");
    }
    @Test void missingFirstCompletionIsProtectedRegardlessOfHowOldTheAttemptIs(){
        protectedBy(evidence("SUBMITTED",first.minus(500,ChronoUnit.DAYS),first.minus(499,ChronoUnit.DAYS),null,
            true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null),Protection.NO_COMPLETION_ANCHOR);
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"IN_PROGRESS","UPLOAD_PENDING","REQUIRES_REVIEW","CANCELLED","UNKNOWN"})
    void unsubmittedOrUnknownBusinessStateCannotBecomeEligible(String state){
        protectedBy(evidence(state,first.minusSeconds(100),first.minusSeconds(50),first,
            true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null),Protection.NOT_RELIABLY_SUBMITTED);
    }
    @ParameterizedTest @ValueSource(strings={"QUEUED","RUNNING","RETRY_WAIT","NEEDS_REVIEW"})
    void anyCurrentPendingOrReviewJobProtectsTheOriginalAudio(String job){
        protectedBy(evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,
            true,true,true,false,false,Set.of("SUCCEEDED",job),Set.of(),null),Protection.PROCESSING);
    }
    @Test void unknownAndMissingJobEvidenceFailClosed(){
        for(var jobs:Set.of(Set.of("SUCCEEDED","NEW_STATE"),Set.<String>of()))
            protectedBy(evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,
                true,true,true,false,false,jobs,Set.of(),null),Protection.UNVERIFIED_BINDINGS);
    }
    @ParameterizedTest @ValueSource(strings={"RESERVED","SENT","UNCERTAIN"})
    void unresolvedCostsAreNotCancelledOrReleasedByRetention(String call){
        protectedBy(evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,
            true,true,true,false,false,Set.of("SUCCEEDED"),Set.of("SETTLED",call),null),Protection.UNRESOLVED_COST);
    }
    @Test void unknownCostStateCannotBeTreatedAsSettled(){
        protectedBy(evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,
            true,true,true,false,false,Set.of("SUCCEEDED"),Set.of("NEW_STATE"),null),Protection.UNVERIFIED_BINDINGS);
    }
    @Test void trustworthyAbsenceOfPaidCallsDoesNotPreventNonPaidAudioRetirement(){
        var value=evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,
            true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null);
        assertThat(inspect(value,due).audioDeleteCandidate()).isTrue();
    }
    @Test void receiptResultsOrBindingsMustNotBeAssumedFromTheAnchorAlone(){
        protectedBy(evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,
            false,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null),Protection.NOT_RELIABLY_SUBMITTED);
        protectedBy(evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,
            true,false,true,false,false,Set.of("SUCCEEDED"),Set.of(),null),Protection.RESULTS_INCOMPLETE);
        protectedBy(evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,
            true,true,false,false,false,Set.of("SUCCEEDED"),Set.of(),null),Protection.UNVERIFIED_BINDINGS);
    }
    @Test void disputeAndExplicitHoldProtectEvenAfterOrdinaryExpiry(){
        protectedBy(evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,
            true,true,true,true,false,Set.of("SUCCEEDED"),Set.of(),null),Protection.DISPUTED);
        protectedBy(evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,
            true,true,true,false,true,Set.of("SUCCEEDED"),Set.of(),null),Protection.EXPLICIT_HOLD);
    }
    @Test void alreadyIssuedReadCapabilityProtectsUntilItsExactExpiry(){
        var value=evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,
            true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),due.plusSeconds(300));
        assertThat(inspect(value,due).protections()).contains(Protection.ACTIVE_READ_AUTHORIZATION);
        assertThat(inspect(value,due.plusSeconds(300).minusNanos(1)).audioDeleteCandidate()).isFalse();
        assertThat(inspect(value,due.plusSeconds(300)).audioDeleteCandidate()).isTrue();
    }
    @Test void invalidOrFutureTimelineNeverProducesTrustedExpiry(){
        for(var times:new Instant[][]{
            {null,first.minusSeconds(50),first},{first.minusSeconds(100),null,first},
            {first,first.minusSeconds(50),first},{first.minusSeconds(100),first.plusSeconds(1),first},
            {first.minusSeconds(100),first.minusSeconds(50),due.plusSeconds(86401)}}){
            var value=evidence("SUBMITTED",times[0],times[1],times[2],true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null);
            protectedBy(value,Protection.INVALID_TIMELINE);
            assertThat(inspect(value,due.plusSeconds(86400)).audioDueAt()).isNull();
        }
    }
    @Test void overflowingExpiryCannotCrashIntoPermission(){
        var value=evidence("SUBMITTED",Instant.MAX.minusSeconds(10),Instant.MAX.minusSeconds(5),Instant.MAX,
            true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null);
        var decision=inspect(value,Instant.MAX);
        assertThat(decision.audioDeleteCandidate()).isFalse();assertThat(decision.protections()).contains(Protection.INVALID_TIMELINE);
    }
    @Test void everyConcurrentProtectionIsReportedWithoutDroppingTheOthers(){
        var value=evidence("UPLOAD_PENDING",first.minusSeconds(100),first.minusSeconds(50),first,
            false,false,false,true,true,Set.of("RUNNING"),Set.of("UNCERTAIN"),due.plusSeconds(86500));
        assertThat(inspect(value,due.plusSeconds(86400)).protections()).contains(
            Protection.NOT_RELIABLY_SUBMITTED,Protection.RESULTS_INCOMPLETE,Protection.UNVERIFIED_BINDINGS,
            Protection.DISPUTED,Protection.EXPLICIT_HOLD,Protection.PROCESSING,Protection.UNRESOLVED_COST,Protection.ACTIVE_READ_AUTHORIZATION);
    }
    @Test void evidenceAndDecisionsAreImmutableAndNullStateCollectionsAreRejected(){
        var jobs=new HashSet<>(Set.of("SUCCEEDED"));var calls=new HashSet<>(Set.of("SETTLED"));
        var value=evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,true,true,true,false,false,jobs,calls,null);
        jobs.add("RUNNING");calls.add("UNCERTAIN");assertThat(inspect(value,due).audioDeleteCandidate()).isTrue();
        assertThatThrownBy(()->value.jobStates().add("RUNNING")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->inspect(value,due).protections().add(Protection.DISPUTED)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->evidence("SUBMITTED",first,first,first,true,true,true,false,false,null,Set.of(),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->evidence("SUBMITTED",first,first,first,true,true,true,false,false,Set.of(),null,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void nullInspectionInputsDoNotDefaultToCleanupPermission(){
        assertThatThrownBy(()->inspect(null,due)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->inspect(ready(),null)).isInstanceOf(IllegalArgumentException.class);
    }
}
