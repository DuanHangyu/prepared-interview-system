package system.assessment.defense.application.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.dto.*;
import system.assessment.defense.domain.entity.TeacherSummary;
import system.assessment.defense.domain.event.StudentCreateEvent;
import system.assessment.defense.infrastructure.common.FileByteDTO;
import system.assessment.defense.infrastructure.common.HttpUtils;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.emuns.UserRoleEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.*;
import system.assessment.defense.infrastructure.repository.dao.service.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @USER taoHouChao
 * @DATE 21:09 2025/8/7
 */
@Service
@RequiredArgsConstructor
public class StudentAppService {

    private static final int MAX_STUDENT_IMPORT_ROWS = 1_000;
    private static final int MAX_IMPORTED_RELATIONS = 100_000;
    private static final int MAX_USER_TEXT_LENGTH = 255;
    private static final int MAX_EXPORT_ROWS = 1_000;
    private static final int MAX_SCHOOL_CLASS_OPTIONS = 1_000;
    private static final int MAX_STUDENTS_PER_CLASS = 1_000;

    private final UserService userService;

    private final StudentAssessmentAppointmentService appointmentService;

    private final AssessmentStudentRelationService relationService;

    private final AssessmentSchoolClassService schoolClassService;

    private final StudentAssessmentRecordService recordService;


    private final HttpUtils httpUtils;

    private final StudentAssessmentQuestionAnswerService questionAnswerService;

    private final ApplicationEventPublisher publisher;

    private final PasswordSecurityService passwordSecurityService;

    private final ManagedObjectCleanupService objectCleanupService;

    public IPage<StudentDTO> pageList(StudentPageQuery query) {
        int teacherId = StpUtil.getLoginIdAsInt();
        TeacherSummary teacherSummary = userService.findTeacherSummaryById(teacherId).orElseThrow(() -> new BusinessException(ErrorCodeEnums.USER_NOT_FOUND));
        List<Integer> studentIds = teacherSummary.getStudentIds();
        if (CollectionUtils.isEmpty(studentIds)) {
            return new Page<>();
        }
        IPage<UserPO> page = userService.page(new Page<>(query.getPage(), query.getSize()),
                Wrappers.lambdaQuery(UserPO.class)
                        .in(UserPO::getId, studentIds)
                        .like(StringUtils.isNotBlank(query.getName()), UserPO::getName, query.getName())
                        .like(StringUtils.isNotBlank(query.getPhone()), UserPO::getPhone, query.getPhone())
                        .like(StringUtils.isNotBlank(query.getAccount()), UserPO::getAccount, query.getAccount())
                        .like(StringUtils.isNotBlank(query.getSchoolClass()), UserPO::getSchoolClass, query.getSchoolClass()));
        return page.convert(studentPO -> BeanUtil.copyProperties(studentPO, StudentDTO.class));
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean createStudent(StudentCreateCmd createCmd) {
        UserPO userPO = createCmd.toPO();
        userPO.setPassword(passwordSecurityService.encode(userPO.getPassword()));
        boolean save = userService.save(userPO);
        if (!save) {
            return false;
        }
        saveRelations(List.of(userPO));
        publisher.publishEvent(new StudentCreateEvent(this, userPO));
        return true;
    }

    private void saveRelations(List<UserPO> students) {
        Set<String> schoolClasses = students.stream()
                .map(UserPO::getSchoolClass)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (schoolClasses.isEmpty()) {
            return;
        }
        List<AssessmentSchoolClassPO> classAssignments = schoolClassService.list(
                Wrappers.lambdaQuery(AssessmentSchoolClassPO.class)
                        .in(AssessmentSchoolClassPO::getSchoolClass, schoolClasses));
        Map<String, Set<Integer>> assessmentIdsByClass = new HashMap<>();
        for (AssessmentSchoolClassPO assignment : classAssignments) {
            if (StringUtils.isNotBlank(assignment.getSchoolClass())
                    && assignment.getAssessmentId() != null) {
                assessmentIdsByClass.computeIfAbsent(assignment.getSchoolClass(), ignored -> new LinkedHashSet<>())
                        .add(assignment.getAssessmentId());
            }
        }
        long relationCount = students.stream()
                .mapToLong(student -> assessmentIdsByClass
                        .getOrDefault(student.getSchoolClass(), Collections.emptySet()).size())
                .sum();
        if (relationCount > MAX_IMPORTED_RELATIONS) {
            throw new BusinessException(400, "导入学生匹配到的考核关系过多，请分批处理");
        }
        List<AssessmentStudentRelationPO> relations = new ArrayList<>((int) relationCount);
        for (UserPO student : students) {
            for (Integer assessmentId : assessmentIdsByClass
                    .getOrDefault(student.getSchoolClass(), Collections.emptySet())) {
                relations.add(AssessmentStudentRelationPO.builder()
                        .relationType(1)
                        .assessmentId(assessmentId)
                        .studentId(student.getId())
                        .build());
            }
        }
        if (CollectionUtils.isNotEmpty(relations)) {
            requireStudentRelationsSaved(relationService.saveBatch(relations));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean modifyStudent(StudentModifyCmd modifyCmd) {
        UserPO existingStudent = requireStudent(modifyCmd.getId());
        UserPO userPO = modifyCmd.toPO();
        userPO.setPassword(StringUtils.isBlank(modifyCmd.getPassword())
                ? null
                : passwordSecurityService.encode(modifyCmd.getPassword()));
        boolean updated = userService.updateById(userPO);
        if (updated && !Objects.equals(existingStudent.getSchoolClass(), userPO.getSchoolClass())) {
            reconcileClassRelations(userPO);
        }
        return updated;
    }

    private void reconcileClassRelations(UserPO student) {
        relationService.remove(Wrappers.lambdaQuery(AssessmentStudentRelationPO.class)
                .eq(AssessmentStudentRelationPO::getStudentId, student.getId())
                .eq(AssessmentStudentRelationPO::getRelationType, 1));
        List<AssessmentSchoolClassPO> classAssignments = schoolClassService.list(
                Wrappers.lambdaQuery(AssessmentSchoolClassPO.class)
                        .eq(AssessmentSchoolClassPO::getSchoolClass, student.getSchoolClass()));
        if (CollectionUtils.isEmpty(classAssignments)) {
            return;
        }
        Set<Integer> directAssessmentIds = relationService.list(
                        Wrappers.lambdaQuery(AssessmentStudentRelationPO.class)
                                .eq(AssessmentStudentRelationPO::getStudentId, student.getId())
                                .eq(AssessmentStudentRelationPO::getRelationType, 0))
                .stream()
                .map(AssessmentStudentRelationPO::getAssessmentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<AssessmentStudentRelationPO> newRelations = classAssignments.stream()
                .map(AssessmentSchoolClassPO::getAssessmentId)
                .filter(Objects::nonNull)
                .filter(assessmentId -> !directAssessmentIds.contains(assessmentId))
                .distinct()
                .map(assessmentId -> AssessmentStudentRelationPO.builder()
                        .assessmentId(assessmentId)
                        .studentId(student.getId())
                        .relationType(1)
                        .build())
                .toList();
        if (CollectionUtils.isNotEmpty(newRelations)) {
            requireStudentRelationsSaved(relationService.saveBatch(newRelations));
        }
    }

    private static void requireStudentRelationsSaved(boolean saved) {
        if (!saved) {
            throw new IllegalStateException("学生考核关系保存失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteStudent(StudentRemoveCmd removeCmd) {
        requireStudent(removeCmd.getId());
        List<StudentAssessmentRecordPO> records = recordService.list(
                Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                        .eq(StudentAssessmentRecordPO::getStudentId, removeCmd.getId()));
        List<StudentAssessmentQuestionAnswerPO> answers = questionAnswerService.list(
                Wrappers.lambdaQuery(StudentAssessmentQuestionAnswerPO.class)
                        .eq(StudentAssessmentQuestionAnswerPO::getStudentId, removeCmd.getId()));
        relationService.remove(Wrappers.lambdaQuery(AssessmentStudentRelationPO.class)
                .eq(AssessmentStudentRelationPO::getStudentId, removeCmd.getId()));
        recordService.remove(Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                .eq(StudentAssessmentRecordPO::getStudentId, removeCmd.getId()));
        questionAnswerService.remove(Wrappers.lambdaQuery(StudentAssessmentQuestionAnswerPO.class)
                .eq(StudentAssessmentQuestionAnswerPO::getStudentId, removeCmd.getId()));
        appointmentService.remove(Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                .eq(StudentAssessmentAppointmentPO::getStudentId, removeCmd.getId()));
        if (!userService.removeById(removeCmd.getId())) {
            throw new BusinessException(ErrorCodeEnums.STUDENT_NOT_EXIST);
        }
        objectCleanupService.deleteInterviewArtifacts(records, answers);
        return true;
    }

    private UserPO requireStudent(Integer studentId) {
        UserPO target = userService.getById(studentId);
        if (target == null || !Objects.equals(target.getType(), UserRoleEnums.STUDENT.getRole())) {
            throw new BusinessException(ErrorCodeEnums.STUDENT_NOT_EXIST);
        }
        return target;
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean importStudent(List<StudentExcelDTO> students) {
        checkImportStudent(students);
        List<String> userAccounts = students.stream().map(StudentExcelDTO::getAccount).toList();
        Set<String> existUsers = userService.list(Wrappers.lambdaQuery(UserPO.class)
                        .in(UserPO::getAccount, userAccounts)).stream()
                .map(UserPO::getAccount)
                .collect(Collectors.toSet());
        if (CollectionUtils.isNotEmpty(existUsers)) {
            throw new BusinessException(ErrorCodeEnums.EXIST_STUDENT);
        }
        List<UserPO> studentPos = students.stream()
                .map(item -> UserPO.builder()
                        .name(item.getName())
                        .account(item.getAccount())
                        .password(passwordSecurityService.encode(item.getPassword()))
                        .schoolClass(item.getSchoolClass())
                        .type(UserRoleEnums.STUDENT.getRole())
                        .build())
                .toList();
        if (!userService.saveBatch(studentPos)) {
            return false;
        }
        saveRelations(studentPos);
        return true;
    }

    private void checkImportStudent(List<StudentExcelDTO> students) {
        if (CollectionUtils.isEmpty(students)) {
            throw new BusinessException(400, "导入学生不能为空");
        }
        if (students.size() > MAX_STUDENT_IMPORT_ROWS) {
            throw new BusinessException(400, "单次最多导入1000名学生");
        }
        Set<String> accounts = new HashSet<>();
        for (StudentExcelDTO student : students) {
            if (student == null) {
                throw new BusinessException(400, "导入学生数据不能为空");
            }
            if (StrUtil.isBlank(student.getName())) {
                throw new BusinessException(ErrorCodeEnums.IMPORT_STUDENT_NAME_EMPTY);
            }
            if (student.getName().length() > MAX_USER_TEXT_LENGTH) {
                throw new BusinessException(400, "导入学生姓名不能超过255个字符");
            }
            if (StrUtil.isBlank(student.getAccount())) {
                throw new BusinessException(ErrorCodeEnums.IMPORT_STUDENT_ACCOUNT_EMPTY);
            }
            if (student.getAccount().length() > MAX_USER_TEXT_LENGTH) {
                throw new BusinessException(400, "导入学生账号不能超过255个字符");
            }
            if (StrUtil.isBlank(student.getPassword())) {
                throw new BusinessException(ErrorCodeEnums.IMPORT_STUDENT_PASSWORD_EMPTY);
            }
            if (student.getPassword().length() < 8) {
                throw new BusinessException(400, "导入学生密码至少需要8个字符");
            }
            if (StrUtil.isBlank(student.getSchoolClass())) {
                throw new BusinessException(400, "导入学生班级不能为空");
            }
            if (student.getSchoolClass().length() > MAX_USER_TEXT_LENGTH) {
                throw new BusinessException(400, "导入学生班级不能超过255个字符");
            }
            if (!accounts.add(student.getAccount())) {
                throw new BusinessException(ErrorCodeEnums.EXIST_STUDENT);
            }
        }
    }

    public List<StudentExportExcelDTO> exportStudent(StudentExportCmd exportCmd) {
        if (Objects.equals(Boolean.TRUE, exportCmd.getAll())) {
            List<UserPO> students = userService.list(Wrappers.lambdaQuery(UserPO.class)
                    .eq(UserPO::getType, 0)
                    .last("LIMIT " + (MAX_EXPORT_ROWS + 1)));
            requireExportSize(students.size());
            return students.stream()
                    .map(item -> StudentExportExcelDTO.builder()
                            .name(item.getName())
                            .account(item.getAccount())
                            .schoolClass(item.getSchoolClass())
                            .build())
                    .toList();
        }
        requireExportSize(exportCmd.getIds() == null ? 0 : exportCmd.getIds().size());
        return userService.listByIds(exportCmd.getIds()).stream()
                .filter(item -> Objects.equals(item.getType(), UserRoleEnums.STUDENT.getRole()))
                .map(item -> StudentExportExcelDTO.builder()
                        .name(item.getName())
                        .account(item.getAccount())
                        .schoolClass(item.getSchoolClass())
                        .build())
                .toList();
    }

    private void requireExportSize(int size) {
        if (size > MAX_EXPORT_ROWS) {
            throw new BusinessException(400, "单次最多导出1000名学生，请缩小范围后重试");
        }
    }

    private String getMimeType(String fileName) {
        String extendName = fileName.substring(fileName.lastIndexOf(".") + 1);
        return switch (extendName) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "pdf" -> "application/pdf";
            case "doc", "docx" -> "application/msword";
            case "xls", "xlsx" -> "application/vnd.ms-excel";
            case "tex" -> "application/x-tex";
            default -> "application/octet-stream";
        };
    }

    public List<String> allSchoolClassOptions() {
        List<String> schoolClasses = userService.list(Wrappers.lambdaQuery(UserPO.class)
                        .select(UserPO::getSchoolClass)
                        .eq(UserPO::getType, UserRoleEnums.STUDENT.getRole())
                        .isNotNull(UserPO::getSchoolClass)
                        .groupBy(UserPO::getSchoolClass)
                        .orderByAsc(UserPO::getSchoolClass)
                        .last("LIMIT " + (MAX_SCHOOL_CLASS_OPTIONS + 1)))
                .stream()
                .map(UserPO::getSchoolClass)
                .filter(StringUtils::isNotBlank)
                .toList();
        if (schoolClasses.size() > MAX_SCHOOL_CLASS_OPTIONS) {
            throw new BusinessException(400, "班级数量超过1000个，请先整理班级数据");
        }
        return schoolClasses;
    }

    public List<StudentOptionDTO> studentOptionsBySchoolClass(String schoolClass) {
        List<UserPO> students = userService.list(Wrappers.lambdaQuery(UserPO.class)
                .select(UserPO::getId, UserPO::getName, UserPO::getSchoolClass)
                .eq(UserPO::getType, UserRoleEnums.STUDENT.getRole())
                .eq(UserPO::getSchoolClass, schoolClass)
                .orderByAsc(UserPO::getId)
                .last("LIMIT " + (MAX_STUDENTS_PER_CLASS + 1)));
        if (students.size() > MAX_STUDENTS_PER_CLASS) {
            throw new BusinessException(400, "单个班级超过1000名学生，无法一次选择");
        }
        return students.stream()
                .map(studentPO -> BeanUtil.copyProperties(studentPO, StudentOptionDTO.class))
                .toList();
    }

}
