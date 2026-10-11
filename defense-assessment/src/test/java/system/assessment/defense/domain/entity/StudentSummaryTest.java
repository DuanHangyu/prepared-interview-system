package system.assessment.defense.domain.entity;

import org.junit.jupiter.api.Test;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentStudentRelationPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentAppointmentPO;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StudentSummaryTest {

    @Test
    void reappointableAppointmentDoesNotHideAssessmentFromAppointmentList() {
        StudentSummary summary = summaryWithAppointmentState(8, 4);

        List<Integer> toAppointIds = summary.toAppointAssessmentIds(
                ids -> Collections.emptyList(),
                ids -> Collections.emptyList()
        );
        List<Integer> doneIds = summary.doneAssessmentIds(ids -> Collections.emptyList());

        assertThat(toAppointIds).containsExactly(8);
        assertThat(doneIds).isEmpty();
    }

    @Test
    void activeAppointmentKeepsAssessmentOutOfAppointmentList() {
        StudentSummary summary = summaryWithAppointmentState(8, 0);

        List<Integer> toAppointIds = summary.toAppointAssessmentIds(
                ids -> Collections.emptyList(),
                ids -> Collections.emptyList()
        );

        assertThat(toAppointIds).isEmpty();
    }

    @Test
    void legacyNullAppointmentStateIsTreatedAsActive() {
        StudentSummary summary = summaryWithAppointmentState(8, null);

        List<Integer> todoIds = summary.todoAssessmentIds(ids -> Collections.emptyList());
        List<Integer> toAppointIds = summary.toAppointAssessmentIds(
                ids -> Collections.emptyList(),
                ids -> Collections.emptyList()
        );

        assertThat(todoIds).containsExactly(8);
        assertThat(toAppointIds).isEmpty();
    }

    private StudentSummary summaryWithAppointmentState(Integer assessmentId, Integer appointmentState) {
        StudentSummary summary = new StudentSummary();
        summary.setRelations(List.of(AssessmentStudentRelationPO.builder()
                .assessmentId(assessmentId)
                .studentId(18)
                .build()));

        StudentAssessmentAppointmentPO appointment = new StudentAssessmentAppointmentPO();
        appointment.setAssessmentId(assessmentId);
        appointment.setStudentId(18);
        appointment.setState(appointmentState);

        summary.setAppointments(List.of(appointment));
        summary.setRecords(Collections.emptyList());
        return summary;
    }
}
