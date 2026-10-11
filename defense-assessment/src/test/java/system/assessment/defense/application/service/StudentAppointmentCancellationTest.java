package system.assessment.defense.application.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.infrastructure.concurrency.MySqlAdvisoryLockService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentAppointmentPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentAppointmentCancellationTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "appointment-cancel-test"),
                StudentAssessmentAppointmentPO.class);
    }

    @Test
    void missedAppointmentsCannotBeDeletedToBypassTheReschedulePenalty() {
        StudentAssessmentAppointmentService appointmentService = mock(StudentAssessmentAppointmentService.class);
        StudentAppointmentApplicationService service = service(appointmentService);
        when(appointmentService.getOne(any())).thenReturn(appointment(5, 3, LocalDateTime.now().plusHours(1)));

        assertThatThrownBy(() -> service.cancelAppointment(7, 11))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("取消");

        verify(appointmentService, never()).remove(any(Wrapper.class));
    }

    @Test
    void confirmedAppointmentsCannotBeCancelledAfterTheirStartTime() {
        StudentAssessmentAppointmentService appointmentService = mock(StudentAssessmentAppointmentService.class);
        StudentAppointmentApplicationService service = service(appointmentService);
        when(appointmentService.getOne(any())).thenReturn(appointment(5, 0, LocalDateTime.now().minusMinutes(1)));

        assertThatThrownBy(() -> service.cancelAppointment(7, 11))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("取消");

        verify(appointmentService, never()).remove(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void futureConfirmedAppointmentsAreDeletedWithAnAtomicStateAndTimeGuard() {
        StudentAssessmentAppointmentService appointmentService = mock(StudentAssessmentAppointmentService.class);
        StudentAppointmentApplicationService service = service(appointmentService);
        when(appointmentService.getOne(any())).thenReturn(appointment(5, 0, LocalDateTime.now().plusHours(1)));
        when(appointmentService.remove(any(Wrapper.class))).thenReturn(true);

        assertThat(service.cancelAppointment(7, 11)).isTrue();

        ArgumentCaptor<Wrapper<StudentAssessmentAppointmentPO>> deletion =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(appointmentService).remove(deletion.capture());
        assertThat(deletion.getValue().getCustomSqlSegment().toLowerCase())
                .contains("id", "student_id", "assessment_id", "state", "time_period");
    }

    @Test
    void schedulerSkipsCorruptAppointmentsAndContinuesMarkingExpiredOnes() {
        StudentAssessmentAppointmentService appointmentService = mock(StudentAssessmentAppointmentService.class);
        MySqlAdvisoryLockService advisoryLockService = mock(MySqlAdvisoryLockService.class);
        StudentAppointmentApplicationService service = new StudentAppointmentApplicationService(
                mock(AssessmentSettingService.class),
                appointmentService,
                mock(ResourceAccessControlService.class),
                advisoryLockService);
        ReflectionTestUtils.setField(service, "runScheduler", "start");
        StudentAssessmentAppointmentPO corrupt = appointment(5, 0, null);
        StudentAssessmentAppointmentPO expired = appointment(6, 0, LocalDateTime.now().minusMinutes(11));
        when(appointmentService.list(any(Wrapper.class))).thenReturn(List.of(corrupt, expired));
        when(appointmentService.update(any(Wrapper.class))).thenReturn(true);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        }).when(advisoryLockService).tryWithLock(anyString(), any(Runnable.class));

        service.schedulerCheckStudentAppointment();

        verify(appointmentService, never()).updateById(corrupt);
        verify(appointmentService, never()).updateById(expired);
        ArgumentCaptor<Wrapper<StudentAssessmentAppointmentPO>> update =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(appointmentService).update(update.capture());
        assertThat(update.getValue().getCustomSqlSegment().toLowerCase())
                .contains("id", "state", "time_period");
    }

    @Test
    void schedulerNeverMarksAnActiveAssessmentAsMissed() {
        StudentAssessmentAppointmentService appointmentService = mock(StudentAssessmentAppointmentService.class);
        MySqlAdvisoryLockService advisoryLockService = mock(MySqlAdvisoryLockService.class);
        StudentAppointmentApplicationService service = new StudentAppointmentApplicationService(
                mock(AssessmentSettingService.class),
                appointmentService,
                mock(ResourceAccessControlService.class),
                advisoryLockService);
        ReflectionTestUtils.setField(service, "runScheduler", "start");
        StudentAssessmentAppointmentPO active = appointment(
                6, 1, LocalDateTime.now().minusMinutes(11));
        when(appointmentService.list(any(Wrapper.class))).thenReturn(List.of(active));
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        }).when(advisoryLockService).tryWithLock(anyString(), any(Runnable.class));

        service.schedulerCheckStudentAppointment();

        verify(appointmentService, never()).updateById(active);
        verify(appointmentService, never()).update(any(Wrapper.class));
        assertThat(active.getState()).isEqualTo(1);
    }

    private StudentAppointmentApplicationService service(
            StudentAssessmentAppointmentService appointmentService) {
        return new StudentAppointmentApplicationService(
                mock(AssessmentSettingService.class),
                appointmentService,
                mock(ResourceAccessControlService.class),
                mock(MySqlAdvisoryLockService.class));
    }

    private StudentAssessmentAppointmentPO appointment(Integer id,
                                                        Integer state,
                                                        LocalDateTime timePeriod) {
        StudentAssessmentAppointmentPO appointment = new StudentAssessmentAppointmentPO();
        appointment.setId(id);
        appointment.setStudentId(7);
        appointment.setAssessmentId(11);
        appointment.setState(state);
        appointment.setTimePeriod(timePeriod);
        return appointment;
    }
}
