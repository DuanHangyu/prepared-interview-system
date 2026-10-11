package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.prepared.retention.PreparedResultRetentionPolicy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.retention.PreparedAudioRetentionPolicy.*;

/** Read-only result candidate policy. No database origin, cloud or deletion acceptance. */
class PreparedResultRetentionPolicyTest {
    private final ZoneId beijing=ZoneId.of("Asia/Shanghai");
    private final Instant first=local("2023-03-01T00:30:12.123456789");
    private final Instant due=local("2024-03-01T00:30:12.123456789");
    private Instant local(String value){return LocalDateTime.parse(value).atZone(beijing).toInstant();}
    private Evidence ready(Instant anchor){
        return new Evidence("SUBMITTED",anchor.minusSeconds(100),anchor.minusSeconds(50),anchor,
            true,true,true,false,false,Set.of("SUCCEEDED"),Set.of("SETTLED","CANCELLED"),null,false);
    }
    private Evidence evidence(String state,Instant start,Instant submission,Instant anchor,
            boolean reliable,boolean complete,boolean bound,boolean dispute,boolean hold,
            Set<String> jobs,Set<String> calls,Instant read,boolean io){
        return new Evidence(state,start,submission,anchor,reliable,complete,bound,dispute,hold,jobs,calls,read,io);
    }
    private Evidence flags(boolean reliable,boolean complete,boolean bound,boolean dispute,boolean hold,
            Set<String> jobs,Set<String> calls,Instant read,boolean io){
        return evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),first,
            reliable,complete,bound,dispute,hold,jobs,calls,read,io);
    }
    private void protectedBy(Evidence input,Protection protection){
        var result=PreparedResultRetentionPolicy.inspect(input,due.plusSeconds(86400));
        assertThat(result.state()).isEqualTo(State.PROTECTED);
        assertThat(result.resultDeleteCandidate()).isFalse();
        assertThat(result.protections()).contains(protection);
    }
    @ParameterizedTest @CsvSource({
        "2026-10-06T17:12:34.123456789,2027-10-06T17:12:34.123456789",
        "2023-03-01T00:30:12.123456789,2024-03-01T00:30:12.123456789",
        "2024-02-29T00:30:00.000000001,2025-02-28T00:30:00.000000001",
        "2024-02-29T23:59:59.999999999,2025-02-28T23:59:59.999999999",
        "2026-12-31T23:59:59.999999999,2027-12-31T23:59:59.999999999"
    })
    void firstCompleteResultsSelectsBeijingNaturalAnniversaryAndExactBoundary(String anchor,String expected){
        Instant origin=local(anchor),expiry=local(expected);var original=ready(origin);
        var before=PreparedResultRetentionPolicy.inspect(original,expiry.minusNanos(1));
        assertThat(before.state()).isEqualTo(State.WITHIN_RETENTION);
        assertThat(before.resultDueAt()).isEqualTo(expiry);
        assertThat(before.resultDeleteCandidate()).isFalse();
        var at=PreparedResultRetentionPolicy.inspect(original,expiry);
        assertThat(at.state()).isEqualTo(State.DUE);assertThat(at.resultDeleteCandidate()).isTrue();
        assertThat(at.protections()).isEmpty();assertThat(at.resultDueAt()).isEqualTo(expiry);
        assertThat(PreparedResultRetentionPolicy.inspect(original,expiry.plusNanos(1)).resultDeleteCandidate()).isTrue();
    }
    @Test void dueAudioAnd365DaysCannotMakeResultsExpireBeforeTheNaturalAnniversary(){
        for(long days:new long[]{180,181,365}){
            var result=PreparedResultRetentionPolicy.inspect(ready(first),first.plusSeconds(days*86400));
            assertThat(result.state()).isEqualTo(State.WITHIN_RETENTION);
            assertThat(result.resultDueAt()).isEqualTo(due);assertThat(result.resultDeleteCandidate()).isFalse();
        }
    }
    @Test void noResultAnchorDoesNotFallBackToStartSubmissionOrNow(){
        protectedBy(evidence("SUBMITTED",first.minusSeconds(100),first.minusSeconds(50),null,
            true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null,false),Protection.NO_COMPLETION_ANCHOR);
        assertThat(PreparedResultRetentionPolicy.inspect(evidence("SUBMITTED",first,first,null,
            true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null,false),due).resultDueAt()).isNull();
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"IN_PROGRESS","UPLOAD_PENDING","REQUIRES_REVIEW","CANCELLED","UNKNOWN"})
    void pendingOrUnknownAttemptCannotExpireResults(String state){
        protectedBy(evidence(state,first.minusSeconds(100),first.minusSeconds(50),first,
            true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null,false),Protection.NOT_RELIABLY_SUBMITTED);
    }
    @Test void anchorAloneDoesNotProveReliableReceiptCompleteResultsOrScope(){
        protectedBy(flags(false,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null,false),Protection.NOT_RELIABLY_SUBMITTED);
        protectedBy(flags(true,false,true,false,false,Set.of("SUCCEEDED"),Set.of(),null,false),Protection.RESULTS_INCOMPLETE);
        protectedBy(flags(true,true,false,false,false,Set.of("SUCCEEDED"),Set.of(),null,false),Protection.UNVERIFIED_BINDINGS);
    }
    @ParameterizedTest @ValueSource(strings={"QUEUED","RUNNING","RETRY_WAIT","NEEDS_REVIEW"})
    void currentOrHistoricalUnfinishedProcessingProtectsResults(String job){
        protectedBy(flags(true,true,true,false,false,Set.of("SUCCEEDED",job),Set.of(),null,false),Protection.PROCESSING);
    }
    @ParameterizedTest @ValueSource(strings={"FAILED","CANCELLED","UNKNOWN","succeeded",""})
    void unprovenJobDispositionRemainsProtected(String job){
        protectedBy(flags(true,true,true,false,false,Set.of("SUCCEEDED",job),Set.of(),null,false),Protection.UNVERIFIED_BINDINGS);
    }
    @Test void missingJobsCannotBeSilentlyTreatedAsSuccessful(){
        protectedBy(flags(true,true,true,false,false,Set.of(),Set.of(),null,false),Protection.UNVERIFIED_BINDINGS);
    }
    @ParameterizedTest @ValueSource(strings={"RESERVED","SENT","UNCERTAIN"})
    void unresolvedBillableCallsNeverBecomeRetentionCancellation(String call){
        protectedBy(flags(true,true,true,false,false,Set.of("SUCCEEDED"),Set.of("SETTLED",call),null,false),Protection.UNRESOLVED_COST);
    }
    @ParameterizedTest @ValueSource(strings={"UNKNOWN","FAILED","settled",""})
    void unverifiedCallDispositionProtectsInsteadOfReleasingCost(String call){
        protectedBy(flags(true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(call),null,false),Protection.UNVERIFIED_BINDINGS);
    }
    @Test void verifiedAbsenceOfPaidCallsIsNotAnArtificialPermanentHold(){
        var input=flags(true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null,false);
        assertThat(PreparedResultRetentionPolicy.inspect(input,due).resultDeleteCandidate()).isTrue();
    }
    @Test void disputeManualHoldAndActualInFlightIoEachProtectBeyondTheYear(){
        protectedBy(flags(true,true,true,true,false,Set.of("SUCCEEDED"),Set.of(),null,false),Protection.DISPUTED);
        protectedBy(flags(true,true,true,false,true,Set.of("SUCCEEDED"),Set.of(),null,false),Protection.EXPLICIT_HOLD);
        protectedBy(flags(true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null,true),Protection.ACTIVE_AUDIO_IO);
    }
    @Test void anIssuedOriginalReadCapabilityProtectsUntilItsExactExpiry(){
        var readExpiry=due.plusSeconds(300);
        var input=flags(true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),readExpiry,false);
        assertThat(PreparedResultRetentionPolicy.inspect(input,due).protections()).contains(Protection.ACTIVE_READ_AUTHORIZATION);
        assertThat(PreparedResultRetentionPolicy.inspect(input,readExpiry.minusNanos(1)).resultDeleteCandidate()).isFalse();
        assertThat(PreparedResultRetentionPolicy.inspect(input,readExpiry).resultDeleteCandidate()).isTrue();
    }
    @Test void releasingOneProtectionDoesNotDropOtherConcurrentProtections(){
        var input=flags(true,true,true,true,true,Set.of("SUCCEEDED","RUNNING"),Set.of("UNCERTAIN"),due.plusSeconds(90000),true);
        var result=PreparedResultRetentionPolicy.inspect(input,due.plusSeconds(86400));
        assertThat(result.protections()).containsExactlyInAnyOrder(Protection.DISPUTED,Protection.EXPLICIT_HOLD,
            Protection.PROCESSING,Protection.UNRESOLVED_COST,Protection.ACTIVE_READ_AUTHORIZATION,Protection.ACTIVE_AUDIO_IO);
        assertThat(result.resultDueAt()).isEqualTo(due);assertThat(result.resultDeleteCandidate()).isFalse();
        var withoutHold=flags(true,true,true,true,false,Set.of("SUCCEEDED","RUNNING"),Set.of("UNCERTAIN"),null,true);
        assertThat(PreparedResultRetentionPolicy.inspect(withoutHold,due).protections()).contains(
            Protection.DISPUTED,Protection.PROCESSING,Protection.UNRESOLVED_COST,Protection.ACTIVE_AUDIO_IO).doesNotContain(Protection.EXPLICIT_HOLD);
    }
    @Test void invalidOriginalTimelineHasNoTrustedResultExpiry(){
        for(var times:new Instant[][]{{null,first.minusSeconds(50),first},{first.minusSeconds(100),null,first},
            {first,first.minusSeconds(50),first},{first.minusSeconds(100),first.plusSeconds(1),first},
            {first.minusSeconds(100),first.minusSeconds(50),due.plusSeconds(86401)}}){
            var input=evidence("SUBMITTED",times[0],times[1],times[2],true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null,false);
            protectedBy(input,Protection.INVALID_TIMELINE);
            assertThat(PreparedResultRetentionPolicy.inspect(input,due.plusSeconds(86400)).resultDueAt()).isNull();
        }
    }
    @ParameterizedTest @ValueSource(strings={"-1000000000-01-01T00:00:00Z","+1000000000-12-31T23:59:59.999999999Z","+999999999-12-30T00:00:00Z"})
    void unrepresentableCalendarYearRetainsResultsWithoutCrashing(String timestamp){
        var anchor=Instant.parse(timestamp);
        var input=evidence("SUBMITTED",anchor,anchor,anchor,true,true,true,false,false,Set.of("SUCCEEDED"),Set.of(),null,false);
        var result=PreparedResultRetentionPolicy.inspect(input,Instant.MAX);
        assertThat(result.state()).isEqualTo(State.PROTECTED);assertThat(result.resultDueAt()).isNull();
        assertThat(result.protections()).contains(Protection.INVALID_TIMELINE);assertThat(result.resultDeleteCandidate()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"1990-04-14T02:30:00","1990-09-15T01:30:00"})
    void historicalAmbiguousAnniversaryIsProtectedRatherThanShifted(String timestamp){
        var result=PreparedResultRetentionPolicy.inspect(ready(local(timestamp)),due);
        assertThat(result.state()).isEqualTo(State.PROTECTED);assertThat(result.resultDueAt()).isNull();
        assertThat(result.protections()).contains(Protection.INVALID_TIMELINE);
    }
    @Test void originalEvidenceAndDecisionRemainImmutable(){
        var jobs=new HashSet<>(Set.of("SUCCEEDED"));var calls=new HashSet<>(Set.of("SETTLED"));
        var input=flags(true,true,true,false,false,jobs,calls,null,false);
        jobs.add("RUNNING");calls.add("UNCERTAIN");
        var result=PreparedResultRetentionPolicy.inspect(input,due);
        assertThat(result.resultDeleteCandidate()).isTrue();
        assertThatThrownBy(()->result.protections().add(Protection.DISPUTED)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->input.jobStates().add("RUNNING")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void candidateFlagRequiresKnownExpiryAndNoProtectionEvenForAManufacturedDueState(){
        var noExpiry=new PreparedResultRetentionPolicy.Decision(State.DUE,null,Set.of());
        var stillProtected=new PreparedResultRetentionPolicy.Decision(State.DUE,due,Set.of(Protection.DISPUTED));
        assertThat(noExpiry.resultDeleteCandidate()).isFalse();assertThat(stillProtected.resultDeleteCandidate()).isFalse();
    }
    @Test void inspectionDoesNotMutateTheOriginalAnchorOrResetItToTheCurrentTime(){
        var input=ready(first);var firstDecision=PreparedResultRetentionPolicy.inspect(input,first);
        assertThat(firstDecision.resultDueAt()).isEqualTo(due);
        assertThat(PreparedResultRetentionPolicy.inspect(input,due.plusSeconds(86400)).resultDueAt()).isEqualTo(due);
        assertThat(input.firstResultsCompletedAt()).isEqualTo(first);
    }
    @Test void missingInputsAreRejectedAndCandidateIsNotAnExecutionCapability(){
        assertThatThrownBy(()->PreparedResultRetentionPolicy.inspect(null,due)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedResultRetentionPolicy.inspect(ready(first),null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PreparedResultRetentionPolicy.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
            .doesNotContain("delete","deleteAudio","execute","schedule","authorize","selectAnchor");
    }
}
