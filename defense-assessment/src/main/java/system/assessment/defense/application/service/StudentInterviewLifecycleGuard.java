package system.assessment.defense.application.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import system.assessment.defense.application.dto.WebsocketMessageDTO;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentAppointmentPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class StudentInterviewLifecycleGuard {

    private final AssessmentSettingService settingService;
    private final StudentAssessmentAppointmentService appointmentService;
    private final StudentAssessmentRecordService recordService;
    private final StudentAssessmentQuestionAnswerService questionAnswerService;

    public void validate(Integer studentId, Integer assessmentId, WebsocketMessageDTO message) {
        AssessmentSettingPO setting = requireAppointmentLifecycle(studentId, assessmentId, message);
        requireInterviewLifecycle(studentId, assessmentId, message, setting);
    }

    private AssessmentSettingPO requireAppointmentLifecycle(Integer studentId,
                                                            Integer assessmentId,
                                                            WebsocketMessageDTO message) {
        AssessmentSettingPO setting = settingService.getById(assessmentId);
        if (setting == null) {
            throw new BusinessException(ErrorCodeEnums.ASSESSMENT_SETTING_NOT_FOUND);
        }
        if (!Boolean.TRUE.equals(setting.getNeedAppoint())) {
            return setting;
        }
        StudentAssessmentAppointmentPO appointment = appointmentService.getOne(
                Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                        .eq(StudentAssessmentAppointmentPO::getStudentId, studentId)
                        .eq(StudentAssessmentAppointmentPO::getAssessmentId, assessmentId));
        if (appointment == null) {
            throw new BusinessException(ErrorCodeEnums.APPOINTMENT_NOT_READY);
        }
        if (Objects.equals(appointment.getState(), 1)
                || (message.endAssessment() && Objects.equals(appointment.getState(), 2))) {
            return setting;
        }
        if (!message.startDefense() && !message.startAssessment()) {
            throw new BusinessException(ErrorCodeEnums.APPOINTMENT_NOT_READY);
        }
        if (appointment.getState() != null && !Objects.equals(appointment.getState(), 0)) {
            throw new BusinessException(ErrorCodeEnums.APPOINTMENT_NOT_READY);
        }
        LocalDateTime timePeriod = appointment.getTimePeriod();
        LocalDateTime now = LocalDateTime.now();
        if (timePeriod == null || now.isBefore(timePeriod) || now.isAfter(timePeriod.plusMinutes(10))) {
            throw new BusinessException(ErrorCodeEnums.APPOINTMENT_NOT_READY);
        }
        return setting;
    }

    private void requireInterviewLifecycle(Integer studentId,
                                           Integer assessmentId,
                                           WebsocketMessageDTO message,
                                           AssessmentSettingPO setting) {
        if ((message.startDefense() || message.endDefense())
                && !Boolean.TRUE.equals(setting.getDefense())) {
            throw notReady();
        }
        if ((message.startAssessment() || message.startAnswer()
                || message.endAnswer() || message.generateQuestion())
                && !Boolean.TRUE.equals(setting.getQuestion())) {
            throw notReady();
        }
        if (message.startAssessment() && Boolean.TRUE.equals(setting.getDefense())) {
            StudentAssessmentRecordPO record = recordService
                    .findByStudentIdAndAssessmentId(studentId, assessmentId)
                    .orElseThrow(StudentInterviewLifecycleGuard::notReady);
            if (record.getEndDefenseTime() == null) {
                throw notReady();
            }
        }
        if (message.startDefense() && Boolean.TRUE.equals(setting.getQuestion())) {
            recordService.findByStudentIdAndAssessmentId(studentId, assessmentId)
                    .filter(record -> record.getQuestionStartedAt() != null)
                    .ifPresent(record -> {
                        throw notReady();
                    });
        }
        if (message.startAnswer() || message.endAnswer() || message.generateQuestion()) {
            StudentAssessmentRecordPO record = requireActiveRecord(studentId, assessmentId);
            if (record.getQuestionStartedAt() == null) {
                throw notReady();
            }
        }

        if (message.endDefense()) {
            StudentAssessmentRecordPO record = requireActiveRecord(studentId, assessmentId);
            if (record.getStartDefenseTime() == null
                    || record.getEndDefenseTime() != null
                    || StringUtils.isNotBlank(record.getDefenseVoice())) {
                throw notReady();
            }
        }

        if (!message.endAssessment()) {
            return;
        }
        StudentAssessmentRecordPO record = recordService
                .findByStudentIdAndAssessmentId(studentId, assessmentId)
                .orElseThrow(StudentInterviewLifecycleGuard::notReady);
        if (!Objects.equals(record.getState(), 0)) {
            return;
        }
        if (Boolean.TRUE.equals(setting.getDefense()) && record.getEndDefenseTime() == null) {
            throw notReady();
        }
        if (Boolean.TRUE.equals(setting.getQuestion())) {
            requireCompletedQuestionPhase(studentId, assessmentId, setting, record);
        }
    }

    private void requireCompletedQuestionPhase(Integer studentId,
                                               Integer assessmentId,
                                               AssessmentSettingPO setting,
                                               StudentAssessmentRecordPO record) {
        if (record.getQuestionStartedAt() == null) {
            throw notReady();
        }
        int expected = Math.max(Objects.requireNonNullElse(setting.getQuestionCount(), 0), 0);
        long completed = questionAnswerService
                .listByAssessmentIdAndStudentId(assessmentId, studentId)
                .stream()
                .filter(answer -> StringUtils.isNotBlank(answer.getAnswer()))
                .count();
        if (completed < expected) {
            throw notReady();
        }
    }

    private StudentAssessmentRecordPO requireActiveRecord(Integer studentId, Integer assessmentId) {
        return recordService.findByStudentIdAndAssessmentId(studentId, assessmentId)
                .filter(record -> Objects.equals(record.getState(), 0))
                .orElseThrow(StudentInterviewLifecycleGuard::notReady);
    }

    private static BusinessException notReady() {
        return new BusinessException(ErrorCodeEnums.INTERVIEW_ACTION_NOT_READY);
    }
}
