package system.assessment.defense.application.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentAppointmentPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Keeps the durable interview record and its optional appointment in the same
 * short database transaction. Network, audio, cache and AI work must remain
 * outside this service so those side effects never hold a database transaction.
 */
@Service
@RequiredArgsConstructor
public class StudentInterviewLifecycleTransitionService {

    private static final int APPOINTMENT_CONFIRMED = 0;
    private static final int APPOINTMENT_IN_ASSESSMENT = 1;
    private static final int APPOINTMENT_COMPLETED = 2;

    private final StudentAssessmentRecordService recordService;
    private final StudentAssessmentAppointmentService appointmentService;

    @Transactional(rollbackFor = Exception.class)
    public boolean startDefense(Integer studentId,
                                Integer assessmentId,
                                LocalDateTime startedAt,
                                boolean requiresAppointment) {
        boolean started = recordService.startInterview(studentId, assessmentId, startedAt);
        boolean active = started || recordService.findByStudentIdAndAssessmentId(studentId, assessmentId)
                .filter(this::isDefenseActive)
                .isPresent();
        if (active && requiresAppointment) {
            requireAppointmentInProgress(studentId, assessmentId);
        }
        return started;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean startQuestionPhase(Integer studentId,
                                      Integer assessmentId,
                                      LocalDateTime startedAt,
                                      boolean requireCompletedDefense,
                                      boolean requiresAppointment) {
        boolean started = recordService.startQuestionPhase(
                studentId, assessmentId, startedAt, requireCompletedDefense);
        boolean active = started || recordService.findByStudentIdAndAssessmentId(studentId, assessmentId)
                .filter(this::isQuestionPhaseActive)
                .isPresent();
        if (active && requiresAppointment) {
            requireAppointmentInProgress(studentId, assessmentId);
        }
        return started;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean claimInitialAnalysis(Integer recordId,
                                        Integer studentId,
                                        Integer assessmentId,
                                        LocalDateTime claimedAt,
                                        boolean requiresAppointment) {
        boolean claimed = recordService.claimInitialAnalysis(recordId, claimedAt);
        boolean submitted = claimed || recordService.findByStudentIdAndAssessmentId(studentId, assessmentId)
                .filter(record -> record.getState() != null && !Objects.equals(record.getState(), 0))
                .isPresent();
        if (submitted && requiresAppointment) {
            requireAppointmentCompleted(studentId, assessmentId);
        }
        return claimed;
    }

    private boolean isDefenseActive(StudentAssessmentRecordPO record) {
        return Objects.equals(record.getState(), 0)
                && record.getStartDefenseTime() != null
                && record.getEndDefenseTime() == null;
    }

    private boolean isQuestionPhaseActive(StudentAssessmentRecordPO record) {
        return Objects.equals(record.getState(), 0)
                && record.getQuestionStartedAt() != null;
    }

    private void requireAppointmentInProgress(Integer studentId, Integer assessmentId) {
        boolean updated = appointmentService.update(Wrappers.lambdaUpdate(StudentAssessmentAppointmentPO.class)
                .set(StudentAssessmentAppointmentPO::getState, APPOINTMENT_IN_ASSESSMENT)
                .eq(StudentAssessmentAppointmentPO::getStudentId, studentId)
                .eq(StudentAssessmentAppointmentPO::getAssessmentId, assessmentId)
                .and(state -> state
                        .eq(StudentAssessmentAppointmentPO::getState, APPOINTMENT_CONFIRMED)
                        .or()
                        .isNull(StudentAssessmentAppointmentPO::getState)));
        requireAppointmentState(studentId, assessmentId, APPOINTMENT_IN_ASSESSMENT, updated);
    }

    private void requireAppointmentCompleted(Integer studentId, Integer assessmentId) {
        boolean updated = appointmentService.update(Wrappers.lambdaUpdate(StudentAssessmentAppointmentPO.class)
                .set(StudentAssessmentAppointmentPO::getState, APPOINTMENT_COMPLETED)
                .eq(StudentAssessmentAppointmentPO::getStudentId, studentId)
                .eq(StudentAssessmentAppointmentPO::getAssessmentId, assessmentId)
                .and(state -> state
                        .in(StudentAssessmentAppointmentPO::getState,
                                APPOINTMENT_CONFIRMED, APPOINTMENT_IN_ASSESSMENT)
                        .or()
                        .isNull(StudentAssessmentAppointmentPO::getState)));
        requireAppointmentState(studentId, assessmentId, APPOINTMENT_COMPLETED, updated);
    }

    private void requireAppointmentState(Integer studentId,
                                         Integer assessmentId,
                                         int expectedState,
                                         boolean updated) {
        if (updated) {
            return;
        }
        StudentAssessmentAppointmentPO appointment = appointmentService.getOne(
                Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                        .eq(StudentAssessmentAppointmentPO::getStudentId, studentId)
                        .eq(StudentAssessmentAppointmentPO::getAssessmentId, assessmentId));
        if (appointment == null || !Objects.equals(appointment.getState(), expectedState)) {
            throw new IllegalStateException("预约状态与考核生命周期更新失败");
        }
    }
}
