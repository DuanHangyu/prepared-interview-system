package system.assessment.defense.application.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import kotlin.Pair;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.dto.*;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.domain.entity.StudentSummary;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.*;
import system.assessment.defense.infrastructure.repository.dao.service.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Read-only student assessment projections and report details.
 */
@Service
@RequiredArgsConstructor
public class StudentAssessmentQueryService {

    private static final int APPOINTMENT_CONFIRMED = 0;
    private static final int APPOINTMENT_IN_ASSESSMENT = 1;

    private final StudentAssessmentRecordService recordService;
    private final UserService userService;
    private final AssessmentSettingService settingService;
    private final AssessmentAppointmentSettingService appointmentSettingService;
    private final OssManager ossManager;
    private final StudentAssessmentQuestionAnswerService questionAnswerService;
    private final StudentAssessmentAppointmentService studentAppointmentService;
    private final ResourceAccessControlService accessControlService;

    static int resolveDisplayScore(Integer checkScore, List<ValueDTO> value) {
        if (checkScore != null) {
            return checkScore;
        }
        if (CollectionUtils.isEmpty(value)) {
            return 0;
        }
        return value.stream()
                .filter(Objects::nonNull)
                .map(ValueDTO::getValue)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    static boolean isDefenseFinishedForCurrentTodo(StudentAssessmentRecordPO record) {
        if (record == null || record.getEndDefenseTime() == null) {
            return false;
        }
        Integer state = record.getState();
        return state == null || Objects.equals(state, 0);
    }

    @Transactional(readOnly = true)
    public StudentDefenseStatisticDTO studentDefenseStatistic(int studentId) {
        // 学生所有的
        StudentSummary studentSummary = userService.findSummaryById(studentId).orElseThrow(() -> new BusinessException(ErrorCodeEnums.USER_NOT_FOUND));
        List<AssessmentStudentRelationPO> relations = studentSummary.getRelations();
        if (CollectionUtils.isEmpty(relations)) {
            return StudentDefenseStatisticDTO.empty();
        }

        return StudentDefenseStatisticDTO.builder()
                .all(relations.size())
                .toAppoint(studentSummary.toAppointAssessmentIds(this::noNeedAppointAssessmentIds, this::timeExpireAppointmentIds).size())
                .todo(studentSummary.todoAssessmentIds(this::noNeedAppointAssessmentIds).size())
                .analysis((int) studentSummary.getRecords().stream().filter(record -> Objects.equals(record.getState(), 2)).count())
                .done(studentSummary.doneAssessmentIds(this::timeExpireAppointmentIds).size())
                .build();
    }

    @Transactional(readOnly = true)
    public IPage<StudentTodoDefenseDTO> todoDefenseList(StudentDefensePagingQuery pagingQuery, Integer studentId) {
        int page = pagingQuery.getPage() != null ? pagingQuery.getPage() : 1;
        int size = pagingQuery.getSize() != null ? pagingQuery.getSize() : 10;
        // 全部的
        StudentSummary studentSummary = userService.findSummaryById(studentId).orElseThrow(() -> new BusinessException(ErrorCodeEnums.USER_NOT_FOUND));
        List<AssessmentStudentRelationPO> relations = studentSummary.getRelations();
        if (CollectionUtils.isEmpty(relations)) {
            return new Page<>(page, size);
        }
        List<Integer> todoAssessmentIds = studentSummary.todoAssessmentIds(this::noNeedAppointAssessmentIds);
        if (CollectionUtils.isEmpty(todoAssessmentIds)) {
            return new Page<>(page, size);
        }

        IPage<AssessmentSettingPO> poPage = settingService.page(new Page<>(page, size)
                , Wrappers.lambdaQuery(AssessmentSettingPO.class)
                        .like(StrUtil.isNotBlank(pagingQuery.getTheme()), AssessmentSettingPO::getTheme, pagingQuery.getTheme())
                        .in(AssessmentSettingPO::getId, todoAssessmentIds)
                        .orderByDesc(AssessmentSettingPO::getId));
        IPage<StudentTodoDefenseDTO> resultPage = poPage.convert(item -> StudentTodoDefenseDTO.builder()
                .id(item.getId())
                .theme(item.getTheme())
                .score(item.getTotalScore())
                .duration(item.getDuration())
                .questionCount(item.getQuestionCount())
                .answerTime(item.getAnswerTime())
                .question(item.getQuestion())
                .defense(item.getDefense())
                .build());
        List<StudentTodoDefenseDTO> resultRecords = resultPage.getRecords();
        if (CollectionUtils.isNotEmpty(resultRecords)) {
            // 查询考核记录
            List<Integer> assessmentIds = resultRecords.stream().map(StudentTodoDefenseDTO::getId).distinct().toList();
            List<StudentAssessmentRecordPO> recordList = recordService.findByStudentIdAndAssessmentIds(studentId, assessmentIds);
            Map<Integer, StudentAssessmentRecordPO> assessmentIdMap = recordList.stream().collect(Collectors.toMap(StudentAssessmentRecordPO::getAssessmentId, record -> record));

            // 转换学生预约记录
            Map<Integer, StudentAssessmentAppointmentPO> assessmentAppointmentTimeMap = studentSummary.getAppointments().stream()
                    .collect(Collectors.toMap(StudentAssessmentAppointmentPO::getAssessmentId, item -> item, (v1, v2) -> v1));

            for (StudentTodoDefenseDTO resultRecord : resultRecords) {
                Optional.ofNullable(assessmentIdMap.get(resultRecord.getId()))
                        .ifPresent((value) -> resultRecord.setFinishDefense(isDefenseFinishedForCurrentTodo(value)));
                Optional.ofNullable(assessmentAppointmentTimeMap.get(resultRecord.getId()))
                        .ifPresent(iem -> {
                            resultRecord.setTimePeriod(iem.getTimePeriod());
                            resultRecord.setLocation(iem.getLocation());
                        });
            }
        }
        return resultPage;
    }

    private List<Integer> noNeedAppointAssessmentIds(List<Integer> assessmentIds) {
        if (CollectionUtils.isEmpty(assessmentIds)) {
            return Collections.emptyList();
        }
        return settingService.list(Wrappers.lambdaQuery(AssessmentSettingPO.class)
                        .in(AssessmentSettingPO::getId, assessmentIds)
                        .eq(AssessmentSettingPO::getNeedAppoint, false))
                .stream()
                .map(AssessmentSettingPO::getId)
                .toList();
    }

    private List<Integer> timeExpireAppointmentIds(List<Integer> assessmentIds) {
        if (CollectionUtils.isEmpty(assessmentIds)) {
            return Collections.emptyList();
        }
        return settingService.list(Wrappers.lambdaQuery(AssessmentSettingPO.class)
                        .in(AssessmentSettingPO::getId, assessmentIds)
                        .lt(AssessmentSettingPO::getLastAppointTime, LocalDateTime.now()))
                .stream()
                .map(AssessmentSettingPO::getId)
                .toList();
    }

    @Transactional(readOnly = true)
    public IPage<StudentTodoDefenseDTO> toAppointmentList(StudentDefensePagingQuery pagingQuery, Integer studentId) {
        int page = pagingQuery.getPage() != null ? pagingQuery.getPage() : 1;
        int size = pagingQuery.getSize() != null ? pagingQuery.getSize() : 10;
        StudentSummary studentSummary = userService.findSummaryById(studentId).orElseThrow(() -> new BusinessException(ErrorCodeEnums.USER_NOT_FOUND));
        List<AssessmentStudentRelationPO> relations = studentSummary.getRelations();
        if (CollectionUtils.isEmpty(relations)) {
            return new Page<>(page, size);
        }
        List<Integer> toAppointIds = studentSummary.toAppointAssessmentIds(this::noNeedAppointAssessmentIds, this::timeExpireAppointmentIds);
        if (CollectionUtils.isEmpty(toAppointIds)) {
            return new Page<>(page, size);
        }
        IPage<AssessmentSettingPO> poPage = settingService.page(new Page<>(page, size)
                , Wrappers.lambdaQuery(AssessmentSettingPO.class)
                        .in(AssessmentSettingPO::getId, toAppointIds)
                        .eq(AssessmentSettingPO::getNeedAppoint, true)
                        .like(StrUtil.isNotBlank(pagingQuery.getTheme()), AssessmentSettingPO::getTheme, pagingQuery.getTheme())
                        .orderByDesc(AssessmentSettingPO::getId));
        List<AssessmentSettingPO> records = poPage.getRecords();
        Map<Integer, List<AssessmentAppointmentSettingPO>> asessmentAppointSettingMap = new HashMap<>();
        Map<Integer, LinkedHashMap<LocalDateTime, Long>> assessmentTimeCountMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(records)) {
            List<Integer> assessmentIds = records.stream().map(AssessmentSettingPO::getId).toList();
            asessmentAppointSettingMap.putAll(asessmentAppointSettingMap(assessmentIds));
            assessmentTimeCountMap.putAll(assessmentTimeCountMap(assessmentIds));
        }
        return poPage.convert(item -> StudentTodoDefenseDTO.builder()
                .id(item.getId())
                .theme(item.getTheme())
                .score(item.getTotalScore())
                .duration(item.getDuration())
                .questionCount(item.getQuestionCount())
                .answerTime(item.getAnswerTime())
                .question(item.getQuestion())
                .defense(item.getDefense())
                .canAppointmentTimes(asessmentAppointSettingMap.getOrDefault(item.getId(), Collections.emptyList())
                        .stream()
                        .map(entry -> {
                            LinkedHashMap<LocalDateTime, Long> timePeriodCountMap = assessmentTimeCountMap.getOrDefault(item.getId(), new LinkedHashMap<>());
                            Integer appointCount = timePeriodCountMap.getOrDefault(entry.getTimePeriod(), 0L).intValue();
                            return StudentTodoDefenseDTO.CanAppointmentTimeDTO.builder()
                                    .timePeriod(entry.getTimePeriod())
                                    .location(entry.getLocation())
                                    .full(appointCount >= entry.getParticipantLimit())
                                    .remainCount(entry.getParticipantLimit() - appointCount)
                                    .build();
                        })
                        .toList())
                .build());
    }

    private Map<Integer, List<AssessmentAppointmentSettingPO>> asessmentAppointSettingMap(List<Integer> assessmentIds) {
        return appointmentSettingService.list(Wrappers.lambdaQuery(AssessmentAppointmentSettingPO.class)
                        .gt(AssessmentAppointmentSettingPO::getTimePeriod, LocalDateTime.now())
                        .in(AssessmentAppointmentSettingPO::getAssessmentId, assessmentIds))
                .stream()
                .filter(setting -> setting.getTimePeriod() != null)
                .collect(Collectors.groupingBy(
                        AssessmentAppointmentSettingPO::getAssessmentId,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                groupedList -> groupedList.stream()
                                        .filter(setting -> setting.getTimePeriod() != null)
                                        .sorted(Comparator.comparing(AssessmentAppointmentSettingPO::getTimePeriod))
                                        .toList()
                        )
                ));
    }

    private Map<Integer, LinkedHashMap<LocalDateTime, Long>> assessmentTimeCountMap(List<Integer> assessmentIds) {
        return studentAppointmentService.list(Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                        .in(StudentAssessmentAppointmentPO::getAssessmentId, assessmentIds)
                        .and(query -> query.in(StudentAssessmentAppointmentPO::getState, APPOINTMENT_CONFIRMED, APPOINTMENT_IN_ASSESSMENT)
                                .or()
                                .isNull(StudentAssessmentAppointmentPO::getState)))
                .stream()
                .collect(Collectors.groupingBy(
                        StudentAssessmentAppointmentPO::getAssessmentId,
                        Collectors.collectingAndThen(
                                Collectors.groupingBy(
                                        StudentAssessmentAppointmentPO::getTimePeriod,
                                        Collectors.counting()
                                ),
                                innerMap -> {
                                    // 按 timePeriod 从小到大排序
                                    return innerMap.entrySet().stream()
                                            .sorted(Map.Entry.comparingByKey())
                                            .collect(Collectors.toMap(
                                                    Map.Entry::getKey,
                                                    Map.Entry::getValue,
                                                    (oldValue, newValue) -> oldValue,
                                                    LinkedHashMap::new // 保证有序
                                            ));
                                }
                        )
                ));
    }

    @Transactional(readOnly = true)
    public IPage<StudentTodoDefenseDTO> analysisDefenseList(StudentDefensePagingQuery pagingQuery, Integer studentId) {
        int page = pagingQuery.getPage() != null ? pagingQuery.getPage() : 1;
        int size = pagingQuery.getSize() != null ? pagingQuery.getSize() : 10;
        List<Integer> analyzeIds = recordService.list(Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                        .eq(StudentAssessmentRecordPO::getStudentId, studentId)
                        .eq(StudentAssessmentRecordPO::getState, 2))
                .stream()
                .map(StudentAssessmentRecordPO::getAssessmentId)
                .distinct()
                .toList();
        if (CollectionUtils.isEmpty(analyzeIds)) {
            return new Page<>(page, size);
        }

        IPage<AssessmentSettingPO> poPage = settingService.page(new Page<>(page, size)
                , Wrappers.lambdaQuery(AssessmentSettingPO.class)
                        .in(AssessmentSettingPO::getId, analyzeIds)
                        .like(StrUtil.isNotBlank(pagingQuery.getTheme()), AssessmentSettingPO::getTheme, pagingQuery.getTheme())
                        .orderByDesc(AssessmentSettingPO::getId));
        IPage<StudentTodoDefenseDTO> resultPage = poPage.convert(item -> StudentTodoDefenseDTO.builder()
                .id(item.getId())
                .theme(item.getTheme())
                .score(item.getTotalScore())
                .duration(item.getDuration())
                .questionCount(item.getQuestionCount())
                .answerTime(item.getAnswerTime())
                .question(item.getQuestion())
                .defense(item.getDefense())
                .build());
        List<StudentTodoDefenseDTO> resultRecords = resultPage.getRecords();
        if (CollectionUtils.isNotEmpty(resultRecords)) {
            List<Integer> assessmentIds = resultRecords.stream().map(StudentTodoDefenseDTO::getId).distinct().toList();
            List<StudentAssessmentRecordPO> recordList = recordService.findByStudentIdAndAssessmentIds(studentId, assessmentIds);
            Map<Integer, StudentAssessmentRecordPO> assessmentIdMap = recordList.stream().collect(Collectors.toMap(StudentAssessmentRecordPO::getAssessmentId, record -> record));
            for (StudentTodoDefenseDTO resultRecord : resultRecords) {
                Optional.ofNullable(assessmentIdMap.get(resultRecord.getId()))
                        .ifPresent((value) -> {
                            resultRecord.setFinishDefense(value.getEndDefenseTime() != null);
                        });
            }
        }
        return resultPage;
    }

    @Transactional(readOnly = true)
    public IPage<StudentDoneDefenseDTO> doneDefenseList(StudentDefensePagingQuery pagingQuery, Integer studentId) {
        int page = pagingQuery.getPage() != null ? pagingQuery.getPage() : 1;
        int size = pagingQuery.getSize() != null ? pagingQuery.getSize() : 10;
        // 全部的
        StudentSummary studentSummary = userService.findSummaryById(studentId).orElseThrow(() -> new BusinessException(ErrorCodeEnums.USER_NOT_FOUND));
        List<AssessmentStudentRelationPO> relations = studentSummary.getRelations();
        if (CollectionUtils.isEmpty(relations)) {
            return new Page<>(page, size);
        }
        List<Integer> completeAppointAssessmentIds = studentSummary.doneAssessmentIds(this::timeExpireAppointmentIds);
        if (CollectionUtils.isEmpty(completeAppointAssessmentIds)) {
            return new Page<>(page, size);
        }
        IPage<AssessmentSettingPO> poPage = settingService.page(new Page<>(page, size)
                , Wrappers.lambdaQuery(AssessmentSettingPO.class)
                        .in(AssessmentSettingPO::getId, completeAppointAssessmentIds)
                        .like(StrUtil.isNotBlank(pagingQuery.getTheme()), AssessmentSettingPO::getTheme, pagingQuery.getTheme())
                        .orderByDesc(AssessmentSettingPO::getId));
        List<AssessmentSettingPO> records = poPage.getRecords();
        if (CollectionUtils.isNotEmpty(records)) {
            List<Integer> assessmentIds = records.stream().map(AssessmentSettingPO::getId).toList();
            Map<Integer, List<AssessmentAppointmentSettingPO>> appointMap = asessmentAppointSettingMap(assessmentIds);
            Map<Integer, LinkedHashMap<LocalDateTime, Long>> studentTimeCountMap = assessmentTimeCountMap(assessmentIds);
            Map<Integer, AssessmentSettingPO> assessmentIdMap = settingService.listByIds(assessmentIds).stream()
                    .collect(Collectors.toMap(AssessmentSettingPO::getId, item -> item));

            Map<Integer, StudentAssessmentRecordPO> studentRecordMap = recordService.list(Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                            .eq(StudentAssessmentRecordPO::getStudentId, studentId)
                            .in(StudentAssessmentRecordPO::getAssessmentId, assessmentIds))
                    .stream()
                    .collect(Collectors.toMap(StudentAssessmentRecordPO::getAssessmentId, item -> item));
            Map<Integer, StudentAssessmentAppointmentPO> studentAppointMap = studentSummary.getAppointments().stream()
                    .collect(Collectors.toMap(StudentAssessmentAppointmentPO::getAssessmentId, item -> item));
            return poPage.convert(item -> {
                AssessmentSettingPO assessmentSettingPO = assessmentIdMap.get(item.getId());
                StudentAssessmentRecordPO record = studentRecordMap.getOrDefault(item.getId(), null);
                StudentAssessmentAppointmentPO studentAssessmentAppointmentPO = studentAppointMap.get(item.getId());
                // 计算状态
                Pair<Integer, String> state = calculateState(assessmentSettingPO, record, studentAssessmentAppointmentPO);
                return StudentDoneDefenseDTO.builder()
                        .id(item.getId())
                        .theme(assessmentSettingPO.getTheme())
                        .score(assessmentSettingPO.getTotalScore())
                        .duration(assessmentSettingPO.getDuration())
                        .assessStartTime(Optional.ofNullable(record).map(StudentAssessmentRecordPO::getCreateTime).orElse(null))
                        .assessmentEndTime(Optional.ofNullable(record).map(StudentAssessmentRecordPO::getUpdateTime).orElse(null))
                        .defenseScore(Optional.ofNullable(record).map(StudentAssessmentRecordPO::frontScore).orElse(null))
                        .state(state.getFirst())
                        .stateTag(state.getSecond())
                        .passScore(item.getPassScore())
                        .assessmentFailPunish(assessmentSettingPO.getAssessmentFailPunish())
                        .rescheduleAppointTime(assessmentSettingPO.getRescheduleAppointTime())
                        .showResult(assessmentSettingPO.getShowResult())
                        .timePeriod(studentAssessmentAppointmentPO == null ? null : studentAssessmentAppointmentPO.getTimePeriod())
                        .location(studentAssessmentAppointmentPO == null ? null : studentAssessmentAppointmentPO.getLocation())
                        .appointRecordFlag(studentAppointMap.get(item.getId()) != null)
                        .canAppointmentTimes(appointMap.getOrDefault(item.getId(), Collections.emptyList())
                                .stream()
                                .map(entry -> {
                                    Integer appointCount = studentTimeCountMap.getOrDefault(item.getId(), new LinkedHashMap<>()).getOrDefault(entry.getTimePeriod(), 0L).intValue();
                                    return StudentDoneDefenseDTO.CanAppointmentTimeDTO.builder()
                                            .timePeriod(entry.getTimePeriod())
                                            .location(entry.getLocation())
                                            .full(appointCount >= entry.getParticipantLimit())
                                            .remainCount(entry.getParticipantLimit() - appointCount)
                                            .build();
                                })
                                .toList())
                        .build();
            });
        }
        return new Page<>(poPage.getCurrent(), poPage.getSize(), poPage.getTotal());
    }

    private Pair<Integer, String> calculateState(AssessmentSettingPO setting,
                                                 StudentAssessmentRecordPO record,
                                                 StudentAssessmentAppointmentPO appointment) {
        Boolean assessmentFailPunish = setting.getAssessmentFailPunish();
        LocalDateTime rescheduleAppointTime = setting.getRescheduleAppointTime();

        int state;
        String stateTag = "";
        // 能在已完成的记录，都是已经通过的
        if (record != null && Objects.equals(record.getState(), 1)) {
            state = 2;
        }
        // 如果有预约的，则返回预约的状态
        else if (appointment != null) {
            state = appointment.getState();
            stateTag = "预约未考";
            // 对于预约未考，需要判断是否过了可重新预约时间
            if (Objects.equals(appointment.getState(), 3)) {
                if (Objects.equals(assessmentFailPunish, true) && Objects.nonNull(rescheduleAppointTime) && rescheduleAppointTime.isBefore(LocalDateTime.now())) {
                    state = 4;
                }
                // 如果没有惩罚，则可以直接重新预约
                if (Objects.equals(assessmentFailPunish, false)) {
                    state = 4;
                }
            }
        }
        // 如果没有考核，也没有预约，那就是过期未预约的
        else {
            state = 5;
            stateTag = "过期未预约";
            if (setting.getLastAppointTime() != null && setting.getLastAppointTime().isBefore(LocalDateTime.now())) {
                // 如果有惩罚，并且过了可重新预约时间，则返回可重新预约
                if (Objects.equals(assessmentFailPunish, true) && Objects.nonNull(rescheduleAppointTime) && rescheduleAppointTime.isBefore(LocalDateTime.now())) {
                    state = 4;
                }
            }
        }
        return new Pair<>(state, stateTag);
    }

    public StudentAssessmentDetailDTO assessmentDetailByRecordId(Integer teacherId, Integer id) {
        StudentAssessmentRecordPO record = recordService.getById(id);
        if (record == null) {
            throw new BusinessException(ErrorCodeEnums.RECORD_NOT_FOUND);
        }
        accessControlService.requireAssessmentManager(teacherId, record.getAssessmentId());
        AssessmentSettingPO setting = settingService.findById(record.getAssessmentId())
                .orElseThrow(() -> new BusinessException(ErrorCodeEnums.ASSESSMENT_SETTING_NOT_FOUND));
        UserPO student = userService.findById(record.getStudentId())
                .orElseThrow(() -> new BusinessException(ErrorCodeEnums.USER_NOT_FOUND));

        String location = Optional.ofNullable(studentAppointmentService.getByStudentIdAndAssessmentId(record.getStudentId(), record.getAssessmentId())).map(StudentAssessmentAppointmentPO::getLocation).orElse(null);
        return getStudentAssessmentDetailDTO(record, student, setting, location);
    }

    public StudentAssessmentDetailDTO assessmentDetail(Integer studentId, Integer id) {
        accessControlService.requireAssignedStudent(studentId, id);
        AssessmentSettingPO setting = settingService.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCodeEnums.ASSESSMENT_SETTING_NOT_FOUND));

        StudentAssessmentRecordPO record = Optional.ofNullable(recordService.getOne(Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                .eq(StudentAssessmentRecordPO::getAssessmentId, id)
                .eq(StudentAssessmentRecordPO::getStudentId, studentId)))
                .orElseThrow(() -> new BusinessException(ErrorCodeEnums.STUDENT_ASSESSMENT_NOT_FOUND));
        UserPO student = userService.findById(record.getStudentId())
                .orElseThrow(() -> new BusinessException(ErrorCodeEnums.USER_NOT_FOUND));
        String location = Optional.ofNullable(studentAppointmentService.getByStudentIdAndAssessmentId(studentId, id)).map(StudentAssessmentAppointmentPO::getLocation).orElse(null);

        if (!Objects.equals(setting.getShowResult(), true)) {
            return StudentAssessmentDetailDTO.builder()
                    .id(record.getId())
                    .studentName(student.getName())
                    .theme(setting.getTheme())
                    .showResult(false)
                    .state(record.getState())
                    .reAnalysis(record.getReAnalysis())
                    .location(location)
                    .build();
        }
        return getStudentAssessmentDetailDTO(record, student, setting, location);
    }

    private StudentAssessmentDetailDTO getStudentAssessmentDetailDTO(StudentAssessmentRecordPO record,
                                                                     UserPO student,
                                                                     AssessmentSettingPO setting,
                                                                     String location) {
        List<StudentAssessmentQuestionAnswerPO> questionAnswers = questionAnswerService
                .listByAssessmentIdAndStudentId(record.getAssessmentId(), record.getStudentId());
        String defenseResult = record.getDefenseResult();
        if (StringUtils.isNotBlank(defenseResult)) {
            AssessmentEvaluationDTO evaluation = StoredAssessmentEvaluationParser.parse(defenseResult);
            Integer checkScore = record.getCheckScore();
            List<ValueDTO> value = evaluation.getValue();
            int totalScore = Math.min(
                    Math.max(0, Optional.ofNullable(setting.getTotalScore()).orElse(0)),
                    Math.max(0, resolveDisplayScore(checkScore, value)));
            DefenseDTO defense = evaluation.getDefense();
            if (defense != null) {
                defense.setDefenseAnswer(record.getDefenseContent());
                defense.setDefenseAnswerFile(Optional.ofNullable(record.getDefenseVoice())
                        .map(ossManager::getSignedUrl)
                        .orElse(null));
            }
            boolean hasQuestionTranscript = Optional.ofNullable(setting.getQuestionCount()).orElse(0) > 0
                    || CollectionUtils.isNotEmpty(questionAnswers)
                    || evaluation.getResult() != null;
            List<AnswerEvaluationDTO> result = hasQuestionTranscript
                    ? questionAnswers.stream().map(this::authoritativeAnswer).toList()
                    : null;
            return StudentAssessmentDetailDTO.builder()
                    .id(record.getId())
                    .studentName(student.getName())
                    .theme(setting.getTheme())
                    .passScore(setting.getPassScore())
                    .score(totalScore)
                    .totalScore(setting.getTotalScore())
                    .result(result)
                    .analysis(evaluation.getAnalysis())
                    .summary(evaluation.getSummary())
                    .value(value)
                    .defense(defense)
                    .showResult(setting.getShowResult())
                    .state(record.getState())
                    .reAnalysis(record.getReAnalysis())
                    .location(location)
                    .build();
        }
        return StudentAssessmentDetailDTO.builder()
                .id(record.getId())
                .studentName(student.getName())
                .theme(setting.getTheme())
                .showResult(setting.getShowResult())
                .state(record.getState())
                .reAnalysis(record.getReAnalysis())
                .location(location)
                .build();
    }

    private AnswerEvaluationDTO authoritativeAnswer(StudentAssessmentQuestionAnswerPO answer) {
        return AnswerEvaluationDTO.builder()
                .id(answer.getId())
                .question(answer.getQuestion())
                .answer(answer.getAnswer())
                .answerFile(signedUrlIfPresent(answer.getAnswerVoice()))
                .followQuestion(answer.getFollowQuestion())
                .followAnswer(answer.getFollowAnswer())
                .followAnswerFile(signedUrlIfPresent(answer.getFollowAnswerVoice()))
                .build();
    }

    private String signedUrlIfPresent(String objectName) {
        return Optional.ofNullable(objectName)
                .filter(StringUtils::isNotBlank)
                .map(ossManager::getSignedUrl)
                .orElse(null);
    }
}
