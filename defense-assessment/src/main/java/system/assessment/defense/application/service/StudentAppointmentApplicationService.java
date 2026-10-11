package system.assessment.defense.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.dto.front.StudentAppointmentCmd;
import system.assessment.defense.infrastructure.concurrency.MySqlAdvisoryLockService;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentAppointmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentAppointmentPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Owns appointment scheduling, capacity enforcement and appointment mutations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StudentAppointmentApplicationService {

    private static final int APPOINTMENT_CONFIRMED = 0;
    private static final int APPOINTMENT_IN_ASSESSMENT = 1;
    private static final int APPOINTMENT_MISSED = 3;

    private final AssessmentSettingService settingService;
    private final StudentAssessmentAppointmentService studentAppointmentService;
    private final ResourceAccessControlService accessControlService;
    private final MySqlAdvisoryLockService advisoryLockService;

    @Value("${scheduler.start:stop}")
    private String runScheduler;

    @Scheduled(cron = "0 0/1 * * * ?")
    public void schedulerCheckStudentAppointment() {
        if (!Objects.equals(runScheduler, "start")) {
            log.info("schedulerCheckStudentAppointment不启动定时任务");
            return;
        }

        boolean acquired = advisoryLockService.tryWithLock(
                "scheduler:appointments", this::checkExpiredAppointments);
        if (!acquired) {
            log.info("其他实例正在执行预约状态检查，本轮跳过");
        }
    }

    private void checkExpiredAppointments() {
        log.info("schedulerCheckStudentAppointment开始执行");
        // 预约了，但是没有考试的，需要更新状态
        List<StudentAssessmentAppointmentPO> notAssessList = studentAppointmentService.list(Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                .and(state -> state
                        .eq(StudentAssessmentAppointmentPO::getState, APPOINTMENT_CONFIRMED)
                        .or()
                        .isNull(StudentAssessmentAppointmentPO::getState)));
        if (CollectionUtils.isNotEmpty(notAssessList)) {
            LocalDateTime now = LocalDateTime.now();
            for (StudentAssessmentAppointmentPO assessmentAppointmentPO : notAssessList) {
                LocalDateTime timePeriod = assessmentAppointmentPO.getTimePeriod();
                if (timePeriod == null) {
                    log.warn("跳过缺少时间的预约记录, appointmentId:{}, assessmentId:{}",
                            assessmentAppointmentPO.getId(), assessmentAppointmentPO.getAssessmentId());
                    continue;
                }
                if (assessmentAppointmentPO.getState() != null
                        && !Objects.equals(assessmentAppointmentPO.getState(), APPOINTMENT_CONFIRMED)) {
                    log.info("跳过已进入考核的预约, appointmentId:{}, assessmentId:{}",
                            assessmentAppointmentPO.getId(), assessmentAppointmentPO.getAssessmentId());
                    continue;
                }
                // 如果timePeriod+10分钟小于当前时间，则更新为预约未考核
                if (timePeriod.plusMinutes(10).isBefore(now)) {
                    log.info("更新预约未考核, appointmentId:{}, assessmentId:{}",
                            assessmentAppointmentPO.getId(), assessmentAppointmentPO.getAssessmentId());
                    boolean expired = studentAppointmentService.update(
                            Wrappers.lambdaUpdate(StudentAssessmentAppointmentPO.class)
                                    .set(StudentAssessmentAppointmentPO::getState, APPOINTMENT_MISSED)
                                    .set(StudentAssessmentAppointmentPO::getPunishState, 0)
                                    .eq(StudentAssessmentAppointmentPO::getId, assessmentAppointmentPO.getId())
                                    .eq(StudentAssessmentAppointmentPO::getTimePeriod, timePeriod)
                                    .lt(StudentAssessmentAppointmentPO::getTimePeriod, now.minusMinutes(10))
                                    .and(state -> state
                                            .eq(StudentAssessmentAppointmentPO::getState, APPOINTMENT_CONFIRMED)
                                            .or()
                                            .isNull(StudentAssessmentAppointmentPO::getState)));
                    if (!expired) {
                        log.warn("预约未考核状态更新失败，将在下次调度重试, appointmentId:{}",
                                assessmentAppointmentPO.getId());
                    }
                }
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean studentAppointment(Integer studentId, StudentAppointmentCmd appointmentCmd) {
        accessControlService.requireAssignedStudent(studentId, appointmentCmd.getAssessmentId());
        AssessmentSettingPO assessmentSettingPO = settingService.findByIdLock(appointmentCmd.getAssessmentId()).orElseThrow(() -> new BusinessException(ErrorCodeEnums.ASSESSMENT_SETTING_NOT_FOUND));
        AssessmentAppointmentSettingPO appointmentSettingPO = assessmentSettingPO.findAppointmentSetting(appointmentCmd.getTimePeriod());
        if (appointmentSettingPO == null) {
            throw new BusinessException(ErrorCodeEnums.ASSESSMENT_APPOINTMENT_SETTING_NOT_FOUND);
        }
        LocalDateTime timePeriod = appointmentSettingPO.getTimePeriod();
        if (timePeriod.isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCodeEnums.ASSESSMENT_SETTING_NOT_CAN_APPOINTMENT);
        }
        StudentAssessmentAppointmentPO studentExistAppoint = studentAppointmentService.getOne(Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                .eq(StudentAssessmentAppointmentPO::getStudentId, studentId)
                .eq(StudentAssessmentAppointmentPO::getAssessmentId, assessmentSettingPO.getId()));
        if (studentExistAppoint != null) {
            if (Objects.equals(studentExistAppoint.getState(), APPOINTMENT_MISSED)
                    && Objects.equals(assessmentSettingPO.getAssessmentFailPunish(), true)) {
                LocalDateTime rescheduleAppointTime = assessmentSettingPO.getRescheduleAppointTime();
                if (rescheduleAppointTime == null) {
                    throw new BusinessException(ErrorCodeEnums.UNABLE_APPOINT);
                }
                if (rescheduleAppointTime.isAfter(LocalDateTime.now())) {
                    throw new BusinessException(ErrorCodeEnums.UNABLE_APPOINT);
                }
            }
        }
        LambdaQueryWrapper<StudentAssessmentAppointmentPO> countWrapper = Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                .eq(StudentAssessmentAppointmentPO::getAssessmentId, assessmentSettingPO.getId())
                .eq(StudentAssessmentAppointmentPO::getTimePeriod, timePeriod)
                .and(query -> query.in(StudentAssessmentAppointmentPO::getState, APPOINTMENT_CONFIRMED, APPOINTMENT_IN_ASSESSMENT)
                        .or()
                        .isNull(StudentAssessmentAppointmentPO::getState));
        if (studentExistAppoint != null && studentExistAppoint.getId() != null) {
            countWrapper.ne(StudentAssessmentAppointmentPO::getId, studentExistAppoint.getId());
        }
        long appointmentCount = studentAppointmentService.count(countWrapper);
        // 统计是否已经预约满了
        if (appointmentCount >= appointmentSettingPO.getParticipantLimit()) {
            throw new BusinessException(ErrorCodeEnums.ASSESSMENT_SETTING_APPOINTMENT_LIMIT_EXCEED);
        }

        // 判断是否已经预约
        if (studentExistAppoint != null) {
            if (!Objects.equals(studentExistAppoint.getState(), 2) && !Objects.equals(studentExistAppoint.getState(), 4) && !Objects.equals(studentExistAppoint.getState(), APPOINTMENT_MISSED)) {
                throw new BusinessException(ErrorCodeEnums.ASSESSMENT_SETTING_APPOINTMENT_EXIST);
            }
            studentExistAppoint.setAppointmentTime(LocalDateTime.now());
            studentExistAppoint.setState(APPOINTMENT_CONFIRMED);
            studentExistAppoint.setPunishState(-1);
            studentExistAppoint.setTimePeriod(timePeriod);
            return studentAppointmentService.updateById(studentExistAppoint);
        }
        // 创建预约信息
        StudentAssessmentAppointmentPO assessmentAppointmentPO = new StudentAssessmentAppointmentPO();
        assessmentAppointmentPO.setAppointmentTime(LocalDateTime.now());
        assessmentAppointmentPO.setStudentId(studentId);
        assessmentAppointmentPO.setAssessmentId(appointmentCmd.getAssessmentId());
        assessmentAppointmentPO.setTimePeriod(timePeriod);
        assessmentAppointmentPO.setState(APPOINTMENT_CONFIRMED);
        assessmentAppointmentPO.setPunishState(-1);
        return studentAppointmentService.save(assessmentAppointmentPO);
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean cancelAppointment(Integer studentId, Integer assessmentId) {
        accessControlService.requireAssignedStudent(studentId, assessmentId);
        StudentAssessmentAppointmentPO appointment = studentAppointmentService.getOne(
                Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                        .eq(StudentAssessmentAppointmentPO::getStudentId, studentId)
                        .eq(StudentAssessmentAppointmentPO::getAssessmentId, assessmentId));
        LocalDateTime now = LocalDateTime.now();
        if (appointment == null
                || appointment.getId() == null
                || (!Objects.equals(appointment.getState(), APPOINTMENT_CONFIRMED)
                        && appointment.getState() != null)
                || appointment.getTimePeriod() == null
                || !appointment.getTimePeriod().isAfter(now)) {
            throw new BusinessException(ErrorCodeEnums.APPOINTMENT_CANNOT_CANCEL);
        }
        boolean removed = studentAppointmentService.remove(
                Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                .eq(StudentAssessmentAppointmentPO::getId, appointment.getId())
                .eq(StudentAssessmentAppointmentPO::getStudentId, studentId)
                .eq(StudentAssessmentAppointmentPO::getAssessmentId, assessmentId)
                .and(state -> state.eq(StudentAssessmentAppointmentPO::getState, APPOINTMENT_CONFIRMED)
                        .or()
                        .isNull(StudentAssessmentAppointmentPO::getState))
                .gt(StudentAssessmentAppointmentPO::getTimePeriod, now));
        if (!removed) {
            throw new BusinessException(ErrorCodeEnums.APPOINTMENT_CANNOT_CANCEL);
        }
        return true;
    }
}
