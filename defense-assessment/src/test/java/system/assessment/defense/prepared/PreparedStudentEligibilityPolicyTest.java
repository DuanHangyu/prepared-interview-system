package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.prepared.attempt.PreparedStudentEligibility;
import system.assessment.defense.prepared.attempt.PreparedStudentEligibilityPolicy;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class PreparedStudentEligibilityPolicyTest {
    final Instant slot = Instant.parse("2026-10-04T10:00:00Z");
    PreparedStudentEligibility.Phase phase(boolean published, boolean history, boolean appointment,
                                           Integer state, Instant time, Instant now) {
        return PreparedStudentEligibilityPolicy.inspect(
                new PreparedStudentEligibilityPolicy.Conditions(published, history, appointment, state, time), now);
    }
    @Test void unpublishedPaperCannotBeEnteredRegardlessOfAppointment() {
        assertThat(phase(false,false,false,null,null,slot)).isEqualTo(PreparedStudentEligibility.Phase.PAPER_NOT_PUBLISHED);
        assertThat(phase(false,false,true,0,slot,slot)).isEqualTo(PreparedStudentEligibility.Phase.PAPER_NOT_PUBLISHED);
    }
    @Test void oldRecordIsNeverSilentlyTurnedIntoARepeatAttempt() {
        assertThat(phase(true,true,false,null,null,slot)).isEqualTo(PreparedStudentEligibility.Phase.HISTORY_REQUIRES_REVIEW);
        assertThat(phase(false,true,true,0,slot,slot)).isEqualTo(PreparedStudentEligibility.Phase.HISTORY_REQUIRES_REVIEW);
    }
    @Test void noAppointmentDoesNotInventAnOpeningWindow() {
        assertThat(phase(true,false,false,3,slot,slot.plusSeconds(3600)))
                .isEqualTo(PreparedStudentEligibility.Phase.READY_FOR_PREFLIGHT);
    }
    @Test void missingOrUnscheduledAppointmentIsNotReady() {
        assertThat(phase(true,false,true,null,null,slot)).isEqualTo(PreparedStudentEligibility.Phase.APPOINTMENT_REQUIRED);
        assertThat(phase(true,false,true,0,null,slot)).isEqualTo(PreparedStudentEligibility.Phase.APPOINTMENT_REQUIRED);
    }
    @Test void confirmedLegacyNullAndExplicitZeroStatesShareTheExistingTenMinuteWindow() {
        for (Integer state : new Integer[]{null,0}) {
            assertThat(phase(true,false,true,state,slot,slot.minusMillis(1)))
                    .isEqualTo(PreparedStudentEligibility.Phase.BEFORE_APPOINTMENT);
            assertThat(phase(true,false,true,state,slot,slot))
                    .isEqualTo(PreparedStudentEligibility.Phase.READY_FOR_PREFLIGHT);
            assertThat(phase(true,false,true,state,slot,slot.plusSeconds(600)))
                    .isEqualTo(PreparedStudentEligibility.Phase.READY_FOR_PREFLIGHT);
            assertThat(phase(true,false,true,state,slot,slot.plusSeconds(600).plusMillis(1)))
                    .isEqualTo(PreparedStudentEligibility.Phase.APPOINTMENT_WINDOW_EXPIRED);
        }
    }
    @ParameterizedTest @ValueSource(ints={-1,1,2,3,4,99})
    void inProgressCompletedMissedCancelledOrUnknownAppointmentCannotStartAnotherAttempt(int state) {
        assertThat(phase(true,false,true,state,slot,slot))
                .isEqualTo(PreparedStudentEligibility.Phase.APPOINTMENT_STATE_CONFLICT);
    }
    @Test void nullInputsDoNotDefaultToPermission() {
        assertThatThrownBy(()->PreparedStudentEligibilityPolicy.inspect(null,slot)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->phase(true,false,false,null,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void statusHasNoQuestionBodyAnswerKeyPrivatePathOrAttemptWriteReceipt() throws Exception {
        var status=new PreparedStudentEligibility.Status(12,"预检考核",3,true,false,
                PreparedStudentEligibility.Phase.READY_FOR_PREFLIGHT,"请先完成设备与本地保存预检",slot.toString(),null,null);
        var fields=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(status);
        assertThat(fields.size()).isEqualTo(10);
        assertThat(fields.toString()).doesNotContain("questionId","rubric","objectKey","paperVersionId","attemptId");
    }
}
