package system.assessment.defense.security;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import system.assessment.defense.application.dto.AssessmentSettingsRemoveCmd;
import system.assessment.defense.application.dto.StudentDefenseFileUploadCmd;
import system.assessment.defense.application.dto.StudentAssessmentDetailDTO;
import system.assessment.defense.application.dto.WebsocketMessageDTO;
import system.assessment.defense.application.dto.backed.CancelStudentAppointmentCmd;
import system.assessment.defense.application.dto.backed.AppointmentSettingCreateCmd;
import system.assessment.defense.application.dto.backed.LiftPunishCmd;
import system.assessment.defense.application.dto.backed.StudentRecordChangeScoreCmd;
import system.assessment.defense.application.manage.GeminiManager;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.application.manage.RealtimeSpeechManager;
import system.assessment.defense.application.manage.TtsManager;
import system.assessment.defense.application.service.AssessmentAppService;
import system.assessment.defense.application.service.AssessmentMaterialService;
import system.assessment.defense.application.service.FileAccessControlService;
import system.assessment.defense.application.service.InterviewSessionStore;
import system.assessment.defense.application.service.ManagedObjectCleanupService;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.application.service.StudentAssessmentService;
import system.assessment.defense.application.service.StudentAssessmentAnalysisService;
import system.assessment.defense.application.service.StudentAssessmentQueryService;
import system.assessment.defense.application.service.StudentAppointmentApplicationService;
import system.assessment.defense.application.service.StudentInterviewLifecycleGuard;
import system.assessment.defense.application.service.StudentInterviewLifecycleTransitionService;
import system.assessment.defense.application.service.StudentInterviewSessionService;
import system.assessment.defense.application.service.StudyRecordService;
import system.assessment.defense.application.service.SecurityRateLimitService;
import system.assessment.defense.infrastructure.common.HttpUtils;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.emuns.WebsocketActionEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentAppointmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentAppointmentPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentQuestionAnswerPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.mapper.AssessmentSettingMapper;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentAppointmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentInviteService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSchoolClassService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentStudentRelationService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.Optional;
import java.util.List;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadPoolExecutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ObjectOwnershipEnforcementTest {

    @Mock private UserService userService;
    @Mock private AssessmentSettingService settingService;
    @Mock private AssessmentSettingMapper settingMapper;
    @Mock private StudentAssessmentRecordService recordService;
    @Mock private StudentAssessmentQuestionAnswerService questionAnswerService;
    @Mock private AssessmentStudentRelationService relationService;
    @Mock private AssessmentSchoolClassService schoolClassService;
    @Mock private AssessmentInviteService inviteService;
    @Mock private AssessmentAppointmentSettingService appointmentSettingService;
    @Mock private StudentAssessmentAppointmentService studentAppointmentService;
    @Mock private OssManager ossManager;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private ResourceAccessControlService accessControlService;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private HttpUtils httpUtils;
    @Mock private GeminiManager geminiManager;
    @Mock private AssessmentMaterialService materialService;
    @Mock private RealtimeSpeechManager realtimeSpeechManager;
    @Mock private TtsManager ttsManager;
    @Mock private ThreadPoolExecutor threadPoolExecutor;
    @Mock private SecurityRateLimitService rateLimitService;
    @Mock private InterviewSessionStore interviewSessionStore;
    @Mock private ManagedObjectCleanupService objectCleanupService;
    @Mock private FileAccessControlService fileAccessControlService;

    @InjectMocks private AssessmentAppService assessmentAppService;
    @InjectMocks private StudyRecordService studyRecordService;
    private StudentAssessmentService studentAssessmentService;

    @BeforeEach
    void setUpStudentAssessmentFacade() {
        StudentAssessmentQueryService queryService = new StudentAssessmentQueryService(
                recordService,
                userService,
                settingService,
                appointmentSettingService,
                ossManager,
                questionAnswerService,
                studentAppointmentService,
                accessControlService
        );
        StudentAssessmentAnalysisService analysisService =
                org.mockito.Mockito.mock(StudentAssessmentAnalysisService.class);
        StudentInterviewSessionService interviewSessionService =
                new StudentInterviewSessionService(
                        redisTemplate,
                        interviewSessionStore,
                        recordService,
                        settingService,
                        httpUtils,
                        ossManager,
                        geminiManager,
                        materialService,
                        realtimeSpeechManager,
                        ttsManager,
                        threadPoolExecutor,
                        questionAnswerService,
                        org.mockito.Mockito.mock(StudentInterviewLifecycleTransitionService.class),
                        accessControlService,
                        rateLimitService,
                        org.mockito.Mockito.mock(StudentInterviewLifecycleGuard.class),
                        fileAccessControlService,
                        objectCleanupService,
                        analysisService
                );
        studentAssessmentService = new StudentAssessmentService(
                queryService,
                org.mockito.Mockito.mock(StudentAppointmentApplicationService.class),
                analysisService,
                interviewSessionService
        );
    }

    @Test
    void assessmentDeletionChecksManagerBeforeMutatingRelations() {
        AssessmentSettingsRemoveCmd command = new AssessmentSettingsRemoveCmd();
        command.setId(21);
        when(settingMapper.softDeleteWithTimestamp(eq(21), any())).thenReturn(1);

        assessmentAppService.removeAssessmentSettings(7, command);

        var order = inOrder(accessControlService, relationService, settingMapper, objectCleanupService);
        order.verify(accessControlService).requireAssessmentManager(7, 21);
        order.verify(relationService).remove(any());
        order.verify(settingMapper).softDeleteWithTimestamp(eq(21), any());
        order.verify(objectCleanupService).deleteInterviewArtifacts(any(), any());
    }

    @Test
    void failedAssessmentDeleteDoesNotScheduleArtifactRemoval() {
        AssessmentSettingsRemoveCmd command = new AssessmentSettingsRemoveCmd();
        command.setId(21);
        when(settingMapper.softDeleteWithTimestamp(eq(21), any())).thenReturn(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        assessmentAppService.removeAssessmentSettings(7, command))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCodeEnums.ASSESSMENT_SETTING_NOT_FOUND.getCode());

        verifyNoInteractions(objectCleanupService);
    }

    @Test
    void assessmentDeletionRemovesEveryDependentRowBeforeSoftDelete() {
        AssessmentSettingsRemoveCmd command = new AssessmentSettingsRemoveCmd();
        command.setId(21);
        when(settingMapper.softDeleteWithTimestamp(eq(21), any())).thenReturn(1);

        assessmentAppService.removeAssessmentSettings(7, command);

        var order = inOrder(
                relationService,
                schoolClassService,
                inviteService,
                studentAppointmentService,
                appointmentSettingService,
                recordService,
                questionAnswerService,
                settingMapper);
        order.verify(relationService).remove(any());
        order.verify(schoolClassService).remove(any());
        order.verify(inviteService).remove(any());
        order.verify(studentAppointmentService).remove(any());
        order.verify(appointmentSettingService).remove(any());
        order.verify(recordService).remove(any());
        order.verify(questionAnswerService).remove(any());
        order.verify(settingMapper).softDeleteWithTimestamp(eq(21), any());
    }

    @Test
    void appointmentAdministrationUsesAppointmentsAssessmentOwnership() {
        StudentAssessmentAppointmentPO appointment = new StudentAssessmentAppointmentPO();
        appointment.setId(33);
        appointment.setAssessmentId(21);
        when(studentAppointmentService.getById(33)).thenReturn(appointment);

        LiftPunishCmd lift = new LiftPunishCmd();
        lift.setId(33);
        assessmentAppService.liftPunish(7, lift);

        CancelStudentAppointmentCmd cancel = new CancelStudentAppointmentCmd();
        cancel.setAppointmentId(33);
        assessmentAppService.cancelStudentAppointment(7, cancel);

        verify(accessControlService, org.mockito.Mockito.times(2))
                .requireAssessmentManager(7, 21);
    }

    @Test
    void recordMutationChecksItsAssessmentOwnership() {
        StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                .id(31)
                .assessmentId(21)
                .studentId(12)
                .build();
        when(recordService.getById(31)).thenReturn(record);
        StudentRecordChangeScoreCmd command = new StudentRecordChangeScoreCmd();
        command.setId(31);
        when(settingService.getById(21)).thenReturn(AssessmentSettingPO.builder()
                .id(21)
                .totalScore(100)
                .build());

        studyRecordService.changeScore(7, command);

        verify(accessControlService).requireAssessmentManager(7, 21);
    }

    @Test
    void replacingEveryAppointmentPeriodRemovesTheOldPeriods() {
        LocalDateTime oldTime = LocalDateTime.of(2026, 10, 10, 9, 0);
        LocalDateTime newTime = LocalDateTime.of(2026, 10, 11, 9, 0);
        when(settingService.findById(21)).thenReturn(Optional.of(
                AssessmentSettingPO.builder().id(21).build()));
        when(appointmentSettingService.list(any(Wrapper.class))).thenReturn(List.of(
                AssessmentAppointmentSettingPO.builder()
                        .id(31).assessmentId(21).timePeriod(oldTime).build()));
        when(settingService.updateById(any(AssessmentSettingPO.class))).thenReturn(true);
        when(appointmentSettingService.removeBatchByIds(List.of(31))).thenReturn(true);
        when(appointmentSettingService.saveBatch(any())).thenReturn(true);

        AppointmentSettingCreateCmd.TimePeriod period = new AppointmentSettingCreateCmd.TimePeriod();
        period.setTimePeriod(newTime);
        period.setParticipantLimit(10);
        period.setLocation("A101");
        AppointmentSettingCreateCmd command = new AppointmentSettingCreateCmd();
        command.setAssessmentId(21);
        command.setAssessmentFailPunish(false);
        command.setTimePeriods(List.of(period));

        assessmentAppService.settingAppointment(7, command);

        verify(appointmentSettingService).removeBatchByIds(List.of(31));
        verify(studentAppointmentService).remove(any());
        verify(appointmentSettingService).saveBatch(any());
    }

    @Test
    void duplicateAppointmentPeriodsAreRejectedBeforePersistence() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 11, 9, 0);
        AppointmentSettingCreateCmd.TimePeriod first = new AppointmentSettingCreateCmd.TimePeriod();
        first.setTimePeriod(time);
        first.setParticipantLimit(10);
        first.setLocation("A101");
        AppointmentSettingCreateCmd.TimePeriod duplicate = new AppointmentSettingCreateCmd.TimePeriod();
        duplicate.setTimePeriod(time);
        duplicate.setParticipantLimit(20);
        duplicate.setLocation("A102");
        AppointmentSettingCreateCmd command = new AppointmentSettingCreateCmd();
        command.setAssessmentId(21);
        command.setAssessmentFailPunish(false);
        command.setTimePeriods(List.of(first, duplicate));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        assessmentAppService.settingAppointment(7, command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复");

        verifyNoInteractions(settingService, appointmentSettingService, studentAppointmentService);
    }

    @Test
    void failedAssessmentAppointmentSettingWriteDoesNotReportSuccess() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 11, 9, 0);
        when(settingService.findById(21)).thenReturn(Optional.of(
                AssessmentSettingPO.builder().id(21).build()));
        when(settingService.updateById(any(AssessmentSettingPO.class))).thenReturn(false);

        AppointmentSettingCreateCmd command = appointmentSettingCommand(null, time);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        assessmentAppService.settingAppointment(7, command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("预约设置");
    }

    @Test
    void failedAssessmentAppointmentPeriodWriteDoesNotReportSuccess() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 11, 9, 0);
        AssessmentAppointmentSettingPO existing = AssessmentAppointmentSettingPO.builder()
                .id(31).assessmentId(21).timePeriod(time).build();
        when(settingService.findById(21)).thenReturn(Optional.of(
                AssessmentSettingPO.builder().id(21).build()));
        when(settingService.updateById(any(AssessmentSettingPO.class))).thenReturn(true);
        when(appointmentSettingService.list(any(Wrapper.class))).thenReturn(List.of(existing));
        when(appointmentSettingService.updateById(existing)).thenReturn(false);

        AppointmentSettingCreateCmd command = appointmentSettingCommand(31, time);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        assessmentAppService.settingAppointment(7, command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("预约时段");
    }

    private AppointmentSettingCreateCmd appointmentSettingCommand(Integer id, LocalDateTime time) {
        AppointmentSettingCreateCmd.TimePeriod period = new AppointmentSettingCreateCmd.TimePeriod();
        period.setId(id);
        period.setTimePeriod(time);
        period.setParticipantLimit(10);
        period.setLocation("A101");
        AppointmentSettingCreateCmd command = new AppointmentSettingCreateCmd();
        command.setAssessmentId(21);
        command.setAssessmentFailPunish(false);
        command.setTimePeriods(List.of(period));
        return command;
    }

    @Test
    void studentUploadChecksAssignmentBeforeWritingRecord() {
        StudentDefenseFileUploadCmd command = cn.hutool.json.JSONUtil.toBean("""
                {"assessmentId":21,"file":{"fileUrl":"defense-assessment-new/users/12/2026/10/03/0123456789abcdef0123456789abcdef.pdf","fileName":"defense.pdf","fileSize":"1024"}}
                """, StudentDefenseFileUploadCmd.class);

        when(recordService.saveOrUpdate(any(StudentAssessmentRecordPO.class))).thenReturn(true);

        studentAssessmentService.uploadDefenseFile(command, 12);

        verify(accessControlService).requireAssignedStudent(12, 21);
        verify(fileAccessControlService).requireStudentDefenseFileBinding(
                12, 21, "defense-assessment-new/users/12/2026/10/03/0123456789abcdef0123456789abcdef.pdf");
    }

    @Test
    void studentCannotBindAnotherUsersUploadedObject() {
        StudentDefenseFileUploadCmd command = cn.hutool.json.JSONUtil.toBean("""
                {"assessmentId":21,"file":{"fileUrl":"defense-assessment-new/users/99/2026/10/03/0123456789abcdef0123456789abcdef.pdf","fileName":"stolen.pdf","fileSize":"1024"}}
                """, StudentDefenseFileUploadCmd.class);
        doThrow(new BusinessException(ErrorCodeEnums.NO_PERMISSION))
                .when(fileAccessControlService).requireStudentDefenseFileBinding(
                        12, 21, command.getFile().getFileUrl());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        studentAssessmentService.uploadDefenseFile(command, 12))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(recordService);
    }

    @Test
    void teacherRecordDetailAndStudentDetailUseTheirOwnObjectChecks() {
        StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                .id(31)
                .assessmentId(21)
                .studentId(12)
                .build();
        when(recordService.getById(31)).thenReturn(record);
        when(recordService.getOne(any())).thenReturn(record);
        when(settingService.findById(21)).thenReturn(Optional.of(
                AssessmentSettingPO.builder()
                        .id(21)
                        .theme("系统设计能力考核")
                        .showResult(true)
                        .build()));
        when(userService.findById(12)).thenReturn(Optional.of(
                UserPO.builder().id(12).name("Student").build()));

        StudentAssessmentDetailDTO teacherDetail =
                studentAssessmentService.assessmentDetailByRecordId(7, 31);
        studentAssessmentService.assessmentDetail(12, 21);

        assertThat(teacherDetail.getTheme()).isEqualTo("系统设计能力考核");
        verify(accessControlService).requireAssessmentManager(7, 21);
        verify(accessControlService).requireAssignedStudent(12, 21);
    }

    @Test
    void hiddenStudentResultNeverSerializesScoresOrAnalysis() {
        StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                .id(31)
                .assessmentId(21)
                .studentId(12)
                .score(88)
                .checkScore(90)
                .state(1)
                .defenseResult("""
                        {"result":[],"defense":{"defenseAnswer":"private defense"},
                         "analysis":{"analysis":[{"name":"quality","score":88,"description":"private analysis"}],
                         "suggestions":[],"strengths":[],"weaknesses":[]},"summary":"private summary"}
                        """)
                .build();
        when(recordService.getOne(any())).thenReturn(record);
        when(settingService.findById(21)).thenReturn(Optional.of(
                AssessmentSettingPO.builder()
                        .id(21)
                        .showResult(false)
                        .passScore(60)
                        .totalScore(100)
                        .build()));
        when(userService.findById(12)).thenReturn(Optional.of(
                UserPO.builder().id(12).name("Student").build()));
        StudentAssessmentDetailDTO detail = studentAssessmentService.assessmentDetail(12, 21);

        assertThat(detail.getShowResult()).isFalse();
        assertThat(detail.getScore()).isNull();
        assertThat(detail.getTotalScore()).isNull();
        assertThat(detail.getPassScore()).isNull();
        assertThat(detail.getResult()).isNull();
        assertThat(detail.getValue()).isNull();
        assertThat(detail.getDefense()).isNull();
        assertThat(detail.getAnalysis()).isNull();
        assertThat(detail.getSummary()).isNull();
    }

    @Test
    void assessmentDetailRebuildsQuestionAnswersFromAuthoritativeRecords() {
        StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                .id(31)
                .assessmentId(21)
                .studentId(12)
                .state(1)
                .defenseResult("""
                        {"result":[{"id":999,"question":"虚构问题","answer":"虚构回答","followQuestion":"","followAnswer":""}],
                         "analysis":{"analysis":[{"name":"quality","score":88,"description":"analysis"}],
                         "suggestions":["suggestion"],"strengths":["strength"],"weaknesses":["weakness"]},
                         "summary":"summary"}
                        """)
                .build();
        StudentAssessmentQuestionAnswerPO actualAnswer = StudentAssessmentQuestionAnswerPO.builder()
                .id(44)
                .assessmentId(21)
                .studentId(12)
                .question("真实问题")
                .answer("真实回答")
                .build();
        when(recordService.getOne(any())).thenReturn(record);
        when(questionAnswerService.listByAssessmentIdAndStudentId(21, 12))
                .thenReturn(List.of(actualAnswer));
        when(settingService.findById(21)).thenReturn(Optional.of(
                AssessmentSettingPO.builder()
                        .id(21)
                        .question(true)
                        .defense(false)
                        .showResult(true)
                        .totalScore(100)
                        .build()));
        when(userService.findById(12)).thenReturn(Optional.of(
                UserPO.builder().id(12).name("Student").build()));

        StudentAssessmentDetailDTO detail = studentAssessmentService.assessmentDetail(12, 21);

        assertThat(detail.getResult()).singleElement().satisfies(answer -> {
            assertThat(answer.getId()).isEqualTo(44);
            assertThat(answer.getQuestion()).isEqualTo("真实问题");
            assertThat(answer.getAnswer()).isEqualTo("真实回答");
            assertThat(answer.getAnswerFile()).isNull();
        });
    }

    @Test
    void malformedStoredEvaluationIsReportedWithoutLeakingParserFailures() {
        StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                .id(31)
                .assessmentId(21)
                .studentId(12)
                .state(1)
                .defenseResult("{not-json")
                .build();
        when(recordService.getOne(any())).thenReturn(record);
        when(settingService.findById(21)).thenReturn(Optional.of(
                AssessmentSettingPO.builder()
                        .id(21)
                        .showResult(true)
                        .totalScore(100)
                        .build()));
        when(userService.findById(12)).thenReturn(Optional.of(
                UserPO.builder().id(12).name("Student").build()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        studentAssessmentService.assessmentDetail(12, 21))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCodeEnums.ANALYSIS_CHECK_FAIL.getCode());
    }

    @Test
    void websocketBudgetRejectionStopsBeforeAnyModelOrRecordWork() {
        WebsocketMessageDTO message = new WebsocketMessageDTO();
        message.setMessage("21");
        message.setAction(WebsocketActionEnums.START_ASSESSMENT.getAction());
        doThrow(new BusinessException(ErrorCodeEnums.RATE_LIMIT_EXCEEDED))
                .when(rateLimitService).checkAiWebSocket(12, 5);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        studentAssessmentService.handleWebsocketMessage(12, message, ignored -> { }))
                .isInstanceOf(BusinessException.class);

        verify(settingService, never()).getById(21);
        verifyNoInteractions(geminiManager);
    }

    @Test
    void websocketAudioUsesBoundedSessionStore() throws Exception {
        ByteBuffer payload = ByteBuffer.wrap(new byte[]{1, 2, 3});

        studentAssessmentService.appendVoice(payload, 12);

        verify(interviewSessionStore).appendAudio(12, payload);
    }
}
