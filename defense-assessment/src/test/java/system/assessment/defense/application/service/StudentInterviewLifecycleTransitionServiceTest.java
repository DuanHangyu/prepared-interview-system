package system.assessment.defense.application.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentAppointmentPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentInterviewLifecycleTransitionServiceTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "lifecycle-transition-test"),
                StudentAssessmentAppointmentPO.class);
    }

    private final StudentAssessmentRecordService recordService =
            mock(StudentAssessmentRecordService.class);
    private final StudentAssessmentAppointmentService appointmentService =
            mock(StudentAssessmentAppointmentService.class);

    private StudentInterviewLifecycleTransitionService transitionService;

    @BeforeEach
    void setUp() {
        transitionService = new StudentInterviewLifecycleTransitionService(
                recordService, appointmentService);
    }

    @Test
    void appointmentFailureCannotLeaveARequiredDefenseStartPartiallyApplied() {
        LocalDateTime startedAt = LocalDateTime.now();
        when(recordService.startInterview(7, 11, startedAt)).thenReturn(true);
        when(appointmentService.update(any())).thenReturn(false);
        when(appointmentService.getOne(any())).thenReturn(appointment(0));

        assertThatThrownBy(() -> transitionService.startDefense(
                7, 11, startedAt, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("预约状态");
    }

    @Test
    void anExistingActiveDefenseRepairsItsAppointmentBeforeResuming() {
        LocalDateTime startedAt = LocalDateTime.now();
        when(recordService.startInterview(7, 11, startedAt)).thenReturn(false);
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .startDefenseTime(startedAt.minusSeconds(1))
                        .build()));
        when(appointmentService.update(any())).thenReturn(true);

        assertThat(transitionService.startDefense(7, 11, startedAt, true)).isFalse();
        verify(appointmentService).update(any());
    }

    @Test
    void appointmentFailureCannotLeaveAssessmentSubmissionPartiallyClaimed() {
        LocalDateTime claimedAt = LocalDateTime.now();
        when(recordService.claimInitialAnalysis(23, claimedAt)).thenReturn(true);
        when(appointmentService.update(any())).thenReturn(false);
        when(appointmentService.getOne(any())).thenReturn(appointment(1));

        assertThatThrownBy(() -> transitionService.claimInitialAnalysis(
                23, 7, 11, claimedAt, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("预约状态");
    }

    @Test
    void assessmentWithoutAppointmentsOnlyTransitionsTheAssessmentRecord() {
        LocalDateTime startedAt = LocalDateTime.now();
        when(recordService.startQuestionPhase(7, 11, startedAt, false)).thenReturn(true);

        assertThat(transitionService.startQuestionPhase(
                7, 11, startedAt, false, false)).isTrue();
        verify(appointmentService, never()).update(any());
        verify(appointmentService, never()).getOne(any());
    }

    private StudentAssessmentAppointmentPO appointment(int state) {
        StudentAssessmentAppointmentPO appointment = new StudentAssessmentAppointmentPO();
        appointment.setStudentId(7);
        appointment.setAssessmentId(11);
        appointment.setState(state);
        return appointment;
    }
}
