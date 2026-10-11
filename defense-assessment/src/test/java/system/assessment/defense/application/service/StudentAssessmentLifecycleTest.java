package system.assessment.defense.application.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.application.dto.WebSocketResponse;
import system.assessment.defense.application.dto.WebsocketMessageDTO;
import system.assessment.defense.application.dto.front.StudentAppointmentCmd;
import system.assessment.defense.application.manage.GeminiManager;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.application.manage.RealtimeSpeechManager;
import system.assessment.defense.application.manage.TtsManager;
import system.assessment.defense.infrastructure.common.HttpUtils;
import system.assessment.defense.infrastructure.concurrency.MySqlAdvisoryLockService;
import system.assessment.defense.infrastructure.emuns.WebsocketActionEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentAppointmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentAppointmentPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentQuestionAnswerPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentAppointmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentAssessmentLifecycleTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "lifecycle-test"),
                system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentAppointmentPO.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "lifecycle-record-test"),
                StudentAssessmentRecordPO.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "lifecycle-answer-test"),
                StudentAssessmentQuestionAnswerPO.class);
    }

    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final ValueOperations<String, Object> redisValueOperations = mock(ValueOperations.class);
    private final InterviewSessionStore sessionStore = mock(InterviewSessionStore.class);
    private final StudentAssessmentRecordService recordService = mock(StudentAssessmentRecordService.class);
    private final AssessmentSettingService settingService = mock(AssessmentSettingService.class);
    private final RealtimeSpeechManager realtimeSpeechManager = mock(RealtimeSpeechManager.class);
    private final OssManager ossManager = mock(OssManager.class);
    private final GeminiManager geminiManager = mock(GeminiManager.class);
    private final ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
    private final StudentAssessmentQuestionAnswerService questionAnswerService =
            mock(StudentAssessmentQuestionAnswerService.class);
    private final StudentAssessmentAppointmentService appointmentService =
            mock(StudentAssessmentAppointmentService.class);
    private final ResourceAccessControlService accessControlService = mock(ResourceAccessControlService.class);
    private final SecurityRateLimitService rateLimitService = mock(SecurityRateLimitService.class);
    private final MySqlAdvisoryLockService advisoryLockService = mock(MySqlAdvisoryLockService.class);

    private StudentAssessmentService service;
    private StudentAppointmentApplicationService appointmentApplicationService;
    private StudentAssessmentAnalysisService analysisService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(redisValueOperations);
        when(settingService.getById(11)).thenReturn(
                AssessmentSettingPO.builder().id(11).needAppoint(false).build());
        HttpUtils httpUtils = mock(HttpUtils.class);
        AssessmentMaterialService materialService = mock(AssessmentMaterialService.class);
        analysisService = new StudentAssessmentAnalysisService(
                recordService,
                settingService,
                questionAnswerService,
                accessControlService,
                advisoryLockService,
                executor,
                materialService,
                geminiManager,
                httpUtils
        );
        appointmentApplicationService = new StudentAppointmentApplicationService(
                settingService,
                appointmentService,
                accessControlService,
                advisoryLockService
        );
        StudentInterviewSessionService interviewSessionService =
                new StudentInterviewSessionService(
                redisTemplate,
                sessionStore,
                recordService,
                settingService,
                httpUtils,
                ossManager,
                geminiManager,
                materialService,
                realtimeSpeechManager,
                mock(TtsManager.class),
                executor,
                questionAnswerService,
                new StudentInterviewLifecycleTransitionService(recordService, appointmentService),
                accessControlService,
                rateLimitService,
                new StudentInterviewLifecycleGuard(
                        settingService, appointmentService, recordService, questionAnswerService),
                mock(FileAccessControlService.class),
                mock(ManagedObjectCleanupService.class),
                analysisService
        );
        service = new StudentAssessmentService(
                mock(StudentAssessmentQueryService.class),
                appointmentApplicationService,
                analysisService,
                interviewSessionService
        );
    }

    @Test
    void duplicateDefenseStartReturnsTheSameResponseWithoutResettingAudio() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder().defense(true).duration(120).build();
        when(settingService.getById(11)).thenReturn(setting);
        when(recordService.startInterview(anyInt(), anyInt(), any()))
                .thenReturn(true, false);
        when(sessionStore.startAudio(eq(7), anyString(), anyInt())).thenReturn(true);
        when(sessionStore.isAudioRecording(eq(7), anyString())).thenReturn(true);
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7, message(WebsocketActionEnums.START_DEFENSE, 11), responses::add);
        service.handleWebsocketMessage(7, message(WebsocketActionEnums.START_DEFENSE, 11), responses::add);

        assertThat(responses).extracting(WebSocketResponse::getContent)
                .containsExactly("开始答辩", "开始答辩");
        verify(sessionStore, times(1)).startAudio(anyInt(), anyString(), anyInt());
        verify(realtimeSpeechManager, times(1)).startTurn(7);
    }

    @Test
    void disabledDefensePhaseCannotBeStarted() {
        when(settingService.getById(11)).thenReturn(AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(false)
                .defense(false)
                .question(true)
                .build());

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_DEFENSE, 11), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("阶段");

        verify(recordService, never()).startInterview(anyInt(), anyInt(), any());
        verify(realtimeSpeechManager, never()).startTurn(7);
    }

    @Test
    void disabledQuestionPhaseCannotBeStarted() {
        when(settingService.getById(11)).thenReturn(AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(false)
                .defense(true)
                .question(false)
                .build());

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_ASSESSMENT, 11), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("阶段");

        verify(recordService, never()).startQuestionPhase(anyInt(), anyInt(), any(), anyBoolean());
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void combinedAssessmentCannotStartQuestionsBeforeDefenseCompletes() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(false)
                .defense(true)
                .question(true)
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .startDefenseTime(LocalDateTime.now())
                        .build()));

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_ASSESSMENT, 11), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("完成");

        verify(recordService, never()).startQuestionPhase(anyInt(), anyInt(), any(), anyBoolean());
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void combinedAssessmentCannotStartDefenseAfterQuestionPhaseBegins() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(false)
                .defense(true)
                .question(true)
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .questionStartedAt(LocalDateTime.now())
                        .build()));

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_DEFENSE, 11), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("阶段");

        verify(recordService, never()).startInterview(anyInt(), anyInt(), any());
        verify(realtimeSpeechManager, never()).startTurn(7);
    }

    @Test
    void appointmentRequiredAssessmentCannotStartBeforeTheStudentsTimeSlot() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(true)
                .defense(true)
                .duration(120)
                .build();
        StudentAssessmentAppointmentPO appointment = appointment(
                0, LocalDateTime.now().plusMinutes(1));
        when(settingService.getById(11)).thenReturn(setting);
        when(appointmentService.getOne(any())).thenReturn(appointment);

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_DEFENSE, 11), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("预约");

        verify(recordService, never()).startInterview(anyInt(), anyInt(), any());
        verify(sessionStore, never()).startAudio(anyInt(), anyString(), anyInt());
    }

    @Test
    void appointmentRequiredAssessmentCannotStartWithoutAnAppointmentOrAfterTheWindow() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(true)
                .questionCount(3)
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(appointmentService.getOne(any()))
                .thenReturn(null, appointment(0, LocalDateTime.now().minusMinutes(11)));

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_ASSESSMENT, 11), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("预约");
        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_ASSESSMENT, 11), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("预约");

        verify(recordService, never()).startQuestionPhase(anyInt(), anyInt(), any(), anyBoolean());
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void appointmentCanStartInsideItsWindowAndAnActiveAssessmentCanResume() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(true)
                .defense(true)
                .duration(120)
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(appointmentService.getOne(any()))
                .thenReturn(
                        appointment(0, LocalDateTime.now().minusMinutes(1)),
                        appointment(1, LocalDateTime.now().minusHours(1)));
        when(recordService.startInterview(anyInt(), anyInt(), any())).thenReturn(true, false);
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .startDefenseTime(LocalDateTime.now().minusHours(1))
                        .build()));
        when(sessionStore.startAudio(eq(7), anyString(), anyInt())).thenReturn(true);

        service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_DEFENSE, 11, "first-turn"), ignored -> { });
        service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_DEFENSE, 11, "resume-turn"), ignored -> { });

        verify(sessionStore).startAudio(7, "first-turn", 1_048_576);
        verify(sessionStore).startAudio(7, "resume-turn", 1_048_576);
    }

    @Test
    void appointmentRequiredAssessmentMustEnterBeforeAnsweringOrSubmitting() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(true)
                .questionCount(3)
                .answerTime(60)
                .build();
        StudentAssessmentAppointmentPO appointment = appointment(
                0, LocalDateTime.now().minusMinutes(1));
        StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                .id(42)
                .studentId(7)
                .assessmentId(11)
                .state(0)
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(settingService.findById(11)).thenReturn(Optional.of(setting));
        when(appointmentService.getOne(any())).thenReturn(appointment);
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_ANSWER, 11), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("预约");
        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.END_ASSESSMENT, 11), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("预约");

        verify(sessionStore, never()).startAudio(anyInt(), anyString(), anyInt());
        verify(recordService, never()).claimInitialAnalysis(anyInt(), any());
    }

    @Test
    void answerRecordingCannotStartBeforeTheQuestionPhase() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(false)
                .question(true)
                .questionCount(3)
                .answerTime(60)
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_ANSWER, 11), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("阶段");

        verify(sessionStore, never()).startAudio(anyInt(), anyString(), anyInt());
        verify(realtimeSpeechManager, never()).startTurn(7);
    }

    @Test
    void answerRecordingCannotStartBeforeAQuestionHasBeenIssued() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(false)
                .question(true)
                .questionCount(3)
                .answerTime(60)
                .build();
        StudentAssessmentRecordPO activeRecord = StudentAssessmentRecordPO.builder()
                .studentId(7)
                .assessmentId(11)
                .state(0)
                .questionStartedAt(LocalDateTime.now())
                .build();
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(settingService.getById(11)).thenReturn(setting);
        when(recordService.findByStudentIdAndAssessmentId(7, 11))
                .thenReturn(Optional.of(activeRecord));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("student:question:7:11")).thenReturn(null);

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_ANSWER, 11), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("阶段");

        verify(sessionStore, never()).startAudio(anyInt(), anyString(), anyInt());
        verify(realtimeSpeechManager, never()).startTurn(7);
    }

    @Test
    void defenseCannotEndBeforeItHasStarted() {
        when(sessionStore.audioBytes(7, "forged-turn")).thenReturn(new byte[2048]);
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.END_DEFENSE, 11, "forged-turn"), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("阶段");

        verify(ossManager, never()).uploadVoice(any(byte[].class));
        verify(recordService, never()).update(any(Wrapper.class));
    }

    @Test
    void assessmentCannotBeSubmittedUntilEveryEnabledPhaseIsComplete() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(false)
                .defense(true)
                .question(true)
                .questionCount(2)
                .build();
        StudentAssessmentRecordPO unfinishedRecord = StudentAssessmentRecordPO.builder()
                .id(42)
                .studentId(7)
                .assessmentId(11)
                .state(0)
                .startDefenseTime(LocalDateTime.now())
                .questionStartedAt(LocalDateTime.now())
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(settingService.findById(11)).thenReturn(Optional.of(setting));
        when(recordService.findByStudentIdAndAssessmentId(7, 11))
                .thenReturn(Optional.of(unfinishedRecord));
        when(questionAnswerService.count(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.END_ASSESSMENT, 11), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("完成");

        verify(recordService, never()).claimInitialAnalysis(anyInt(), any());
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void assessmentCannotBeSubmittedWhileAnAnswerIsStillBeingTranscribed() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(false)
                .question(true)
                .questionCount(1)
                .build();
        StudentAssessmentRecordPO activeRecord = StudentAssessmentRecordPO.builder()
                .id(42)
                .studentId(7)
                .assessmentId(11)
                .state(0)
                .questionStartedAt(LocalDateTime.now())
                .build();
        StudentAssessmentQuestionAnswerPO pendingAnswer = StudentAssessmentQuestionAnswerPO.builder()
                .id(99)
                .studentId(7)
                .assessmentId(11)
                .question("请说明实现")
                .answer(null)
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(settingService.findById(11)).thenReturn(Optional.of(setting));
        when(recordService.findByStudentIdAndAssessmentId(7, 11))
                .thenReturn(Optional.of(activeRecord));
        when(questionAnswerService.count(any())).thenReturn(1L);
        when(questionAnswerService.listByAssessmentIdAndStudentId(11, 7))
                .thenReturn(List.of(pendingAnswer));

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.END_ASSESSMENT, 11), ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("完成");

        verify(recordService, never()).claimInitialAnalysis(anyInt(), any());
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void interruptedActiveDefenseCanResumeWithANewAudioTurn() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder().defense(true).duration(120).build();
        StudentAssessmentRecordPO activeRecord = StudentAssessmentRecordPO.builder()
                .studentId(7)
                .assessmentId(11)
                .state(0)
                .startDefenseTime(java.time.LocalDateTime.now())
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(recordService.startInterview(anyInt(), anyInt(), any())).thenReturn(false);
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(activeRecord));
        when(sessionStore.startAudio(7, "replacement-turn", 1_048_576)).thenReturn(true);
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7,
                message(WebsocketActionEnums.START_DEFENSE, 11, "replacement-turn"), responses::add);

        assertThat(responses).extracting(WebSocketResponse::getContent).containsExactly("开始答辩");
        verify(sessionStore).startAudio(7, "replacement-turn", 1_048_576);
        verify(realtimeSpeechManager).startTurn(7);
    }

    @Test
    void completedDefenseCannotBeReopenedAsARecordingTurn() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder().defense(true).duration(120).build();
        StudentAssessmentRecordPO completedRecord = StudentAssessmentRecordPO.builder()
                .studentId(7)
                .assessmentId(11)
                .state(1)
                .startDefenseTime(java.time.LocalDateTime.now().minusMinutes(5))
                .endDefenseTime(java.time.LocalDateTime.now())
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(recordService.startInterview(anyInt(), anyInt(), any())).thenReturn(false);
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(completedRecord));
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7,
                message(WebsocketActionEnums.START_DEFENSE, 11, "late-turn"), responses::add);

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.getNormalMessage()).isFalse();
            assertThat(response.getContent()).contains("不可恢复");
        });
        verify(sessionStore, never()).startAudio(anyInt(), anyString(), anyInt());
        verify(realtimeSpeechManager, never()).startTurn(7);
    }

    @Test
    void duplicateQuestionStartDoesNotClearAnswersOrLaunchAnotherGenerator() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder().question(true).questionCount(3).build();
        when(settingService.getById(11)).thenReturn(setting);
        when(recordService.startQuestionPhase(anyInt(), anyInt(), any(), eq(false)))
                .thenReturn(true, false);
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7, message(WebsocketActionEnums.START_ASSESSMENT, 11), responses::add);
        service.handleWebsocketMessage(7, message(WebsocketActionEnums.START_ASSESSMENT, 11), responses::add);

        assertThat(responses).extracting(WebSocketResponse::getContent)
                .containsExactly(
                        "开始考核", "正在生成题目，请稍候...",
                        "{\"index\":1,\"total\":3,\"title\":\"请结合你的答辩内容，补充说明第1个关键技术点的依据、实现细节和验证结果。\",\"follow\":false,\"last\":false}",
                        "题目正在生成中，请稍候再试...");
        verify(questionAnswerService, times(1)).clearQuestionAnswer(7, 11);
        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void duplicateQuestionStartReplaysTheCachedFirstQuestionAfterReconnect() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .question(true)
                .questionCount(1)
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(recordService.startQuestionPhase(anyInt(), anyInt(), any(), eq(false)))
                .thenReturn(false);
        when(sessionStore.getLiveQuestions("7:11")).thenReturn(List.of(
                new InterviewLiveQuestion("架构", "请说明系统如何处理连接中断", false, null)));
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_ASSESSMENT, 11), responses::add);

        assertThat(responses).singleElement().satisfies(response ->
                assertThat(response.getContent()).contains("请说明系统如何处理连接中断"));
        verify(questionAnswerService, never()).clearQuestionAnswer(7, 11);
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void initialQuestionUsesAnImmediateFallbackBeforeTheProviderTaskRuns() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .question(true)
                .questionCount(2)
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(recordService.startQuestionPhase(anyInt(), anyInt(), any(), eq(false)))
                .thenReturn(true);
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_ASSESSMENT, 11), responses::add);

        ArgumentCaptor<List<InterviewLiveQuestion>> initialQuestions = ArgumentCaptor.forClass(List.class);
        verify(sessionStore).putLiveQuestions(eq("7:11"), initialQuestions.capture());
        assertThat(initialQuestions.getValue()).hasSize(2);
        assertThat(responses).extracting(WebSocketResponse::getContent)
                .anySatisfy(content -> assertThat(content).contains("\"title\""));
        verify(executor).execute(any(Runnable.class));
        verify(geminiManager, never()).generateLiveQuestions(any(), any(), any(), any(), anyInt());
    }

    @Test
    void rejectedQuestionGenerationTaskReleasesTheQuestionPhase() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(false)
                .question(true)
                .questionCount(3)
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(recordService.startQuestionPhase(anyInt(), anyInt(), any(), eq(false)))
                .thenReturn(true);
        doAnswer(invocation -> {
            throw new RejectedExecutionException("queue full");
        }).when(executor).execute(any(Runnable.class));

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_ASSESSMENT, 11), ignored -> { }))
                .isInstanceOf(RejectedExecutionException.class);

        verify(recordService).releaseQuestionPhase(eq(7), eq(11), any(), any());
    }

    @Test
    void failedQuestionPreparationReleasesTheQuestionPhaseForRetry() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(false)
                .question(true)
                .questionCount(3)
                .build();
        when(settingService.getById(11)).thenReturn(setting);
        when(recordService.startQuestionPhase(anyInt(), anyInt(), any(), eq(false)))
                .thenReturn(true);
        doThrow(new QueryTimeoutException("redis timeout"))
                .when(redisTemplate).delete(anyString());

        assertThatThrownBy(() -> service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_ASSESSMENT, 11), ignored -> { }))
                .isInstanceOf(QueryTimeoutException.class);

        verify(recordService).releaseQuestionPhase(eq(7), eq(11), any(), any());
        verify(executor, never()).execute(any(Runnable.class));

        ArgumentCaptor<LocalDateTime> startedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(recordService).startQuestionPhase(eq(7), eq(11), startedAt.capture(), eq(false));
        assertThat(startedAt.getValue().getNano()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void questionProviderFailureUsesFallbackQuestionsInsteadOfLeavingTheStudentLoading() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .needAppoint(false)
                .question(true)
                .questionCount(2)
                .build();
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(settingService.getById(11)).thenReturn(setting);
        when(recordService.startQuestionPhase(anyInt(), anyInt(), any(), eq(false)))
                .thenReturn(true);
        when(geminiManager.generateLiveQuestions(anyString(), any(), any(), any(), eq(2)))
                .thenThrow(new IllegalStateException("provider timeout"));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(
                7, message(WebsocketActionEnums.START_ASSESSMENT, 11), responses::add);

        ArgumentCaptor<List<InterviewLiveQuestion>> fallbackQuestions = ArgumentCaptor.forClass(List.class);
        verify(sessionStore).putLiveQuestions(eq("7:11"), fallbackQuestions.capture());
        assertThat(fallbackQuestions.getValue())
                .hasSize(2)
                .allSatisfy(question -> assertThat(question.getQuestion()).isNotBlank());
        verify(recordService, never()).releaseQuestionPhase(eq(7), eq(11), any(), any());
    }

    @Test
    void nextTurnCannotStartBeforePreviousDefenseRealtimeCommitCompletes() {
        String turnId = "defense-turn-1";
        when(settingService.getById(11)).thenReturn(
                AssessmentSettingPO.builder().id(11).needAppoint(false).defense(true).build());
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .startDefenseTime(LocalDateTime.now())
                        .build()));
        when(sessionStore.audioBytes(7, turnId)).thenReturn(new byte[2048]);
        when(ossManager.uploadVoice(any(byte[].class))).thenReturn("defense-voice-key");
        when(recordService.update(any(Wrapper.class))).thenReturn(true);
        when(realtimeSpeechManager.completeTurnAndAwaitTranscript(7))
                .thenReturn(Optional.of("答辩转写"));
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7,
                message(WebsocketActionEnums.END_DEFENSE, 11, turnId), responses::add);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        verify(realtimeSpeechManager, never()).completeTurnAndAwaitTranscript(7);
        verify(sessionStore, never()).clearAudio(7, turnId);
        assertThat(responses).isEmpty();

        task.getValue().run();

        verify(realtimeSpeechManager).completeTurnAndAwaitTranscript(7);
        verify(sessionStore).clearAudio(7, turnId);
        assertThat(responses).extracting(WebSocketResponse::getContent)
                .containsExactly("结束答辩");
    }

    @Test
    void providerFailureDoesNotMasqueradeAsNoAnswerOrCompleteDefense() {
        String turnId = "defense-turn-failure";
        when(settingService.getById(11)).thenReturn(
                AssessmentSettingPO.builder().id(11).needAppoint(false).defense(true).build());
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .startDefenseTime(LocalDateTime.now())
                        .build()));
        when(sessionStore.audioBytes(7, turnId)).thenReturn(new byte[2048]);
        when(ossManager.uploadVoice(any(byte[].class))).thenReturn("failed-defense-voice-key");
        when(recordService.update(any(Wrapper.class))).thenReturn(true);
        when(realtimeSpeechManager.completeTurnAndAwaitTranscript(7)).thenReturn(Optional.empty());
        when(geminiManager.pickVoiceContent(any(byte[].class)))
                .thenThrow(new IllegalStateException("provider unavailable"));
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7,
                message(WebsocketActionEnums.END_DEFENSE, 11, turnId), responses::add);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());

        task.getValue().run();

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.getNormalMessage()).isFalse();
            assertThat(response.getContent()).contains("失败").doesNotContain("没有回答");
        });
        verify(recordService, times(2)).update(any(Wrapper.class));
        verify(ossManager).deleteStoredObject("failed-defense-voice-key");
    }

    @Test
    void concurrentDefenseEndLosingTheAtomicClaimDeletesItsUploadedVoice() {
        String turnId = "concurrent-defense-turn";
        when(settingService.getById(11)).thenReturn(
                AssessmentSettingPO.builder().id(11).needAppoint(false).defense(true).build());
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .startDefenseTime(LocalDateTime.now())
                        .build()));
        when(sessionStore.audioBytes(7, turnId)).thenReturn(new byte[2048]);
        when(ossManager.uploadVoice(any(byte[].class))).thenReturn("losing-defense-voice-key");
        when(recordService.update(any(Wrapper.class))).thenReturn(false);
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7,
                message(WebsocketActionEnums.END_DEFENSE, 11, turnId), responses::add);

        verify(ossManager).deleteStoredObject("losing-defense-voice-key");
        verify(executor, never()).execute(any(Runnable.class));
        verify(sessionStore).clearAudio(7, turnId);
        assertThat(responses).singleElement().satisfies(response ->
                assertThat(response.getContent()).contains("结束"));
    }

    @Test
    void rejectedDefenseTranscriptionTaskRollsBackTheClaimAndUploadedVoice() {
        String turnId = "rejected-defense-turn";
        when(settingService.getById(11)).thenReturn(
                AssessmentSettingPO.builder().id(11).needAppoint(false).defense(true).build());
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .startDefenseTime(LocalDateTime.now())
                        .build()));
        when(sessionStore.audioBytes(7, turnId)).thenReturn(new byte[2048]);
        when(ossManager.uploadVoice(any(byte[].class))).thenReturn("rejected-defense-voice-key");
        when(recordService.update(any(Wrapper.class))).thenReturn(true);
        doAnswer(invocation -> {
            throw new RejectedExecutionException("queue full");
        }).when(executor).execute(any(Runnable.class));

        assertThatThrownBy(() -> service.handleWebsocketMessage(7,
                message(WebsocketActionEnums.END_DEFENSE, 11, turnId), ignored -> { }))
                .isInstanceOf(RejectedExecutionException.class);

        verify(recordService, times(2)).update(any(Wrapper.class));
        verify(ossManager).deleteStoredObject("rejected-defense-voice-key");
        verify(sessionStore).clearAudio(7, turnId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void answerTranscriptionFailureKeepsCurrentQuestionRetryableAndDoesNotAdvance() {
        String turnId = "answer-turn-failure";
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("""
                {"question":"请说明实现","follow":false}
                """);
        when(sessionStore.audioBytes(7, turnId)).thenReturn(new byte[2048]);
        when(ossManager.uploadVoice(any(byte[].class))).thenReturn("voice-key");
        when(settingService.getById(11)).thenReturn(
                AssessmentSettingPO.builder().question(true).followUp(false).build());
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .questionStartedAt(LocalDateTime.now())
                        .build()));
        when(realtimeSpeechManager.completeTurnAndAwaitTranscript(7)).thenReturn(Optional.empty());
        when(geminiManager.pickVoiceContent(any(byte[].class)))
                .thenThrow(new IllegalStateException("provider unavailable"));
        doAnswer(invocation -> {
            StudentAssessmentQuestionAnswerPO answer = invocation.getArgument(0);
            answer.setId(99);
            return true;
        }).when(questionAnswerService).save(any(StudentAssessmentQuestionAnswerPO.class));
        when(questionAnswerService.removeById(99)).thenReturn(true);
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7,
                message(WebsocketActionEnums.END_ANSWER, 11, turnId), responses::add);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        assertThat(responses).isEmpty();

        task.getValue().run();

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.getNormalMessage()).isFalse();
            assertThat(response.getContent()).contains("重新回答");
        });
        verify(questionAnswerService).removeById(99);
        verify(geminiManager, never()).generateFollowUp(anyString(), anyString(), anyString());
        verify(sessionStore).clearAudio(7, turnId);
        verify(valueOperations).set(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingParentAnswerDeletesTheUploadedFollowUpVoice() {
        String turnId = "orphan-follow-up-turn";
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("""
                {"question":"请继续说明","follow":true}
                """);
        when(sessionStore.audioBytes(7, turnId)).thenReturn(new byte[2048]);
        when(ossManager.uploadVoice(any(byte[].class))).thenReturn("orphan-follow-up-voice-key");
        when(settingService.getById(11)).thenReturn(
                AssessmentSettingPO.builder().question(true).build());
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .questionStartedAt(LocalDateTime.now())
                        .build()));
        when(questionAnswerService.lastQuestionAnswer(7, 11)).thenReturn(Optional.empty());
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7,
                message(WebsocketActionEnums.END_ANSWER, 11, turnId), responses::add);

        verify(ossManager).deleteStoredObject("orphan-follow-up-voice-key");
        verify(sessionStore).clearAudio(7, turnId);
        assertThat(responses).singleElement().satisfies(response ->
                assertThat(response.getContent()).contains("未找到上一题记录"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedAnswerRecordInsertKeepsTheQuestionRetryableAndDeletesTheUpload() {
        String turnId = "failed-answer-insert-turn";
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("""
                {"question":"请说明实现","follow":false}
                """);
        when(sessionStore.audioBytes(7, turnId)).thenReturn(new byte[2048]);
        when(ossManager.uploadVoice(any(byte[].class))).thenReturn("unbound-answer-voice-key");
        when(settingService.getById(11)).thenReturn(
                AssessmentSettingPO.builder().question(true).build());
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .questionStartedAt(LocalDateTime.now())
                        .build()));
        when(questionAnswerService.save(any(StudentAssessmentQuestionAnswerPO.class))).thenReturn(false);
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7,
                message(WebsocketActionEnums.END_ANSWER, 11, turnId), responses::add);

        verify(ossManager).deleteStoredObject("unbound-answer-voice-key");
        verify(sessionStore).clearAudio(7, turnId);
        verify(redisTemplate, never()).delete(anyString());
        verify(executor, never()).execute(any(Runnable.class));
        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.getNormalMessage()).isFalse();
            assertThat(response.getContent()).contains("保存失败");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedAnswerTranscriptUpdateRollsBackTheRecordAndUpload() {
        String turnId = "failed-answer-update-turn";
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("""
                {"question":"请说明实现","follow":false}
                """);
        when(sessionStore.audioBytes(7, turnId)).thenReturn(new byte[2048]);
        when(ossManager.uploadVoice(any(byte[].class))).thenReturn("failed-answer-update-voice-key");
        when(settingService.getById(11)).thenReturn(
                AssessmentSettingPO.builder().question(true).followUp(false).build());
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .questionStartedAt(LocalDateTime.now())
                        .build()));
        when(realtimeSpeechManager.completeTurnAndAwaitTranscript(7)).thenReturn(Optional.of("转写内容"));
        doAnswer(invocation -> {
            StudentAssessmentQuestionAnswerPO answer = invocation.getArgument(0);
            answer.setId(99);
            return true;
        }).when(questionAnswerService).save(any(StudentAssessmentQuestionAnswerPO.class));
        when(questionAnswerService.update(any(Wrapper.class))).thenReturn(false);
        when(questionAnswerService.removeById(99)).thenReturn(true);
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7,
                message(WebsocketActionEnums.END_ANSWER, 11, turnId), responses::add);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        verify(questionAnswerService).removeById(99);
        verify(ossManager).deleteStoredObject("failed-answer-update-voice-key");
        verify(valueOperations).set(anyString(), anyString());
        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.getNormalMessage()).isFalse();
            assertThat(response.getContent()).contains("重新回答");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedAnswerRollbackPreservesReferencedUploadAndDoesNotRestoreTheQuestion() {
        String turnId = "failed-answer-rollback-turn";
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("""
                {"question":"请说明实现","follow":false}
                """);
        when(sessionStore.audioBytes(7, turnId)).thenReturn(new byte[2048]);
        when(ossManager.uploadVoice(any(byte[].class))).thenReturn("preserved-answer-voice-key");
        when(settingService.getById(11)).thenReturn(
                AssessmentSettingPO.builder().question(true).followUp(false).build());
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .questionStartedAt(LocalDateTime.now())
                        .build()));
        when(realtimeSpeechManager.completeTurnAndAwaitTranscript(7)).thenReturn(Optional.of("转写内容"));
        doAnswer(invocation -> {
            StudentAssessmentQuestionAnswerPO answer = invocation.getArgument(0);
            answer.setId(99);
            return true;
        }).when(questionAnswerService).save(any(StudentAssessmentQuestionAnswerPO.class));
        when(questionAnswerService.update(any(Wrapper.class))).thenReturn(false);
        when(questionAnswerService.removeById(99)).thenReturn(false);
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7,
                message(WebsocketActionEnums.END_ANSWER, 11, turnId), responses::add);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        verify(ossManager, never()).deleteStoredObject("preserved-answer-voice-key");
        verify(valueOperations, never()).set(anyString(), anyString());
        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.getNormalMessage()).isFalse();
            assertThat(response.getContent()).contains("刷新");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedFollowUpTranscriptUpdateRestoresTheQuestionAndDeletesTheUpload() {
        String turnId = "failed-follow-up-update-turn";
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("""
                {"question":"请继续说明","follow":true}
                """);
        when(sessionStore.audioBytes(7, turnId)).thenReturn(new byte[2048]);
        when(ossManager.uploadVoice(any(byte[].class))).thenReturn("failed-follow-up-update-voice-key");
        when(settingService.getById(11)).thenReturn(
                AssessmentSettingPO.builder().question(true).build());
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(11)
                        .state(0)
                        .questionStartedAt(LocalDateTime.now())
                        .build()));
        when(questionAnswerService.lastQuestionAnswer(7, 11)).thenReturn(Optional.of(
                StudentAssessmentQuestionAnswerPO.builder().id(88).build()));
        when(realtimeSpeechManager.completeTurnAndAwaitTranscript(7)).thenReturn(Optional.of("追问转写"));
        when(questionAnswerService.update(any(Wrapper.class))).thenReturn(false);
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7,
                message(WebsocketActionEnums.END_ANSWER, 11, turnId), responses::add);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        verify(ossManager).deleteStoredObject("failed-follow-up-update-voice-key");
        verify(valueOperations).set(anyString(), anyString());
        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.getNormalMessage()).isFalse();
            assertThat(response.getContent()).contains("重新回答");
        });
    }

    @Test
    void duplicateEndAssessmentOnlySchedulesOneScoringTask() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder().questionCount(0).build();
        StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                .id(42).studentId(7).assessmentId(11).state(0).reAnalysis(false).build();
        when(settingService.findById(11)).thenReturn(Optional.of(setting));
        when(recordService.findByStudentIdAndAssessmentId(7, 11)).thenReturn(Optional.of(record));
        when(questionAnswerService.listByAssessmentIdAndStudentId(11, 7)).thenReturn(List.of());
        when(recordService.claimInitialAnalysis(anyInt(), any())).thenReturn(true, false);
        List<WebSocketResponse> responses = new ArrayList<>();

        service.handleWebsocketMessage(7, message(WebsocketActionEnums.END_ASSESSMENT, 11), responses::add);
        service.handleWebsocketMessage(7, message(WebsocketActionEnums.END_ASSESSMENT, 11), responses::add);

        verify(executor, times(1)).execute(any(Runnable.class));
        assertThat(responses).extracting(WebSocketResponse::getContent)
                .containsExactly("考核已提交，正在分析");
    }

    @Test
    void duplicateManualReanalysisIsRejectedByAtomicClaim() {
        StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                .id(42).studentId(7).assessmentId(11).state(1).reAnalysis(false).build();
        AssessmentSettingPO setting = AssessmentSettingPO.builder().build();
        when(recordService.getById(42)).thenReturn(record);
        when(recordService.claimManualAnalysis(eq(42), any())).thenReturn(true, false);
        when(settingService.findById(11)).thenReturn(Optional.of(setting));
        when(questionAnswerService.listByAssessmentIdAndStudentId(11, 7)).thenReturn(List.of());

        assertThat(service.reanalyze(9, 42)).isTrue();
        assertThatThrownBy(() -> service.reanalyze(9, 42))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正在分析");
        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void schedulersSkipWhenAnotherInstanceOwnsTheirLocks() {
        ReflectionTestUtils.setField(appointmentApplicationService, "runScheduler", "start");
        ReflectionTestUtils.setField(analysisService, "runScheduler", "start");
        when(advisoryLockService.tryWithLock(anyString(), any(Runnable.class))).thenReturn(false);

        service.schedulerCheckStudentAppointment();
        service.schedulerCheckAnalysis();

        verify(advisoryLockService).tryWithLock(
                org.mockito.ArgumentMatchers.eq("scheduler:appointments"), any(Runnable.class));
        verify(advisoryLockService).tryWithLock(
                org.mockito.ArgumentMatchers.eq("scheduler:analysis"), any(Runnable.class));
        verify(appointmentService, never()).list(any(Wrapper.class));
        verify(recordService, never()).list(any(Wrapper.class));
    }

    @Test
    void failedAppointmentRescheduleDoesNotReportSuccess() {
        LocalDateTime timePeriod = LocalDateTime.now().plusHours(1);
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(11)
                .assessmentFailPunish(false)
                .appointmentSettingList(List.of(AssessmentAppointmentSettingPO.builder()
                        .assessmentId(11)
                        .timePeriod(timePeriod)
                        .participantLimit(10)
                        .build()))
                .build();
        StudentAssessmentAppointmentPO existing = appointment(2, timePeriod.minusDays(1));
        StudentAppointmentCmd command = new StudentAppointmentCmd();
        command.setAssessmentId(11);
        command.setTimePeriod(timePeriod);
        when(settingService.findByIdLock(11)).thenReturn(Optional.of(setting));
        when(appointmentService.getOne(any())).thenReturn(existing);
        when(appointmentService.count(any())).thenReturn(0L);
        when(appointmentService.updateById(existing)).thenReturn(false);

        assertThat(appointmentApplicationService.studentAppointment(7, command)).isFalse();
    }

    private WebsocketMessageDTO message(WebsocketActionEnums action, int assessmentId) {
        return message(action, assessmentId, "turn-" + action.getAction());
    }

    private WebsocketMessageDTO message(WebsocketActionEnums action, int assessmentId, String turnId) {
        WebsocketMessageDTO message = new WebsocketMessageDTO();
        message.setAction(action.getAction());
        message.setMessage(String.valueOf(assessmentId));
        if (action == WebsocketActionEnums.START_DEFENSE
                || action == WebsocketActionEnums.END_DEFENSE
                || action == WebsocketActionEnums.START_ANSWER
                || action == WebsocketActionEnums.END_ANSWER) {
            message.setTurnId(turnId);
        }
        return message;
    }

    private StudentAssessmentAppointmentPO appointment(Integer state, LocalDateTime timePeriod) {
        StudentAssessmentAppointmentPO appointment = new StudentAssessmentAppointmentPO();
        appointment.setId(5);
        appointment.setStudentId(7);
        appointment.setAssessmentId(11);
        appointment.setState(state);
        appointment.setTimePeriod(timePeriod);
        return appointment;
    }
}
