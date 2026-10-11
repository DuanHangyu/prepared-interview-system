package system.assessment.defense.application.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.dto.*;
import system.assessment.defense.application.dto.backed.RecordRetakeCmd;
import system.assessment.defense.application.dto.backed.StudentRecordChangeScoreCmd;
import system.assessment.defense.application.dto.backed.StudentRecordExcelDTO;
import system.assessment.defense.application.dto.backed.StudentRecordExcelQuery;
import system.assessment.defense.domain.entity.TeacherSummary;
import system.assessment.defense.infrastructure.common.ExcelUtils;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentQuestionAnswerPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;
import system.assessment.defense.prepared.processing.PreparedRecordBridge;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @USER taoHouChao
 * @DATE 14:09 2025/8/17
 */
@Service
@RequiredArgsConstructor
public class StudyRecordService {

    private static final int MAX_EXPORT_RECORDS = 1_000;

    private final StudentAssessmentRecordService studentAssessmentService;

    private final StudentAssessmentQuestionAnswerService questionAnswerService;

    private final UserService userService;

    private final AssessmentSettingService assessmentSettingService;

    private final ResourceAccessControlService accessControlService;

    private final PreparedRecordBridge preparedRecords;

    private static final String PREPARED_RECORD_EXISTS = "EXISTS (SELECT 1 FROM prepared_attempt pr WHERE pr.record_id = student_assessment_record.id)";

    /**
     * 分页查询学习记录列表
     * <p>
     * 根据学生姓名和考核主题进行模糊查询，支持分页。如果提供了学生姓名或考核主题，
     * 将先查询匹配的学生ID列表或考核设置ID列表，再根据这些ID查询学习记录。
     * 最终将查询结果转换为 {@link StudyRecordDTO} 并返回分页结果。
     *
     * @param teacherId 当前已认证的教师或管理员 ID
     * @param pagingQuery 分页查询参数对象，包含页码、页面大小、学生姓名和考核主题等查询条件
     * @return 返回分页的学习记录数据传输对象（{@link StudyRecordDTO}）列表
     */
    @Transactional(readOnly = true)
    public IPage<StudyRecordDTO> recordList(Integer teacherId, StudyRecordPagingQuery pagingQuery) {
        preparedRecords.requireManagerIdentity(teacherId);
        TeacherSummary teacherSummary = userService.findTeacherSummaryById(teacherId)
                .orElseThrow(() -> new BusinessException(ErrorCodeEnums.USER_NOT_FOUND));
        List<Integer> studentIds = teacherSummary.getStudentIds();
        List<Integer> managedAssessmentIds = teacherSummary.getManagedAssessmentIds();
        if (CollectionUtils.isEmpty(studentIds) || CollectionUtils.isEmpty(managedAssessmentIds)) {
            return new Page<>(pagingQuery.getPage(), pagingQuery.getSize());
        }
        String studentName = pagingQuery.getStudentName();
        String theme = pagingQuery.getTheme();
        List<Integer> searchStudentIds = null;
        List<Integer> assessmentIds = null;

        // 如果提供了学生姓名，则根据姓名模糊查询用户ID列表
        if (StringUtils.isNotBlank(studentName)) {
            List<UserPO> users = userService.list(Wrappers.lambdaQuery(UserPO.class)
                    .select(UserPO::getId)
                    .eq(UserPO::getType, 0)
                    .in(UserPO::getId, studentIds)
                    .like(UserPO::getName, studentName));
            if (CollectionUtils.isEmpty(users)) {
                // 如果没有匹配的用户，返回空分页结果
                return new Page<>(pagingQuery.getPage(), pagingQuery.getSize());
            }
            searchStudentIds = users.stream().map(UserPO::getId).toList();
        }

        // 如果提供了考核主题，则根据主题模糊查询考核设置ID列表
        if (StringUtils.isNotBlank(theme)) {
            List<AssessmentSettingPO> assessments = assessmentSettingService.list(Wrappers.lambdaQuery(AssessmentSettingPO.class)
                    .select(AssessmentSettingPO::getId)
                    .in(AssessmentSettingPO::getId, managedAssessmentIds)
                    .like(AssessmentSettingPO::getTheme, theme));
            if (CollectionUtils.isEmpty(assessments)) {
                // 如果没有匹配的考核设置，返回空分页结果
                return new Page<>(pagingQuery.getPage(), pagingQuery.getSize());
            }
            assessmentIds = assessments.stream().map(AssessmentSettingPO::getId).toList();
        }

        // 根据学生ID和考核设置ID分页查询学习记录
        IPage<StudentAssessmentRecordPO> poPage = studentAssessmentService.page(new Page<>(pagingQuery.getPage(), pagingQuery.getSize()),
                Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                        .in(StudentAssessmentRecordPO::getStudentId, studentIds)
                        .in(StudentAssessmentRecordPO::getAssessmentId, managedAssessmentIds)
                        .and(w -> w.eq(StudentAssessmentRecordPO::getState, 1).or().apply(PREPARED_RECORD_EXISTS))
                        .in(CollectionUtils.isNotEmpty(searchStudentIds), StudentAssessmentRecordPO::getStudentId, searchStudentIds)
                        .in(CollectionUtils.isNotEmpty(assessmentIds), StudentAssessmentRecordPO::getAssessmentId, assessmentIds)
                        .orderByDesc(StudentAssessmentRecordPO::getUpdateTime));

        List<StudentAssessmentRecordPO> records = poPage.getRecords();

        // 如果查询到记录，则填充DTO对象并返回
        if (CollectionUtils.isNotEmpty(records)) {
            Map<Integer, PreparedRecordBridge.Info> modes = preparedRecords.managerRecords(teacherId,
                    records.stream().map(StudentAssessmentRecordPO::getId).toList());
            // 提取所有涉及的考核ID和学生ID，用于批量查询详细信息
            List<Integer> assessmentIdList = records.stream().map(StudentAssessmentRecordPO::getAssessmentId).distinct().toList();
            List<Integer> studentIdList = records.stream().map(StudentAssessmentRecordPO::getStudentId).distinct().toList();

            // 批量查询考核设置和用户信息，并构建映射表
            Map<Integer, AssessmentSettingPO> assessmentIdMap = assessmentSettingService.listByIds(assessmentIdList).stream()
                    .collect(Collectors.toMap(AssessmentSettingPO::getId, item -> item));
            Map<Integer, UserPO> userIdMap = userService.listByIds(studentIdList).stream()
                    .collect(Collectors.toMap(UserPO::getId, item -> item));

            // 将PO对象转换为DTO对象
            return poPage.convert(item -> {
                AssessmentSettingPO setting = assessmentIdMap.get(item.getAssessmentId());
                UserPO user = userIdMap.get(item.getStudentId());
                PreparedRecordBridge.Info mode = modes.get(item.getId());
                if (mode == null || mode.assessmentId() != item.getAssessmentId()) {
                    throw new BusinessException(409, "原记录归属已变化，请刷新后重试");
                }
                return StudyRecordDTO.builder()
                        .id(item.getId())
                        .assessmentId(mode.assessmentId())
                        .prepared(mode.prepared())
                        .preparedState(mode.state())
                        .studentName(user.getName())
                        .theme(setting.getTheme())
                        .score(mode.prepared() ? null : clampScore(item.getScore(), setting.getTotalScore()))
                        .checkScore(mode.prepared() ? null : clampNullableScore(item.getCheckScore(), setting.getTotalScore()))
                        .totalScore(setting.getTotalScore())
                        .assessmentTime(item.getCreateTime())
                        .state(item.getState())
                        .build();
            });
        }

        // 如果没有记录，返回空分页结果
        return new Page<>(pagingQuery.getPage(), pagingQuery.getSize());
    }

    public Boolean changeScore(Integer teacherId, StudentRecordChangeScoreCmd changeScoreCmd) {
        StudentAssessmentRecordPO record = requireRecord(changeScoreCmd.getId());
        accessControlService.requireAssessmentManager(teacherId, record.getAssessmentId());
        Integer checkedScore;
        try {
            checkedScore = changeScoreCmd.getCheckScore() == null
                    ? null
                    : changeScoreCmd.getCheckScore().intValueExact();
        } catch (ArithmeticException e) {
            throw new BusinessException(ErrorCodeEnums.INVALID_SCORE);
        }
        AssessmentSettingPO setting = assessmentSettingService.getById(record.getAssessmentId());
        if (setting == null) {
            throw new BusinessException(ErrorCodeEnums.ASSESSMENT_SETTING_NOT_FOUND);
        }
        int totalScore = setting.getTotalScore() == null ? 0 : setting.getTotalScore();
        if (checkedScore != null && (checkedScore < 0 || checkedScore > totalScore)) {
            throw new BusinessException(ErrorCodeEnums.INVALID_SCORE);
        }
        record.setCheckScore(checkedScore);
        return studentAssessmentService.updateById(record);
    }

    @Transactional(rollbackFor = Exception.class)
    public void exportRecordExcel(Integer teacherId, StudentRecordExcelQuery excelQuery) throws IOException {
        preparedRecords.requireManagerIdentity(teacherId);
        List<Integer> recordIds = excelQuery.getRecordIds();
        String theme = excelQuery.getTheme();
        if (StringUtils.isNotBlank(theme)) {
            TeacherSummary teacherSummary = userService.findTeacherSummaryById(teacherId)
                    .orElseThrow(() -> new BusinessException(ErrorCodeEnums.USER_NOT_FOUND));
            Set<Integer> managedAssessmentIds = new HashSet<>(teacherSummary.getManagedAssessmentIds());
            if (managedAssessmentIds.isEmpty()) {
                return;
            }
            List<AssessmentSettingPO> settings = assessmentSettingService.list(Wrappers.lambdaQuery(AssessmentSettingPO.class)
                            .like(AssessmentSettingPO::getTheme, theme)
                            .in(AssessmentSettingPO::getId, managedAssessmentIds))
                    .stream()
                    .filter(setting -> managedAssessmentIds.contains(setting.getId()))
                    .toList();
            if (CollectionUtils.isEmpty(settings)) {
                return;
            }
            List<Integer> assessmentIds = settings.stream()
                    .map(AssessmentSettingPO::getId)
                    .toList();
            List<StudentAssessmentRecordPO> records = studentAssessmentService.list(Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                    .in(StudentAssessmentRecordPO::getAssessmentId, assessmentIds)
                    .and(w -> w.eq(StudentAssessmentRecordPO::getState, 1).or().apply(PREPARED_RECORD_EXISTS))
                    .orderByDesc(StudentAssessmentRecordPO::getId)
                    .last("LIMIT " + (MAX_EXPORT_RECORDS + 1)));
            records = records.stream()
                    .filter(record -> managedAssessmentIds.contains(record.getAssessmentId()))
                    .toList();
            requireExportSize(records.size());
            requireRecordManagers(teacherId, records);
            preparedRecords.requireLegacyExport(teacherId, records.stream().map(StudentAssessmentRecordPO::getId).toList());
            exportRecords(records, settings);
        }else {
            if (CollectionUtils.isEmpty(recordIds)) {
                throw new IllegalArgumentException("请提供考核主题或记录ID");
            }
            requireExportSize(recordIds.size());
            preparedRecords.requireLegacyExport(teacherId, recordIds);
            List<StudentAssessmentRecordPO> records = studentAssessmentService.list(Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                    .in(StudentAssessmentRecordPO::getId, recordIds)
                    .eq(StudentAssessmentRecordPO::getState, 1)
                    .orderByDesc(StudentAssessmentRecordPO::getId));
            requireRecordManagers(teacherId, records);
            List<Integer> assessmentIds = records.stream()
                    .map(StudentAssessmentRecordPO::getAssessmentId)
                    .distinct()
                    .toList();
            List<AssessmentSettingPO> settings = assessmentSettingService.listByIds(assessmentIds);
            exportRecords(records, settings);
        }
    }

    private void requireExportSize(int size) {
        if (size > MAX_EXPORT_RECORDS) {
            throw new BusinessException(400, "单次最多导出1000条考核记录，请缩小范围后重试");
        }
    }

    private void exportRecords(List<StudentAssessmentRecordPO> records,
                               List<AssessmentSettingPO> settings) throws IOException {
        List<Integer> studentIds = records.stream()
                .map(StudentAssessmentRecordPO::getStudentId)
                .distinct()
                .toList();
        List<Integer> recordAssessmentIds = records.stream()
                .map(StudentAssessmentRecordPO::getAssessmentId)
                .distinct()
                .toList();
        List<StudentAssessmentQuestionAnswerPO> questionAnswers = questionAnswerService.list(Wrappers.lambdaQuery(StudentAssessmentQuestionAnswerPO.class)
                .in(StudentAssessmentQuestionAnswerPO::getAssessmentId, recordAssessmentIds)
                .in(StudentAssessmentQuestionAnswerPO::getStudentId, studentIds));
        Map<Integer, Map<Integer, List<StudentAssessmentQuestionAnswerPO>>> assessmentStudentIdMap = questionAnswers.stream()
                .collect(Collectors.groupingBy(StudentAssessmentQuestionAnswerPO::getAssessmentId, Collectors.groupingBy(StudentAssessmentQuestionAnswerPO::getStudentId)));
        // 批量查询用户信息
        Map<Integer, UserPO> studentIdMap = userService.listByIds(studentIds)
                .stream()
                .collect(Collectors.toMap(UserPO::getId, item -> item));

        Map<Integer, AssessmentSettingPO> assessmentIdMap = settings.stream()
                .collect(Collectors.toMap(AssessmentSettingPO::getId, item -> item));

        List<StudentRecordExcelDTO> recordExcels = records.stream()
                .map(record -> {
                    AssessmentSettingPO assessmentSettingPO = assessmentIdMap.getOrDefault(record.getAssessmentId(), new AssessmentSettingPO());
                    UserPO user = studentIdMap.getOrDefault(record.getStudentId(), UserPO.EMPTY_USER);
                    StudentRecordExcelDTO excelDTO = new StudentRecordExcelDTO();
                    excelDTO.setStudentName(user.getName());
                    excelDTO.setIdNumber(user.getAccount());
                    excelDTO.setTheme(assessmentSettingPO.getTheme());
                    excelDTO.setTotalScore(assessmentSettingPO.getTotalScore());
                    excelDTO.setPassScore(assessmentSettingPO.getPassScore());
                    excelDTO.setScore(clampScore(record.getScore(), assessmentSettingPO.getTotalScore()));
                    excelDTO.setCheckScore(clampNullableScore(record.getCheckScore(), assessmentSettingPO.getTotalScore()));
                    excelDTO.setDefenseAnswer(record.getDefenseContent());
                    List<StudentAssessmentQuestionAnswerPO> matchQuestionAnswers = assessmentStudentIdMap.getOrDefault(record.getAssessmentId(), new HashMap<>()).getOrDefault(record.getStudentId(), Collections.emptyList());
                    StringBuilder questionAnswerBuilder = new StringBuilder();
                    for (StudentAssessmentQuestionAnswerPO questionAnswer : matchQuestionAnswers) {
                        questionAnswerBuilder
                                .append("问题：").append(questionAnswer.getQuestion())
                                .append("\n")
                                .append("回答：").append(questionAnswer.getAnswer())
                                .append("\n");
                        if (StringUtils.isNotBlank(questionAnswer.getFollowQuestion())) {
                            questionAnswerBuilder
                                    .append("追问问题：").append(questionAnswer.getFollowQuestion())
                                    .append("\n")
                                    .append("追问回答：").append(questionAnswer.getFollowAnswer())
                                    .append("\n");
                        }
                        questionAnswerBuilder.append("\n");
                    }
                    excelDTO.setQuestionAnswer(questionAnswerBuilder.toString());
                    AssessmentEvaluationDTO evaluationDTO =
                            StoredAssessmentEvaluationParser.parse(record.getDefenseResult());
                    AnalysisBO analysis = evaluationDTO.getAnalysis();
                    List<AnalysisDTO> analysisList = analysis.getAnalysis();
                    StringBuilder analysisBuilder = new StringBuilder();
                    for (AnalysisDTO analysisDTO : analysisList) {
                        analysisBuilder
                                .append("考核指标：").append(analysisDTO.getName())
                                .append("\n")
                                .append("得分：").append(analysisDTO.getScore())
                                .append("\n")
                                .append("评价：").append(analysisDTO.getDescription())
                                .append("\n")
                                .append("\n");
                    }
                    excelDTO.setAnalysis(analysisBuilder.toString());
                    excelDTO.setSuggestions(String.join("\n", analysis.getSuggestions()));
                    excelDTO.setStrengths(String.join("\n", analysis.getStrengths()));
                    excelDTO.setWeaknesses(String.join("\n", analysis.getWeaknesses()));
                    excelDTO.setAssessTime(record.getUpdateTime());
                    return excelDTO;
                }).toList();
        ExcelUtils.exportTemplate(recordExcels, "学生记录");
    }

    private static int clampScore(Integer score, Integer totalScore) {
        int maximum = totalScore == null ? 0 : Math.max(0, totalScore);
        return Math.max(0, Math.min(maximum, score == null ? 0 : score));
    }

    private static Integer clampNullableScore(Integer score, Integer totalScore) {
        return score == null ? null : clampScore(score, totalScore);
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean retake(Integer teacherId, RecordRetakeCmd retakeCmd) {
        Integer recordId = retakeCmd.getRecordId();
        StudentAssessmentRecordPO record = requireRecord(recordId);
        accessControlService.requireAssessmentManager(teacherId, record.getAssessmentId());
        return studentAssessmentService.update(Wrappers.lambdaUpdate(StudentAssessmentRecordPO.class)
                .set(StudentAssessmentRecordPO::getState, 3)
                .eq(StudentAssessmentRecordPO::getId, recordId));
    }

    private StudentAssessmentRecordPO requireRecord(Integer recordId) {
        StudentAssessmentRecordPO record = studentAssessmentService.getById(recordId);
        if (record == null) {
            throw new BusinessException(ErrorCodeEnums.RECORD_NOT_FOUND);
        }
        return record;
    }

    private void requireRecordManagers(Integer teacherId, List<StudentAssessmentRecordPO> records) {
        records.stream()
                .map(StudentAssessmentRecordPO::getAssessmentId)
                .distinct()
                .forEach(assessmentId -> accessControlService.requireAssessmentManager(teacherId, assessmentId));
    }
}
