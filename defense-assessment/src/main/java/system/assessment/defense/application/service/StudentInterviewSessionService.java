package system.assessment.defense.application.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.dto.*;
import system.assessment.defense.application.manage.AiPromptBoundary;
import system.assessment.defense.application.manage.GeminiManager;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.application.manage.RealtimeSpeechManager;
import system.assessment.defense.application.manage.TtsManager;
import system.assessment.defense.application.manage.WebsocketManager;
import system.assessment.defense.infrastructure.common.*;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.*;
import system.assessment.defense.infrastructure.repository.dao.service.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * @USER taoHouChao
 * @DATE 20:09 2025/8/14
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StudentInterviewSessionService {

    private static final int MIN_VALID_AUDIO_BYTES = 2048;
    private static final int MAX_INITIAL_AUDIO_CAPACITY = 1024 * 1024;
    private static final long INTERVIEW_SESSION_CACHE_HOURS = 3;

    static boolean hasValidAudio(ByteArrayOutputStream byteArrayOutputStream) {
        return byteArrayOutputStream != null && byteArrayOutputStream.size() >= MIN_VALID_AUDIO_BYTES;
    }

    private final RedisTemplate<String, Object> redisTemplate;

    private final InterviewSessionStore sessionStore;

    private final StudentAssessmentRecordService recordService;

    private final AssessmentSettingService settingService;

    private final HttpUtils httpUtils;

    private final OssManager ossManger;

    private final GeminiManager geminiManager;

    private final AssessmentMaterialService assessmentMaterialService;

    private final RealtimeSpeechManager realtimeSpeechManager;

    private final TtsManager ttsManager;

    private final ThreadPoolExecutor threadPoolExecutor;

    private final StudentAssessmentQuestionAnswerService questionAnswerService;

    private final StudentInterviewLifecycleTransitionService lifecycleTransitionService;

    private final ResourceAccessControlService accessControlService;

    private final SecurityRateLimitService rateLimitService;

    private final StudentInterviewLifecycleGuard lifecycleGuard;

    private final FileAccessControlService fileAccessControlService;

    private final ManagedObjectCleanupService objectCleanupService;

    private final StudentAssessmentAnalysisService analysisService;

    private String liveQuestionsKey(Integer studentId, Integer assessmentId) {
        return RedisConstants.STUDENT_LIVE_QUESTIONS.formatted(studentId, assessmentId);
    }

    private String followUpKey(Integer studentId, Integer assessmentId, Integer questionIndex) {
        return RedisConstants.STUDENT_FOLLOW_UP.formatted(studentId, assessmentId, questionIndex);
    }

    private void cacheLiveQuestions(String mapKey, Integer studentId, Integer assessmentId, List<InterviewLiveQuestion> liveQuestions) {
        sessionStore.putLiveQuestions(mapKey, liveQuestions);
        redisTemplate.opsForValue().set(liveQuestionsKey(studentId, assessmentId),
                JSONUtil.toJsonStr(liveQuestions), INTERVIEW_SESSION_CACHE_HOURS, TimeUnit.HOURS);
    }

    private List<InterviewLiveQuestion> getCachedLiveQuestions(String mapKey, Integer studentId, Integer assessmentId, Integer questionCount) {
        List<InterviewLiveQuestion> liveQuestions = sessionStore.getLiveQuestions(mapKey);
        if (CollectionUtils.isEmpty(liveQuestions)) {
            Object cached = redisTemplate.opsForValue().get(liveQuestionsKey(studentId, assessmentId));
            if (cached != null && StringUtils.isNotBlank(cached.toString())) {
                liveQuestions = JSONUtil.toList(JSONUtil.parseArray(cached.toString()), InterviewLiveQuestion.class);
                if (CollectionUtils.isNotEmpty(liveQuestions)) {
                    sessionStore.putLiveQuestions(mapKey, liveQuestions);
                }
            }
        }
        if (questionCount != null && questionCount > 0 && CollectionUtils.isNotEmpty(liveQuestions)
                && liveQuestions.size() != questionCount) {
            cn.hutool.json.JSONArray questions = JSONUtil.parseArray(JSONUtil.toJsonStr(liveQuestions));
            liveQuestions = InterviewQuestionPreparation.normalize(questions, questionCount);
            cacheLiveQuestions(mapKey, studentId, assessmentId, liveQuestions);
        }
        return liveQuestions;
    }

    private void cacheFollowUp(String mapKey, Integer studentId, Integer assessmentId, Integer questionIndex, String followUp) {
        sessionStore.putFollowUp(mapKey, followUp);
        redisTemplate.opsForValue().set(followUpKey(studentId, assessmentId, questionIndex),
                followUp, INTERVIEW_SESSION_CACHE_HOURS, TimeUnit.HOURS);
    }

    private String getCachedFollowUp(String mapKey, Integer studentId, Integer assessmentId, Integer questionIndex) {
        String followUp = sessionStore.getFollowUp(mapKey);
        if (StringUtils.isBlank(followUp)) {
            Object cached = redisTemplate.opsForValue().get(followUpKey(studentId, assessmentId, questionIndex));
            if (cached != null && StringUtils.isNotBlank(cached.toString())) {
                followUp = cached.toString();
                sessionStore.putFollowUp(mapKey, followUp);
            }
        }
        return followUp;
    }

    private void clearInterviewQuestionState(Integer studentId, Integer assessmentId, Integer questionCount) {
        String mapKey = studentId + ":" + assessmentId;
        sessionStore.clearInterview(mapKey);
        redisTemplate.delete(liveQuestionsKey(studentId, assessmentId));
        redisTemplate.delete(RedisConstants.STUDENT_QUESTION.formatted(studentId, assessmentId));
        if (questionCount != null && questionCount > 0) {
            for (int i = 1; i <= questionCount; i++) {
                redisTemplate.delete(followUpKey(studentId, assessmentId, i));
            }
        }
    }


    /**
     * 处理 WebSocket 消息，根据消息内容执行不同的业务逻辑。
     * 支持的业务操作包括：开始答辩、结束答辩、开始作答、结束作答、开始考核、结束考核等。
     *
     * @param studentId  学生ID，标识当前操作的学生
     * @param messageDTO WebSocket消息数据传输对象，包含具体的操作类型和消息内容
     * @param consumer   WebSocket响应结果的回调函数，用于向客户端发送处理结果
     */
    public void handleWebsocketMessage(Integer studentId, WebsocketMessageDTO messageDTO, Consumer<WebSocketResponse> consumer) {
        String message = messageDTO.getMessage();
        int assessmentId = Integer.parseInt(message);
        accessControlService.requireAssignedStudent(studentId, assessmentId);
        rateLimitService.checkAiWebSocket(studentId, estimateAiCost(messageDTO));
        lifecycleGuard.validate(studentId, assessmentId, messageDTO);

        if (messageDTO.startDefense()) {
            startDefense(studentId, consumer, assessmentId, requireAudioTurnId(messageDTO));
            return;
        }

        if (messageDTO.endDefense()) {
            endDefense(studentId, consumer, assessmentId, requireAudioTurnId(messageDTO));
            return;
        }

        if (messageDTO.startAssessment()) {
            startAssessment(studentId, consumer, assessmentId);
            return;
        }

        if (messageDTO.startAnswer()) {
            startAnswer(studentId, consumer, assessmentId, requireAudioTurnId(messageDTO));
            return;
        }

        if (messageDTO.endAnswer()) {
            endAnswer(studentId, consumer, assessmentId, requireAudioTurnId(messageDTO));
            return;
        }

        Optional<AssessmentSettingPO> settingOp = settingService.findById(assessmentId);
        AssessmentSettingPO setting = settingOp.orElseThrow(() -> new BusinessException(ErrorCodeEnums.ASSESSMENT_SETTING_NOT_FOUND));

        Optional<StudentAssessmentRecordPO> studentAssessment = recordService.findByStudentIdAndAssessmentId(studentId, assessmentId);
        StudentAssessmentRecordPO record = studentAssessment.orElseGet(() -> {
            StudentAssessmentRecordPO record1 = StudentAssessmentRecordPO.builder()
                    .studentId(studentId)
                    .assessmentId(assessmentId)
                    .defenseVoice("")
                    .score(-1)
                    .checkScore(null)
                    .state(0)
                    .reAnalysis(false)
                    .build();
            recordService.save(record1);
            return record1;
        });

        List<StudentAssessmentQuestionAnswerPO> questionAnswerPOS = questionAnswerService.listByAssessmentIdAndStudentId(assessmentId, studentId);

        if (messageDTO.generateQuestion()) {
            generateQuestionOrFollowQuestion(studentId, consumer, setting, questionAnswerPOS, assessmentId, record);
            return;
        }

        if (messageDTO.endAssessment()) {
            clearInterviewQuestionState(studentId, assessmentId, setting.getQuestionCount());
            sessionStore.clearAudio(studentId);

            if (record.getId() == null
                    || !lifecycleTransitionService.claimInitialAnalysis(
                    record.getId(), studentId, assessmentId, LocalDateTime.now(),
                    Boolean.TRUE.equals(setting.getNeedAppoint()))) {
                consumer.accept(WebSocketResponse.ofLast("考核已提交，正在分析"));
                return;
            }

            try {
                submitClaimedAnalysis(record.getId(), studentId, setting, questionAnswerPOS,
                        assessmentId, true);
            } catch (RuntimeException exception) {
                recordService.releaseAnalysisClaim(record.getId(), LocalDateTime.now());
                throw exception;
            }
        }
    }

    private int estimateAiCost(WebsocketMessageDTO messageDTO) {
        if (messageDTO.endAssessment()) {
            return 10;
        }
        if (messageDTO.startAssessment() || messageDTO.generateQuestion()) {
            return 5;
        }
        if (messageDTO.startDefense() || messageDTO.endDefense() || messageDTO.endAnswer()) {
            return 3;
        }
        return 1;
    }

    private void startAssessment(Integer studentId, Consumer<WebSocketResponse> consumer, int assessmentId) {
        AssessmentSettingPO setting = settingService.getById(assessmentId);
        // The production schema stores DATETIME at whole-second precision. Keep
        // the claim token at the same precision so a failed generation can
        // reliably release the exact row it claimed.
        LocalDateTime startedAt = LocalDateTime.now().withNano(0);
        boolean started = lifecycleTransitionService.startQuestionPhase(
                studentId, assessmentId, startedAt, Boolean.TRUE.equals(setting.getDefense()),
                Boolean.TRUE.equals(setting.getNeedAppoint()));
        if (!started) {
            log.info("忽略重复开始考核请求, studentId:{}, assessmentId:{}", studentId, assessmentId);
            generateQuestionOrFollowQuestion(
                    studentId,
                    consumer,
                    setting,
                    questionAnswerService.listByAssessmentIdAndStudentId(assessmentId, studentId),
                    assessmentId,
                    null);
            return;
        }
        try {
            consumer.accept(WebSocketResponse.of("开始考核"));
            consumer.accept(WebSocketResponse.of("正在生成题目，请稍候..."));
            questionAnswerService.clearQuestionAnswer(studentId, assessmentId);
            clearInterviewQuestionState(studentId, assessmentId, setting.getQuestionCount());
            List<InterviewLiveQuestion> initialQuestions = InterviewQuestionPreparation.normalize(
                    null, setting.getQuestionCount());
            cacheLiveQuestions(studentId + ":" + assessmentId,
                    studentId, assessmentId, initialQuestions);
            if (CollectionUtils.isNotEmpty(initialQuestions)) {
                GeneratedQuestionDTO firstQuestion = new GeneratedQuestionDTO();
                firstQuestion.setQuestion(initialQuestions.getFirst().getQuestion());
                firstQuestion.setFollow(false);
                sendQuestionTextToStudent(
                        firstQuestion, studentId, assessmentId, 1, setting.getQuestionCount(), consumer);
            }
            threadPoolExecutor.execute(() -> {
                try {
                    String defenseContent = "";
                    Optional<StudentAssessmentRecordPO> recordOp =
                            recordService.findByStudentIdAndAssessmentId(studentId, assessmentId);
                    if (recordOp.isPresent() && StringUtils.isNotBlank(recordOp.get().getDefenseContent())) {
                        defenseContent = recordOp.get().getDefenseContent();
                    }
                    List<InterviewLiveQuestion> liveQuestions;
                    try {
                        String materialContext = assessmentMaterialService.getOrParseMaterialContext(setting);
                        String result = geminiManager.generateLiveQuestions(
                                defenseContent,
                                materialContext,
                                setting.getAssessmentCriteria(),
                                setting.getAssessmentRequirements(),
                                setting.getQuestionCount()
                        );
                        String jsonStr = result.trim();
                        if (jsonStr.startsWith("```")) {
                            int startIdx = jsonStr.indexOf('\n') + 1;
                            int endIdx = jsonStr.lastIndexOf("```");
                            if (endIdx > startIdx) {
                                jsonStr = jsonStr.substring(startIdx, endIdx).trim();
                            }
                        }
                        cn.hutool.json.JSONObject jsonResult = JSONUtil.parseObj(jsonStr);
                        cn.hutool.json.JSONArray questions = jsonResult.getJSONArray("questions");
                        liveQuestions = InterviewQuestionPreparation.normalize(questions, setting.getQuestionCount());
                    } catch (Exception providerException) {
                        log.warn("现场题目生成超时或失败，保留已发送的备用题目, studentId:{}, assessmentId:{}, errorType:{}", studentId,
                                assessmentId, providerException.getClass().getSimpleName());
                        return;
                    }
                    for (int index = 1; index < Math.min(initialQuestions.size(), liveQuestions.size()); index++) {
                        initialQuestions.set(index, liveQuestions.get(index));
                    }
                    cacheLiveQuestions(studentId + ":" + assessmentId, studentId, assessmentId, initialQuestions);
                    log.info("现场后续题目生成完成, studentId:{}, assessmentId:{}, count:{}",
                            studentId, assessmentId, initialQuestions.size());
                } catch (Exception e) {
                    log.error("现场题目生成失败, studentId:{}, assessmentId:{}", studentId, assessmentId, e);
                    recordService.releaseQuestionPhase(
                            studentId, assessmentId, startedAt, LocalDateTime.now());
                    WebsocketManager.sendTextMessage(studentId, WebSocketResponse.of("题目生成失败，请重试"));
                }
            });
        } catch (RuntimeException exception) {
            recordService.releaseQuestionPhase(
                    studentId, assessmentId, startedAt, LocalDateTime.now());
            throw exception;
        }
    }

    private void generateQuestionOrFollowQuestion(Integer studentId,
                                                  Consumer<WebSocketResponse> consumer,
                                                  AssessmentSettingPO setting,
                                                  List<StudentAssessmentQuestionAnswerPO> questionAnswerPOS,
                                                  Integer assessmentId,
                                                  StudentAssessmentRecordPO record) {

        String mapKey = studentId + ":" + assessmentId;
        Integer questionCount = setting.getQuestionCount();
        int answeredCount = questionAnswerPOS.size();

        if (CollectionUtils.isNotEmpty(questionAnswerPOS)) {
            StudentAssessmentQuestionAnswerPO lastAnswer = questionAnswerPOS.getLast();
            if (answeredCount <= questionCount) {
                String followUpKey = mapKey + ":" + answeredCount;
                String followUp = getCachedFollowUp(followUpKey, studentId, assessmentId, answeredCount);
                if (StringUtils.isNotBlank(followUp) && StringUtils.isBlank(lastAnswer.getFollowQuestion())) {
                    GeneratedQuestionDTO followDTO = new GeneratedQuestionDTO();
                    followDTO.setQuestion(followUp);
                    followDTO.setFollow(true);
                    sendQuestionToStudent(followDTO, studentId, assessmentId, answeredCount, questionCount, consumer);
                    return;
                }
            }
            if (answeredCount >= questionCount) {
                sendEndMessage(answeredCount, questionCount, consumer);
                return;
            }
        }

        List<InterviewLiveQuestion> liveQuestions = getCachedLiveQuestions(mapKey, studentId, assessmentId, questionCount);
        if (CollectionUtils.isEmpty(liveQuestions)) {
            consumer.accept(WebSocketResponse.of("题目正在生成中，请稍候再试..."));
            return;
        }
        if (answeredCount >= liveQuestions.size()) {
            sendEndMessage(answeredCount, questionCount, consumer);
            return;
        }

        InterviewLiveQuestion nextQuestion = liveQuestions.get(answeredCount);
        GeneratedQuestionDTO questionDTO = new GeneratedQuestionDTO();
        questionDTO.setQuestion(nextQuestion.getQuestion());
        questionDTO.setFollow(false);
        sendQuestionToStudent(questionDTO, studentId, assessmentId, answeredCount + 1, questionCount, consumer);
    }

    private void sendQuestionToStudent(GeneratedQuestionDTO question, Integer studentId, Integer assessmentId,
                                        int index, int total, Consumer<WebSocketResponse> consumer) {
        sendQuestionTextToStudent(question, studentId, assessmentId, index, total, consumer);

        WebsocketManager.startVoiceSending(studentId);
        try {
            boolean hasVoice = ttsManager.streamVoice(question.getQuestion(), chunk -> {
                if (!WebsocketManager.isVoiceSendingInterrupted(studentId)) {
                    WebsocketManager.sendVoiceChunk(chunk, studentId);
                }
            });
            if (!hasVoice) {
                log.warn("TTS未返回音频, studentId:{}, questionLength:{}",
                        studentId, question.getQuestion() == null ? 0 : question.getQuestion().length());
            }
        } catch (Exception e) {
            log.error("TTS生成失败, studentId:{}, questionLength:{}",
                    studentId, question.getQuestion() == null ? 0 : question.getQuestion().length(), e);
        } finally {
            WebsocketManager.sendVoiceEnd(studentId);
            WebsocketManager.finishVoiceSending(studentId);
        }
    }

    private void sendQuestionTextToStudent(GeneratedQuestionDTO question, Integer studentId, Integer assessmentId,
                                           int index, int total, Consumer<WebSocketResponse> consumer) {
        QuestionDTO questionDTO = QuestionDTO.builder()
                .index(index)
                .total(total)
                .title(question.getQuestion())
                .follow(question.getFollow())
                .last(index >= total)
                .build();
        redisTemplate.opsForValue().set(RedisConstants.STUDENT_QUESTION.formatted(studentId, assessmentId),
                JSONUtil.toJsonStr(question), 10, TimeUnit.MINUTES);
        consumer.accept(WebSocketResponse.of(JSONUtil.toJsonStr(questionDTO)));
    }

    private void sendEndMessage(int answeredCount, int questionCount, Consumer<WebSocketResponse> consumer) {
        QuestionDTO questionDTO = QuestionDTO.builder()
                .index(answeredCount)
                .total(questionCount)
                .title("结束问答")
                .follow(false)
                .last(true)
                .build();
        consumer.accept(WebSocketResponse.of(JSONUtil.toJsonStr(questionDTO)));
    }

    private String getVoiceContent(String content, String voiceUrl) {
        if (StringUtils.isBlank(content)) {
            FileByteDTO answerVoiceBytes = httpUtils.downloadFile(voiceUrl);
            if (answerVoiceBytes == null) {
                return "没有回答";
            }
            return getVoiceContent(answerVoiceBytes.getBytes());
        }
        return content;
    }

    private String getVoiceContent(byte[] bytes) {
        if (bytes == null) {
            return "没有回答";
        }
        // A provider outage is not equivalent to the student giving no answer.
        // Let the caller keep the turn retryable instead of persisting a false
        // "没有回答" transcript that would later be scored as genuine input.
        return geminiManager.pickVoiceContent(bytes);
    }

    private String getRealtimeVoiceContentOrFallback(Integer studentId, byte[] bytes) {
        return realtimeSpeechManager.completeTurnAndAwaitTranscript(studentId)
                .filter(StringUtils::isNotBlank)
                .orElseGet(() -> getVoiceContent(bytes));
    }

    private void submitClaimedAnalysis(Integer recordId,
                                       Integer studentId,
                                       AssessmentSettingPO setting,
                                       List<StudentAssessmentQuestionAnswerPO> questionAnswers,
                                       Integer assessmentId,
                                       boolean notifyStudent) {
        analysisService.submitClaimedAnalysis(
                recordId, studentId, setting, questionAnswers, assessmentId, notifyStudent);
    }

    private void endAnswer(Integer studentId, Consumer<WebSocketResponse> consumer,
                           int assessmentId, String turnId) {
        byte[] rawVoiceBytes = sessionStore.audioBytes(studentId, turnId);
        if (rawVoiceBytes == null) {
            consumer.accept(WebSocketResponse.ofLast("未找到录音数据"));
            return;
        }
        if (rawVoiceBytes.length < MIN_VALID_AUDIO_BYTES) {
            sessionStore.clearAudio(studentId, turnId);
            consumer.accept(WebSocketResponse.ofError("未检测到有效录音，请重新回答"));
            return;
        }
        String currentQuestionKey = RedisConstants.STUDENT_QUESTION.formatted(studentId, assessmentId);
        String generatedQuestionString = (String) redisTemplate.opsForValue().get(currentQuestionKey);
        if (generatedQuestionString == null) {
            sessionStore.clearAudio(studentId, turnId);
            consumer.accept(WebSocketResponse.ofLast("题目已过期，请重新获取"));
            return;
        }
        byte[] voiceBytes = WavAudioEncoder.encodeMono16Bit(rawVoiceBytes, 16_000);
        String voiceUrl = ossManger.uploadVoice(voiceBytes);
        GeneratedQuestionDTO questionDTO = JSONUtil.toBean(generatedQuestionString, GeneratedQuestionDTO.class);
        if (questionDTO.getFollow()) {
            Optional<StudentAssessmentQuestionAnswerPO> lastQuestionAnswerOp = questionAnswerService.lastQuestionAnswer(studentId, assessmentId);
            if (lastQuestionAnswerOp.isEmpty()) {
                deleteFailedVoiceUpload(voiceUrl);
                sessionStore.clearAudio(studentId, turnId);
                redisTemplate.delete(currentQuestionKey);
                consumer.accept(WebSocketResponse.ofLast("未找到上一题记录"));
                return;
            }
            StudentAssessmentQuestionAnswerPO questionAnswer = lastQuestionAnswerOp.get();
            redisTemplate.delete(currentQuestionKey);
            try {
                threadPoolExecutor.execute(() -> {
                    try {
                        String voiceText = getRealtimeVoiceContentOrFallback(studentId, voiceBytes);
                        requireQuestionAnswerUpdated(questionAnswerService.update(Wrappers.lambdaUpdate(StudentAssessmentQuestionAnswerPO.class)
                                .set(StudentAssessmentQuestionAnswerPO::getFollowQuestion, questionDTO.getQuestion())
                                .set(StudentAssessmentQuestionAnswerPO::getFollowAnswerVoice, voiceUrl)
                                .set(StudentAssessmentQuestionAnswerPO::getFollowAnswer, voiceText)
                                .eq(StudentAssessmentQuestionAnswerPO::getId, questionAnswer.getId())));
                        consumer.accept(WebSocketResponse.ofLast("结束答题"));
                        WebsocketManager.sendTextMessage(studentId,
                                WebSocketResponse.of("题目生成完成，请点击获取题目"));
                    } catch (Exception e) {
                        restoreCurrentQuestion(currentQuestionKey, generatedQuestionString);
                        deleteFailedVoiceUpload(voiceUrl);
                        log.error("追问回答转写失败, studentId:{}, assessmentId:{}", studentId, assessmentId, e);
                        consumer.accept(WebSocketResponse.ofError("追问回答处理失败，请重新回答"));
                    } finally {
                        sessionStore.clearAudio(studentId, turnId);
                    }
                });
            } catch (RuntimeException exception) {
                restoreCurrentQuestion(currentQuestionKey, generatedQuestionString);
                deleteFailedVoiceUpload(voiceUrl);
                sessionStore.clearAudio(studentId, turnId);
                throw exception;
            }
        } else {
            StudentAssessmentQuestionAnswerPO questionAnswerPO = StudentAssessmentQuestionAnswerPO.builder()
                    .assessmentId(assessmentId)
                    .studentId(studentId)
                    .question(questionDTO.getQuestion())
                    .answerVoice(voiceUrl)
                    .build();
            try {
                if (!questionAnswerService.save(questionAnswerPO)) {
                    deleteFailedVoiceUpload(voiceUrl);
                    sessionStore.clearAudio(studentId, turnId);
                    consumer.accept(WebSocketResponse.ofError("答题记录保存失败，请重新回答"));
                    return;
                }
            } catch (RuntimeException exception) {
                deleteFailedVoiceUpload(voiceUrl);
                sessionStore.clearAudio(studentId, turnId);
                throw exception;
            }
            redisTemplate.delete(currentQuestionKey);
            try {
                threadPoolExecutor.execute(() -> {
                    AtomicBoolean directFollowUpSent = new AtomicBoolean(false);
                    AtomicBoolean answerAccepted = new AtomicBoolean(false);
                    try {
                        AssessmentSettingPO setting = settingService.getById(assessmentId);
                        if (Objects.equals(setting.getFollowUp(), true)) {
                            List<StudentAssessmentQuestionAnswerPO> answers = questionAnswerService.listByAssessmentIdAndStudentId(assessmentId, studentId);
                            int questionIndex = answers.size();
                            String followUpKey = studentId + ":" + assessmentId + ":" + questionIndex;
                            WebsocketManager.sendTextMessage(studentId, WebSocketResponse.of("正在生成追问，请稍候..."));
                            AtomicReference<String> realtimeAnswerText = new AtomicReference<>();
                            if (tryGenerateAndSendRealtimeFollowUp(studentId, assessmentId, setting, questionDTO,
                                    followUpKey, questionIndex, realtimeAnswerText)) {
                                String voiceText = realtimeAnswerOrFallback(realtimeAnswerText.get(), voiceBytes);
                                requireQuestionAnswerUpdated(questionAnswerService.update(Wrappers.lambdaUpdate(StudentAssessmentQuestionAnswerPO.class)
                                        .set(StudentAssessmentQuestionAnswerPO::getAnswer, voiceText)
                                        .eq(StudentAssessmentQuestionAnswerPO::getId, questionAnswerPO.getId())));
                                answerAccepted.set(true);
                                directFollowUpSent.set(true);
                                consumer.accept(WebSocketResponse.ofLast("结束答题"));
                                return;
                            }
                            String voiceText = realtimeAnswerOrFallback(realtimeAnswerText.get(), voiceBytes);
                            requireQuestionAnswerUpdated(questionAnswerService.update(Wrappers.lambdaUpdate(StudentAssessmentQuestionAnswerPO.class)
                                    .set(StudentAssessmentQuestionAnswerPO::getAnswer, voiceText)
                                    .eq(StudentAssessmentQuestionAnswerPO::getId, questionAnswerPO.getId())));
                            answerAccepted.set(true);
                            try {
                                String followUp = geminiManager.generateFollowUp(
                                        questionDTO.getQuestion(),
                                        voiceText,
                                        StringUtils.defaultIfBlank(setting.getFollowUpStandards(), setting.getFollowUpPrompt())
                                );
                                if (StringUtils.isBlank(followUp)) {
                                    followUp = buildDefaultFollowUpQuestion(questionDTO.getQuestion());
                                }
                                cacheFollowUp(followUpKey, studentId, assessmentId, questionIndex, followUp);
                                log.info("追问生成完成, studentId:{}, assessmentId:{}, questionIndex:{}",
                                        studentId, assessmentId, questionIndex);
                            } catch (Exception e) {
                                log.error("追问生成失败, studentId:{}, assessmentId:{}", studentId, assessmentId, e);
                                cacheFollowUp(followUpKey, studentId, assessmentId, questionIndex,
                                        buildDefaultFollowUpQuestion(questionDTO.getQuestion()));
                                WebsocketManager.sendTextMessage(studentId, WebSocketResponse.of("追问生成失败，将使用默认追问"));
                            }
                        } else {
                            String voiceText = getRealtimeVoiceContentOrFallback(studentId, voiceBytes);
                            requireQuestionAnswerUpdated(questionAnswerService.update(Wrappers.lambdaUpdate(StudentAssessmentQuestionAnswerPO.class)
                                    .set(StudentAssessmentQuestionAnswerPO::getAnswer, voiceText)
                                    .eq(StudentAssessmentQuestionAnswerPO::getId, questionAnswerPO.getId())));
                            answerAccepted.set(true);
                        }
                        consumer.accept(WebSocketResponse.ofLast("结束答题"));
                    } catch (Exception e) {
                        boolean rolledBack = rollbackAnswerRecord(
                                questionAnswerPO.getId(), currentQuestionKey, generatedQuestionString, voiceUrl);
                        log.error("普通题回答处理失败, studentId:{}, assessmentId:{}", studentId, assessmentId, e);
                        consumer.accept(WebSocketResponse.ofError(rolledBack
                                ? "答题处理失败，请重新回答"
                                : "答题状态回滚失败，请刷新页面后重试"));
                    } finally {
                        sessionStore.clearAudio(studentId, turnId);
                        if (answerAccepted.get() && !directFollowUpSent.get()) {
                            WebsocketManager.sendTextMessage(studentId,
                                    WebSocketResponse.of("题目生成完成，请点击获取题目"));
                        }
                    }
                });
            } catch (RuntimeException exception) {
                rollbackAnswerRecord(
                        questionAnswerPO.getId(), currentQuestionKey, generatedQuestionString, voiceUrl);
                sessionStore.clearAudio(studentId, turnId);
                throw exception;
            }
        }
    }

    private void restoreCurrentQuestion(String currentQuestionKey, String generatedQuestionString) {
        redisTemplate.opsForValue().set(currentQuestionKey, generatedQuestionString);
    }

    private void requireQuestionAnswerUpdated(boolean updated) {
        if (!updated) {
            throw new IllegalStateException("答题记录更新失败");
        }
    }

    private boolean rollbackAnswerRecord(Integer answerId, String questionKey,
                                         String question, String voiceUrl) {
        if (!questionAnswerService.removeById(answerId)) {
            log.warn("答题记录回滚失败，保留仍可能被引用的语音对象, answerId:{}", answerId);
            return false;
        }
        restoreCurrentQuestion(questionKey, question);
        deleteFailedVoiceUpload(voiceUrl);
        return true;
    }

    private void deleteFailedVoiceUpload(String voiceUrl) {
        try {
            ossManger.deleteStoredObject(voiceUrl);
        } catch (RuntimeException exception) {
            log.warn("failed answer voice cleanup skipped, referenceLength:{}, errorType:{}",
                    voiceUrl == null ? 0 : voiceUrl.length(), exception.getClass().getSimpleName());
        }
    }

    private String realtimeAnswerOrFallback(String realtimeAnswerText, byte[] voiceBytes) {
        if (StringUtils.isNotBlank(realtimeAnswerText)) {
            return realtimeAnswerText;
        }
        return getVoiceContent(voiceBytes);
    }

    private boolean tryGenerateAndSendRealtimeFollowUp(Integer studentId,
                                                       Integer assessmentId,
                                                       AssessmentSettingPO setting,
                                                       GeneratedQuestionDTO originalQuestion,
                                                       String followUpKey,
                                                       int questionIndex,
                                                       AtomicReference<String> answerTextRef) {
        AtomicBoolean sentToStudent = new AtomicBoolean(false);
        AtomicReference<String> followUpRef = new AtomicReference<>();
        WebsocketManager.startVoiceSending(studentId);
        try {
            Optional<RealtimeSpeechManager.RealtimeTurnResponse> response = realtimeSpeechManager.completeTurnAndStreamResponse(
                    studentId,
                    answerText -> {
                        answerTextRef.set(answerText);
                        return buildRealtimeFollowUpInstructions(originalQuestion.getQuestion(), answerText,
                                StringUtils.defaultIfBlank(setting.getFollowUpStandards(), setting.getFollowUpPrompt()));
                    },
                    chunk -> {
                        if (!WebsocketManager.isVoiceSendingInterrupted(studentId)) {
                            WebsocketManager.sendVoiceChunk(chunk, studentId);
                        }
                    },
                    transcript -> {
                        String followUp = normalizeFollowUpQuestion(transcript, originalQuestion.getQuestion());
                        followUpRef.set(followUp);
                        sendRealtimeFollowUpText(studentId, assessmentId, setting, followUpKey, questionIndex, followUp);
                        sentToStudent.set(true);
                    }
            );

            String followUp = response
                    .map(RealtimeSpeechManager.RealtimeTurnResponse::responseTranscript)
                    .filter(StringUtils::isNotBlank)
                    .map(text -> normalizeFollowUpQuestion(text, originalQuestion.getQuestion()))
                    .orElseGet(followUpRef::get);
            response.map(RealtimeSpeechManager.RealtimeTurnResponse::inputTranscript)
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(answerTextRef::set);
            if (StringUtils.isBlank(followUp)) {
                return false;
            }
            if (!sentToStudent.get()) {
                sendRealtimeFollowUpText(studentId, assessmentId, setting, followUpKey, questionIndex, followUp);
                sentToStudent.set(true);
            }
            log.info("Realtime追问生成完成, studentId:{}, assessmentId:{}, questionIndex:{}, hasAudio:{}",
                    studentId, assessmentId, questionIndex, response.map(RealtimeSpeechManager.RealtimeTurnResponse::hasAudio).orElse(false));
            return true;
        } catch (Exception e) {
            log.warn("Realtime追问生成失败, studentId:{}, assessmentId:{}, error:{}",
                    studentId, assessmentId, e.getMessage());
            return false;
        } finally {
            WebsocketManager.sendVoiceEnd(studentId);
            WebsocketManager.finishVoiceSending(studentId);
        }
    }

    private void sendRealtimeFollowUpText(Integer studentId,
                                          Integer assessmentId,
                                          AssessmentSettingPO setting,
                                          String followUpKey,
                                          int questionIndex,
                                          String followUp) {
        cacheFollowUp(followUpKey, studentId, assessmentId, questionIndex, followUp);
        GeneratedQuestionDTO followDTO = new GeneratedQuestionDTO();
        followDTO.setQuestion(followUp);
        followDTO.setFollow(true);
        sendQuestionTextToStudent(followDTO, studentId, assessmentId, questionIndex, setting.getQuestionCount(),
                response -> WebsocketManager.sendTextMessage(studentId, response));
    }

    private String buildRealtimeFollowUpInstructions(String originalQuestion, String answerText, String followUpStandards) {
        return """
                你是正在进行大学综合答辩的AI面试官。请基于学生刚才的语音回答，直接生成一个追问问题，并用清晰自然的中文语音说出来。

                要求：
                1. 只输出追问问题本身，不要解释、不要编号、不要输出JSON或Markdown。
                2. 必须围绕原问题和学生回答继续深入，不要更换无关主题。
                3. 优先追问依据、实现细节、验证数据、关键技术、对比逻辑或学生回答中含糊的地方。
                4. 语言简洁明确，适合作为面试现场的一句话追问。
                %s
                %s
                """.formatted(
                AiPromptBoundary.SYSTEM_GUARD,
                AiPromptBoundary.wrapUntrustedData(Map.of(
                        "originalQuestion", StringUtils.defaultIfBlank(originalQuestion, "无"),
                        "studentAnswerTranscript", StringUtils.defaultIfBlank(answerText, "以刚才的语音回答为准"),
                        "followUpStandards", StringUtils.defaultIfBlank(followUpStandards, "围绕回答继续深入追问")
                )));
    }

    private String normalizeFollowUpQuestion(String text, String originalQuestion) {
        String followUp = StringUtils.trimToEmpty(text);
        if (followUp.startsWith("```")) {
            int startIdx = followUp.indexOf('\n') + 1;
            int endIdx = followUp.lastIndexOf("```");
            if (startIdx > 0 && endIdx > startIdx) {
                followUp = followUp.substring(startIdx, endIdx).trim();
            }
        }
        followUp = StringUtils.strip(followUp, "\"'“”‘’");
        return StringUtils.defaultIfBlank(followUp, buildDefaultFollowUpQuestion(originalQuestion));
    }

    private String buildDefaultFollowUpQuestion(String originalQuestion) {
        if (StringUtils.isBlank(originalQuestion)) {
            return "请进一步补充说明你的依据、实现细节和验证结果。";
        }
        return "围绕刚才的问题，请进一步补充说明你的依据、实现细节和验证结果。";
    }

    private void startAnswer(Integer studentId, Consumer<WebSocketResponse> consumer,
                             int assessmentId, String turnId) {
        AssessmentSettingPO setting = settingService.getById(assessmentId);
        Object currentQuestion = redisTemplate.opsForValue().get(
                RedisConstants.STUDENT_QUESTION.formatted(studentId, assessmentId));
        if (currentQuestion == null || StringUtils.isBlank(currentQuestion.toString())) {
            throw new BusinessException(ErrorCodeEnums.INTERVIEW_ACTION_NOT_READY);
        }
        Integer answerTime = setting.getAnswerTime();
        if (!sessionStore.startAudio(studentId, turnId, initialAudioCapacity(answerTime))) {
            consumer.accept(WebSocketResponse.ofError("上一轮录音仍在处理，请稍后重试"));
            return;
        }
        realtimeSpeechManager.startTurn(studentId);
        consumer.accept(WebSocketResponse.ofLast("开始答题"));
    }

    private void endDefense(Integer studentId, Consumer<WebSocketResponse> consumer,
                            int assessmentId, String turnId) {
        byte[] rawVoiceBytes = sessionStore.audioBytes(studentId, turnId);
        if (rawVoiceBytes == null) {
            consumer.accept(WebSocketResponse.ofLast("未找到录音数据"));
            return;
        }
        if (rawVoiceBytes.length < MIN_VALID_AUDIO_BYTES) {
            sessionStore.clearAudio(studentId, turnId);
            consumer.accept(WebSocketResponse.ofError("未检测到有效录音，请重新答辩"));
            return;
        }
        byte[] bytes = WavAudioEncoder.encodeMono16Bit(rawVoiceBytes, 16_000);
        String voiceUrl = ossManger.uploadVoice(bytes);
        boolean claimed = recordService.update(Wrappers.lambdaUpdate(StudentAssessmentRecordPO.class)
                .set(StudentAssessmentRecordPO::getDefenseVoice, voiceUrl)
                .eq(StudentAssessmentRecordPO::getStudentId, studentId)
                .eq(StudentAssessmentRecordPO::getAssessmentId, assessmentId)
                .eq(StudentAssessmentRecordPO::getState, 0)
                .isNotNull(StudentAssessmentRecordPO::getStartDefenseTime)
                .isNull(StudentAssessmentRecordPO::getEndDefenseTime)
                .and(wrapper -> wrapper
                        .isNull(StudentAssessmentRecordPO::getDefenseVoice)
                        .or()
                        .eq(StudentAssessmentRecordPO::getDefenseVoice, "")));
        if (!claimed) {
            deleteFailedVoiceUpload(voiceUrl);
            sessionStore.clearAudio(studentId, turnId);
            consumer.accept(WebSocketResponse.ofLast("答辩结束请求已处理"));
            return;
        }
        // 在实时转写 commit 完成并释放当前 turn 后再确认结束，避免下一轮 startTurn
        // 抢先重置同一学生共享的 realtime transcript future。
        try {
            threadPoolExecutor.execute(() -> {
                try {
                    String defenseContent = getRealtimeVoiceContentOrFallback(studentId, bytes);
                    boolean finalized = recordService.update(Wrappers.lambdaUpdate(StudentAssessmentRecordPO.class)
                            .set(StudentAssessmentRecordPO::getDefenseContent, defenseContent)
                            .set(StudentAssessmentRecordPO::getEndDefenseTime, LocalDateTime.now())
                            .eq(StudentAssessmentRecordPO::getStudentId, studentId)
                            .eq(StudentAssessmentRecordPO::getAssessmentId, assessmentId)
                            .eq(StudentAssessmentRecordPO::getDefenseVoice, voiceUrl)
                            .isNull(StudentAssessmentRecordPO::getEndDefenseTime));
                    if (!finalized) {
                        throw new IllegalStateException("答辩状态已变化，无法完成转写提交");
                    }
                    sessionStore.clearAudio(studentId, turnId);
                    consumer.accept(WebSocketResponse.ofLast("结束答辩"));
                } catch (Exception exception) {
                    rollbackDefenseFinalization(studentId, assessmentId, voiceUrl);
                    sessionStore.clearAudio(studentId, turnId);
                    log.error("答辩转写失败, studentId:{}, assessmentId:{}", studentId, assessmentId, exception);
                    consumer.accept(WebSocketResponse.ofError("答辩录音处理失败，请重新答辩"));
                }
            });
        } catch (RuntimeException exception) {
            rollbackDefenseFinalization(studentId, assessmentId, voiceUrl);
            sessionStore.clearAudio(studentId, turnId);
            throw exception;
        }
    }

    private void rollbackDefenseFinalization(Integer studentId, Integer assessmentId, String voiceUrl) {
        boolean rolledBack = recordService.update(Wrappers.lambdaUpdate(StudentAssessmentRecordPO.class)
                .set(StudentAssessmentRecordPO::getDefenseVoice, null)
                .eq(StudentAssessmentRecordPO::getStudentId, studentId)
                .eq(StudentAssessmentRecordPO::getAssessmentId, assessmentId)
                .eq(StudentAssessmentRecordPO::getDefenseVoice, voiceUrl)
                .isNull(StudentAssessmentRecordPO::getEndDefenseTime));
        if (rolledBack) {
            deleteFailedVoiceUpload(voiceUrl);
        } else {
            log.warn("答辩结束状态回滚失败，保留仍可能被引用的语音对象, studentId:{}, assessmentId:{}",
                    studentId, assessmentId);
        }
    }

    private void startDefense(Integer studentId, Consumer<WebSocketResponse> consumer,
                              int assessmentId, String turnId) {
        AssessmentSettingPO setting = settingService.getById(assessmentId);
        LocalDateTime now = LocalDateTime.now();
        boolean started = lifecycleTransitionService.startDefense(
                studentId, assessmentId, now, Boolean.TRUE.equals(setting.getNeedAppoint()));
        if (!started && sessionStore.isAudioRecording(studentId, turnId)) {
            log.info("忽略重复开始答辩请求, studentId:{}, assessmentId:{}", studentId, assessmentId);
        } else {
            boolean resumable = !started && recordService.findByStudentIdAndAssessmentId(studentId, assessmentId)
                    .filter(record -> Objects.equals(record.getState(), 0))
                    .filter(record -> record.getStartDefenseTime() != null)
                    .filter(record -> record.getEndDefenseTime() == null)
                    .isPresent();
            if (!started && !resumable) {
                consumer.accept(WebSocketResponse.ofError("当前答辩状态不可恢复，请重新进入考核"));
                return;
            }
            if (sessionStore.hasAudio(studentId)
                    || !sessionStore.startAudio(studentId, turnId, initialAudioCapacity(setting.getDuration()))) {
                consumer.accept(WebSocketResponse.ofError("上一轮录音仍在处理，请稍后重试"));
                return;
            }
            realtimeSpeechManager.startTurn(studentId);
        }
        consumer.accept(WebSocketResponse.ofLast("开始答辩"));
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean uploadDefenseFile(StudentDefenseFileUploadCmd uploadCmd, Integer studentId) {
        Integer assessmentId = uploadCmd.getAssessmentId();
        accessControlService.requireAssignedStudent(studentId, assessmentId);
        String fileKey = Optional.ofNullable(uploadCmd.getFile())
                .map(FileBindingCmd::getFileUrl)
                .orElse(null);
        fileAccessControlService.requireStudentDefenseFileBinding(studentId, assessmentId, fileKey);
        String defenseFile = JSONUtil.toJsonStr(uploadCmd.getFile());
        Optional<StudentAssessmentRecordPO> recordOp = recordService.findByStudentIdAndAssessmentId(studentId, assessmentId);
        List<String> previousReferences = recordOp
                .map(StudentAssessmentRecordPO::getDefenseFile)
                .map(objectCleanupService::extractFileReferences)
                .orElseGet(List::of);
        boolean persisted;
        if (recordOp.isPresent()) {
            StudentAssessmentRecordPO record = recordOp.get();
            record.setDefenseFile(defenseFile);
            persisted = recordService.updateById(record);
        } else {
            StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                    .assessmentId(assessmentId)
                    .studentId(studentId)
                    .defenseFile(defenseFile)
                    .defenseVoice("")
                    .score(-1)
                    .checkScore(null)
                    .state(0)
                    .reAnalysis(false)
                    .build();
            persisted = recordService.saveOrUpdate(record);
        }
        if (!persisted) {
            throw new IllegalStateException("Failed to bind student defense file");
        }
        objectCleanupService.queueRemovedReferences(previousReferences, List.of(fileKey));
        return true;
    }

    public void appendVoice(ByteBuffer payload, Integer studentId) throws IOException {
        sessionStore.appendAudio(studentId, payload);
    }

    public void releaseAudio(Integer studentId) {
        sessionStore.clearAudio(studentId);
    }

    private String requireAudioTurnId(WebsocketMessageDTO messageDTO) {
        String turnId = messageDTO.getTurnId();
        if (StringUtils.isBlank(turnId)) {
            throw new IllegalArgumentException("Audio turnId is required");
        }
        return turnId;
    }

    private int initialAudioCapacity(Integer durationSeconds) {
        long seconds = durationSeconds == null ? 0 : Math.max(0, durationSeconds);
        long estimatedBytes = seconds * 3L * 8192L;
        return (int) Math.min(estimatedBytes, MAX_INITIAL_AUDIO_CAPACITY);
    }

}
