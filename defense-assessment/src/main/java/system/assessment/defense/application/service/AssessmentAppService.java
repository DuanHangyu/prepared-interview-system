package system.assessment.defense.application.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.dto.*;
import system.assessment.defense.application.dto.backed.*;
import system.assessment.defense.domain.event.SettingCreateOrUpdateEvent;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.emuns.UserRoleEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.*;
import system.assessment.defense.infrastructure.repository.dao.mapper.AssessmentSettingMapper;
import system.assessment.defense.infrastructure.repository.dao.service.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @USER taoHouChao
 * @DATE 20:20 2025/8/7
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssessmentAppService {

    private static final Object OBJECT = new Object();
    private static final int MAX_ASSESSMENT_PARTICIPANTS = 10_000;
    private static final int MAX_STUDENTS_PER_CLASS = 1_000;

    private final UserService userService;

    private final AssessmentSettingService assessmentSettingService;

    private final AssessmentSettingMapper assessmentSettingMapper;

    private final StudentAssessmentRecordService recordService;

    private final StudentAssessmentQuestionAnswerService questionAnswerService;

    private final AssessmentStudentRelationService assessmentStudentRelationService;

    private final AssessmentSchoolClassService assessmentSchoolClassService;

    private final AssessmentInviteService assessmentInviteService;

    private final AssessmentAppointmentSettingService appointmentSettingService;

    private final StudentAssessmentAppointmentService studentAssessmentAppointmentService;

    private final ApplicationEventPublisher publisher;

    private final ResourceAccessControlService accessControlService;

    private final ManagedObjectCleanupService objectCleanupService;

    private final FileAccessControlService fileAccessControlService;

    @Transactional(rollbackFor = Exception.class)
    public Boolean createAssessmentSettings(AssessmentSettingsCreateCmd createCmd) {
        int teacherId = StpUtil.getLoginIdAsInt();
        createCmd.check();
        requireOwnedMaterials(teacherId, createCmd.getAssessmentFiles());
        List<SchoolClassStudentSaveCmd> schoolClassList = validateAndResolveRelatedUsers(
                teacherId,
                createCmd.getInviteTeachers(),
                createCmd.getStudentIds(),
                createCmd.getSchoolClassList());
        AssessmentSettingPO assessmentSettingPo = createCmd.toPO();
        assessmentSettingPo.setCreateId(teacherId);
        if (!assessmentSettingService.save(assessmentSettingPo)) {
            throw new IllegalStateException("Failed to create assessment settings");
        }

        List<Integer> inviteTeachers = createCmd.getInviteTeachers();
        List<Integer> studentIds = createCmd.getStudentIds();
        saveRelationStudent(inviteTeachers, assessmentSettingPo.getId(), teacherId, studentIds, schoolClassList);
        publisher.publishEvent(new SettingCreateOrUpdateEvent(OBJECT, assessmentSettingPo));
        return true;
    }

    private void saveRelationStudent(List<Integer> inviteTeachers,
                                     Integer settingId,
                                     Integer teacherId,
                                     List<Integer> studentIds,
                                     List<SchoolClassStudentSaveCmd> schoolClassList) {
        if (CollectionUtils.isNotEmpty(inviteTeachers)) {
            List<AssessmentInvitePO> list = inviteTeachers.stream()
                    .map(item -> AssessmentInvitePO.builder()
                            .assessmentId(settingId)
                            .inviteeTeacherId(item)
                            .InviterTeacherId(teacherId)
                            .build())
                    .toList();
            requirePersistence(assessmentInviteService.saveBatch(list), "邀请教师关系保存失败");
        }

        List<AssessmentStudentRelationPO> relations = new ArrayList<>(100);
        if (CollectionUtils.isNotEmpty(studentIds)) {
            List<AssessmentStudentRelationPO> relationList = studentIds.stream()
                    .map(item -> AssessmentStudentRelationPO.builder()
                            .assessmentId(settingId)
                            .studentId(item)
                            .relationType(0)
                            .build())
                    .toList();
            relations.addAll(relationList);
        }

        if (CollectionUtils.isNotEmpty(schoolClassList)) {
            List<AssessmentSchoolClassPO> classAssignments = schoolClassList.stream()
                    .map(item -> AssessmentSchoolClassPO.builder()
                            .assessmentId(settingId)
                            .schoolClass(item.getSchoolClass())
                            .build())
                    .toList();
            requirePersistence(
                    assessmentSchoolClassService.saveBatch(classAssignments), "考核班级关系保存失败");
            for (SchoolClassStudentSaveCmd schoolClassStudentDTO : schoolClassList) {
                List<Integer> students = schoolClassStudentDTO.getStudents();
                relations.addAll(students.stream()
                        .map(item -> AssessmentStudentRelationPO.builder()
                                .assessmentId(settingId)
                                .studentId(item)
                                .relationType(1)
                                .build())
                        .toList());
            }
        }
        if (CollectionUtils.isNotEmpty(relations)) {
            requirePersistence(
                    assessmentStudentRelationService.saveBatch(relations), "考核学生关系保存失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean modifyAssessmentSettings(Integer teacherId, AssessmentSettingsModifyCmd modifyCmd) {
        modifyCmd.check();
        Integer id = modifyCmd.getId();
        accessControlService.requireAssessmentManager(teacherId, id);
        AssessmentSettingPO assessmentSetting = assessmentSettingService.getById(id);
        if (assessmentSetting == null) {
            throw new BusinessException(ErrorCodeEnums.ASSESSMENT_SETTING_NOT_FOUND);
        }
        requireAssessmentMaterials(teacherId, id, modifyCmd.getAssessmentFiles());
        List<SchoolClassStudentSaveCmd> schoolClassList = validateAndResolveRelatedUsers(
                teacherId,
                modifyCmd.getInviteTeachers(),
                modifyCmd.getStudentIds(),
                modifyCmd.getSchoolClassList());
        AssessmentSettingPO participatingStudents = modifyCmd.toPO();
        List<String> previousMaterialReferences =
                objectCleanupService.extractFileReferences(assessmentSetting.getAssessmentFiles());
        assessmentStudentRelationService.remove(Wrappers.lambdaQuery(AssessmentStudentRelationPO.class)
                .eq(AssessmentStudentRelationPO::getAssessmentId, assessmentSetting.getId()));
        assessmentSchoolClassService.remove(Wrappers.lambdaQuery(AssessmentSchoolClassPO.class)
                .eq(AssessmentSchoolClassPO::getAssessmentId, assessmentSetting.getId()));
        assessmentInviteService.remove(Wrappers.lambdaQuery(AssessmentInvitePO.class)
                .eq(AssessmentInvitePO::getAssessmentId, assessmentSetting.getId()));
        saveRelationStudent(modifyCmd.getInviteTeachers(), modifyCmd.getId(), teacherId,
                modifyCmd.getStudentIds(), schoolClassList);
        // 如果之前是需要预约的，改成了非预约的，则需要把预约设置和学生的预约信息都删除
        if (Objects.equals(assessmentSetting.getNeedAppoint(), true) && Objects.equals(modifyCmd.getNeedAppoint(), false)) {
            appointmentSettingService.remove(Wrappers.lambdaQuery(AssessmentAppointmentSettingPO.class)
                    .eq(AssessmentAppointmentSettingPO::getAssessmentId, assessmentSetting.getId()));
            studentAssessmentAppointmentService.remove(Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                    .eq(StudentAssessmentAppointmentPO::getAssessmentId, assessmentSetting.getId()));
        }
        publisher.publishEvent(new SettingCreateOrUpdateEvent(OBJECT, participatingStudents));
        if (!assessmentSettingService.updateById(participatingStudents)) {
            throw new IllegalStateException("Failed to update assessment settings");
        }
        objectCleanupService.queueRemovedReferences(
                previousMaterialReferences,
                objectCleanupService.extractFileReferences(participatingStudents.getAssessmentFiles()));
        return true;
    }

    private List<SchoolClassStudentSaveCmd> validateAndResolveRelatedUsers(
            Integer teacherId,
            List<Integer> inviteTeachers,
            List<Integer> directStudents,
            List<SchoolClassStudentSaveCmd> requestedSchoolClasses) {
        Set<Integer> invitedTeacherIds = new LinkedHashSet<>();
        if (CollectionUtils.isNotEmpty(inviteTeachers)) {
            for (Integer inviteTeacherId : inviteTeachers) {
                addUniqueUserReference(invitedTeacherIds, inviteTeacherId);
            }
        }
        if (invitedTeacherIds.contains(teacherId)) {
            invalidAssessmentParticipant();
        }

        Set<Integer> studentIds = new LinkedHashSet<>();
        Set<String> schoolClasses = new LinkedHashSet<>();
        if (CollectionUtils.isNotEmpty(directStudents)) {
            for (Integer studentId : directStudents) {
                addUniqueUserReference(studentIds, studentId);
            }
        }
        if (CollectionUtils.isNotEmpty(requestedSchoolClasses)) {
            for (SchoolClassStudentSaveCmd schoolClass : requestedSchoolClasses) {
                if (schoolClass == null
                        || StrUtil.isBlank(schoolClass.getSchoolClass())
                        || !schoolClasses.add(schoolClass.getSchoolClass())) {
                    invalidAssessmentParticipant();
                }
            }
        }

        List<SchoolClassStudentSaveCmd> resolvedSchoolClasses =
                resolveSchoolClassStudents(schoolClasses, studentIds);
        if (invitedTeacherIds.size() + studentIds.size() > MAX_ASSESSMENT_PARTICIPANTS) {
            invalidAssessmentParticipant();
        }

        Set<Integer> explicitUserIds = new LinkedHashSet<>(invitedTeacherIds);
        if (CollectionUtils.isNotEmpty(directStudents)) {
            explicitUserIds.addAll(directStudents);
        }
        if (explicitUserIds.isEmpty()) {
            return resolvedSchoolClasses;
        }

        Map<Integer, UserPO> usersById = userService.listByIds(explicitUserIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(UserPO::getId, user -> user, (first, ignored) -> first));
        boolean validTeachers = invitedTeacherIds.stream()
                .allMatch(userId -> hasRole(usersById.get(userId), UserRoleEnums.TEACHER));
        boolean validStudents = directStudents == null || directStudents.stream()
                .allMatch(userId -> hasRole(usersById.get(userId), UserRoleEnums.STUDENT));
        if (!validTeachers || !validStudents) {
            invalidAssessmentParticipant();
        }
        return resolvedSchoolClasses;
    }

    private List<SchoolClassStudentSaveCmd> resolveSchoolClassStudents(
            Set<String> schoolClasses,
            Set<Integer> selectedStudentIds) {
        if (schoolClasses.isEmpty()) {
            return List.of();
        }
        List<UserPO> classStudents = userService.list(Wrappers.lambdaQuery(UserPO.class)
                .select(UserPO::getId, UserPO::getSchoolClass)
                .eq(UserPO::getType, UserRoleEnums.STUDENT.getRole())
                .in(UserPO::getSchoolClass, schoolClasses)
                .orderByAsc(UserPO::getId)
                .last("LIMIT " + (MAX_ASSESSMENT_PARTICIPANTS + 1)));
        if (classStudents.size() > MAX_ASSESSMENT_PARTICIPANTS) {
            invalidAssessmentParticipant();
        }
        Map<String, List<Integer>> studentIdsByClass = classStudents.stream()
                .collect(Collectors.groupingBy(
                        UserPO::getSchoolClass,
                        LinkedHashMap::new,
                        Collectors.mapping(UserPO::getId, Collectors.toList())));
        List<SchoolClassStudentSaveCmd> resolved = new ArrayList<>(schoolClasses.size());
        for (String schoolClass : schoolClasses) {
            List<Integer> classStudentIds = studentIdsByClass.getOrDefault(schoolClass, List.of());
            if (classStudentIds.isEmpty() || classStudentIds.size() > MAX_STUDENTS_PER_CLASS) {
                invalidAssessmentParticipant();
            }
            for (Integer studentId : classStudentIds) {
                addUniqueUserReference(selectedStudentIds, studentId);
            }
            SchoolClassStudentSaveCmd schoolClassStudents = new SchoolClassStudentSaveCmd();
            schoolClassStudents.setSchoolClass(schoolClass);
            schoolClassStudents.setStudents(classStudentIds);
            resolved.add(schoolClassStudents);
        }
        return resolved;
    }

    private void addUniqueUserReference(Set<Integer> userIds, Integer userId) {
        if (userId == null || userId <= 0 || !userIds.add(userId)) {
            invalidAssessmentParticipant();
        }
    }

    private boolean hasRole(UserPO user, UserRoleEnums role) {
        return user != null && Objects.equals(user.getType(), role.getRole());
    }

    private void invalidAssessmentParticipant() {
        throw new BusinessException(ErrorCodeEnums.INVALID_ASSESSMENT_PARTICIPANT);
    }

    private void requireOwnedMaterials(Integer teacherId, List<FileBindingCmd> files) {
        if (CollectionUtils.isEmpty(files)) {
            return;
        }
        for (FileBindingCmd file : files) {
            fileAccessControlService.requireOwnedUpload(
                    teacherId, file == null ? null : file.getFileUrl());
        }
    }

    private void requireAssessmentMaterials(Integer teacherId,
                                              Integer assessmentId,
                                              List<FileBindingCmd> files) {
        if (CollectionUtils.isEmpty(files)) {
            return;
        }
        for (FileBindingCmd file : files) {
            fileAccessControlService.requireAssessmentMaterialBinding(
                    teacherId, assessmentId, file == null ? null : file.getFileUrl());
        }
    }

    /**
     * 分页查询考核设置列表，并封装为 DTO 返回。
     * <p>
     * 该方法会根据分页参数和主题关键词查询考核设置信息，并同时查询每个考核设置关联的学生信息，
     * 最终将数据封装为 {@link AssessmentSettingsDTO} 并返回分页结果。
     *
     * @param pagingQuery 分页查询参数，包含页码、页大小和查询关键词等信息
     * @return 分页结果，包含考核设置信息及其参与学生列表
     */
    @Transactional(readOnly = true)
    public IPage<AssessmentSettingsDTO> list(Integer teacherId, AssessmentSettingPagingQuery pagingQuery) {
        UserPO user = userService.getById(teacherId);
        IPage<AssessmentSettingPO> poPage;
        // 管理员
        if (Objects.equals(user.getType(), 2)) {
            poPage = assessmentSettingService.page(new Page<>(pagingQuery.getPage(), pagingQuery.getSize()),
                    Wrappers.lambdaQuery(AssessmentSettingPO.class)
                            .like(StrUtil.isNotBlank(pagingQuery.getTheme()), AssessmentSettingPO::getTheme, pagingQuery.getTheme())
                            .orderByDesc(AssessmentSettingPO::getId));
        } else {
            List<Integer> assessmentId = assessmentInviteService.list(Wrappers.lambdaQuery(AssessmentInvitePO.class)
                            .eq(AssessmentInvitePO::getInviteeTeacherId, teacherId))
                    .stream()
                    .map(AssessmentInvitePO::getAssessmentId)
                    .toList();
            // 查询考核设置分页数据，支持按主题模糊查询并按 ID 倒序排列
            poPage = assessmentSettingService.page(new Page<>(pagingQuery.getPage(), pagingQuery.getSize()),
                    Wrappers.lambdaQuery(AssessmentSettingPO.class)
                            .and(query -> {
                                query.eq(AssessmentSettingPO::getCreateId, teacherId);
                                if (CollectionUtils.isNotEmpty(assessmentId)) {
                                    query.or().in(AssessmentSettingPO::getId, assessmentId);
                                }
                            })
                            .like(StrUtil.isNotBlank(pagingQuery.getTheme()),
                                    AssessmentSettingPO::getTheme, pagingQuery.getTheme())
                            .orderByDesc(AssessmentSettingPO::getId));
        }
        List<AssessmentSettingPO> records = poPage.getRecords();
        if (CollectionUtils.isNotEmpty(records)) {
            // 收集所有考核设置的 ID，用于查询关联的学生关系
            List<Integer> assessmentIds = records.stream().map(AssessmentSettingPO::getId).toList();
            List<AssessmentStudentRelationPO> allRelations = assessmentStudentRelationService.list(
                    Wrappers.lambdaQuery(AssessmentStudentRelationPO.class)
                            .in(AssessmentStudentRelationPO::getAssessmentId, assessmentIds));
            List<AssessmentInvitePO> invites = assessmentInviteService.list(
                    Wrappers.lambdaQuery(AssessmentInvitePO.class)
                            .in(AssessmentInvitePO::getAssessmentId, assessmentIds));
            List<AssessmentSchoolClassPO> classAssignments = assessmentSchoolClassService.list(
                    Wrappers.lambdaQuery(AssessmentSchoolClassPO.class)
                            .in(AssessmentSchoolClassPO::getAssessmentId, assessmentIds));
            List<AssessmentAppointmentSettingPO> appointments = appointmentSettingService.list(
                    Wrappers.lambdaQuery(AssessmentAppointmentSettingPO.class)
                            .in(AssessmentAppointmentSettingPO::getAssessmentId, assessmentIds));

            Map<Integer, List<AssessmentStudentRelationPO>> assessmentIdMap = allRelations.stream()
                    .collect(Collectors.groupingBy(AssessmentStudentRelationPO::getAssessmentId));
            Map<Integer, List<AssessmentInvitePO>> assessmentInviteIdMap = invites.stream()
                    .collect(Collectors.groupingBy(AssessmentInvitePO::getAssessmentId));
            Map<Integer, List<AssessmentSchoolClassPO>> assessmentClassMap = classAssignments.stream()
                    .collect(Collectors.groupingBy(AssessmentSchoolClassPO::getAssessmentId));
            Map<Integer, List<AssessmentAppointmentSettingPO>> appointMap = appointments.stream()
                    .collect(Collectors.groupingBy(AssessmentAppointmentSettingPO::getAssessmentId));

            Set<Integer> referencedUserIds = new LinkedHashSet<>();
            allRelations.stream()
                    .map(AssessmentStudentRelationPO::getStudentId)
                    .filter(Objects::nonNull)
                    .forEach(referencedUserIds::add);
            invites.stream()
                    .map(AssessmentInvitePO::getInviteeTeacherId)
                    .filter(Objects::nonNull)
                    .forEach(referencedUserIds::add);
            Map<Integer, UserPO> studentMap = referencedUserIds.isEmpty()
                    ? Collections.emptyMap()
                    : userService.listByIds(referencedUserIds).stream()
                    .collect(Collectors.toMap(UserPO::getId, item -> item));

            // 转换 PO 为 DTO，并填充参与学生信息
            return poPage.convert(item -> {
                AssessmentSettingsDTO settingDTO = BeanUtil.copyProperties(item, AssessmentSettingsDTO.class, "participatingStudents", "assessmentFiles");
                String assessmentFiles = item.getAssessmentFiles();
                if (StrUtil.isNotBlank(assessmentFiles)) {
                    List<FileDTO> fileDTOS = JSONUtil.toList(assessmentFiles, FileDTO.class);
                    settingDTO.setAssessmentFiles(fileDTOS);
                }
                // 受邀老师
                List<TeacherOptionDTO> teachers = assessmentInviteIdMap.getOrDefault(item.getId(), Collections.emptyList()).stream()
                        .map(AssessmentInvitePO::getInviteeTeacherId)
                        .map(studentMap::get)
                        .filter(Objects::nonNull)
                        .map(student -> BeanUtil.copyProperties(student, TeacherOptionDTO.class))
                        .toList();
                settingDTO.setInviteTeachers(teachers);
                List<AssessmentStudentRelationPO> relations = assessmentIdMap.getOrDefault(item.getId(), Collections.emptyList());

                // 构造参与学生 DTO 列表
                List<StudentDTO> studentDTOS = relations.stream()
                        .filter(relation -> Objects.equals(relation.getRelationType(), 0))
                        .map(AssessmentStudentRelationPO::getStudentId)
                        .map(studentMap::get)
                        .filter(Objects::nonNull)
                        .map(ite -> BeanUtil.copyProperties(ite, StudentDTO.class))
                        .toList();
                settingDTO.setStudents(studentDTOS);

                Map<String, List<StudentOptionDTO>> schoolClassMap = relations.stream()
                        .filter(relation -> Objects.equals(relation.getRelationType(), 1))
                        .map(AssessmentStudentRelationPO::getStudentId)
                        .map(studentMap::get)
                        .filter(Objects::nonNull)
                        .map(ite -> BeanUtil.copyProperties(ite, StudentOptionDTO.class))
                        .collect(Collectors.groupingBy(student -> Objects.toString(student.getSchoolClass(), "")));
                List<SchoolClassStudentDTO> schoolClassStudents = assessmentClassMap
                        .getOrDefault(item.getId(), Collections.emptyList()).stream()
                        .map(AssessmentSchoolClassPO::getSchoolClass)
                        .distinct()
                        .map(schoolClass -> {
                            SchoolClassStudentDTO schoolClassStudentDTO = new SchoolClassStudentDTO();
                            schoolClassStudentDTO.setSchoolClass(schoolClass);
                            schoolClassStudentDTO.setStudents(
                                    schoolClassMap.getOrDefault(schoolClass, Collections.emptyList()));
                            return schoolClassStudentDTO;
                        }).toList();
                settingDTO.setSchoolClassList(schoolClassStudents);

                settingDTO.setParticipatingStudents(relations.stream()
                        .filter(relation -> studentMap.containsKey(relation.getStudentId()))
                        .map(relation -> {
                            AssessmentSettingsDTO.ParticipatingStudentDTO studentDTO = new AssessmentSettingsDTO.ParticipatingStudentDTO();
                            UserPO matchStudent = studentMap.get(relation.getStudentId());
                            studentDTO.setStudentName(matchStudent.getName());
                            studentDTO.setSchoolClass(matchStudent.getSchoolClass());
                            return studentDTO;
                        }).toList());

                List<AssessmentAppointmentSettingPO> matchAppoints = appointMap.getOrDefault(item.getId(), Collections.emptyList());
                settingDTO.setAppointmentSetting(AppointmentSettingDTO.builder()
                        .assessmentFailPunish(item.getAssessmentFailPunish())
                        .rescheduleAppointTime(item.getRescheduleAppointTime())
                        .timePeriods(matchAppoints.stream().map(appoint -> {
                            AppointmentSettingDTO.TimePeriod timePeriod = new AppointmentSettingDTO.TimePeriod();
                            timePeriod.setId(appoint.getId());
                            timePeriod.setLocation(appoint.getLocation());
                            timePeriod.setTimePeriod(appoint.getTimePeriod());
                            timePeriod.setParticipantLimit(appoint.getParticipantLimit());
                            return timePeriod;
                        }).toList())
                        .build());
                return settingDTO;
            });
        }
        // 如果没有数据，返回空分页对象
        return new Page<>(pagingQuery.getPage(), pagingQuery.getSize(), poPage.getTotal());
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean removeAssessmentSettings(Integer teacherId, AssessmentSettingsRemoveCmd deleteCmd) {
        accessControlService.requireAssessmentManager(teacherId, deleteCmd.getId());
        List<StudentAssessmentRecordPO> records = recordService.list(
                Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                        .eq(StudentAssessmentRecordPO::getAssessmentId, deleteCmd.getId()));
        List<StudentAssessmentQuestionAnswerPO> answers = questionAnswerService.list(
                Wrappers.lambdaQuery(StudentAssessmentQuestionAnswerPO.class)
                        .eq(StudentAssessmentQuestionAnswerPO::getAssessmentId, deleteCmd.getId()));
        assessmentStudentRelationService.remove(Wrappers.lambdaQuery(AssessmentStudentRelationPO.class)
                .eq(AssessmentStudentRelationPO::getAssessmentId, deleteCmd.getId()));
        assessmentSchoolClassService.remove(Wrappers.lambdaQuery(AssessmentSchoolClassPO.class)
                .eq(AssessmentSchoolClassPO::getAssessmentId, deleteCmd.getId()));
        assessmentInviteService.remove(Wrappers.lambdaQuery(AssessmentInvitePO.class)
                .eq(AssessmentInvitePO::getAssessmentId, deleteCmd.getId()));
        studentAssessmentAppointmentService.remove(Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                .eq(StudentAssessmentAppointmentPO::getAssessmentId, deleteCmd.getId()));
        appointmentSettingService.remove(Wrappers.lambdaQuery(AssessmentAppointmentSettingPO.class)
                .eq(AssessmentAppointmentSettingPO::getAssessmentId, deleteCmd.getId()));
        recordService.remove(Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                .eq(StudentAssessmentRecordPO::getAssessmentId, deleteCmd.getId()));
        questionAnswerService.remove(Wrappers.lambdaQuery(StudentAssessmentQuestionAnswerPO.class)
                .eq(StudentAssessmentQuestionAnswerPO::getAssessmentId, deleteCmd.getId()));
        LocalDateTime deletedAt = LocalDateTime.now();
        if (assessmentSettingMapper.softDeleteWithTimestamp(deleteCmd.getId(), deletedAt) != 1) {
            throw new BusinessException(ErrorCodeEnums.ASSESSMENT_SETTING_NOT_FOUND);
        }
        objectCleanupService.deleteInterviewArtifacts(records, answers);
        return true;
    }

    public AssessmentSimpleDTO detail(Integer studentId, Integer assessmentId) {
        accessControlService.requireAssignedStudent(studentId, assessmentId);
        AssessmentSettingPO setting = assessmentSettingService.findById(assessmentId).orElseThrow(() -> new BusinessException(ErrorCodeEnums.ASSESSMENT_SETTING_NOT_FOUND));
        AssessmentSimpleDTO assessmentSimpleDTO = BeanUtil.copyProperties(setting, AssessmentSimpleDTO.class);
        Optional<StudentAssessmentRecordPO> recordOp = recordService.findByStudentIdAndAssessmentId(studentId, assessmentId);
        recordOp.ifPresent(item -> assessmentSimpleDTO.setFinishDefense(item.getEndDefenseTime() != null));
        return assessmentSimpleDTO;
    }

    /**
     * 设置考核预约
     * 该方法用于配置特定考核的预约设置，包括考核失败惩罚策略和具体的预约时间段
     *
     * @param appointmentCmd 预约设置创建命令对象，包含考核ID、失败惩罚策略、惩罚天数以及预约时间段列表
     * @return Boolean 操作结果，成功返回true
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean settingAppointment(Integer teacherId, AppointmentSettingCreateCmd appointmentCmd) {
        Integer assessmentId = appointmentCmd.getAssessmentId();
        accessControlService.requireAssessmentManager(teacherId, assessmentId);
        requireUniqueAppointmentPeriods(appointmentCmd);
        AssessmentSettingPO setting = assessmentSettingService.findById(assessmentId)
                .orElseThrow(() -> new BusinessException(ErrorCodeEnums.ASSESSMENT_SETTING_NOT_FOUND));
        setting.setAssessmentFailPunish(appointmentCmd.getAssessmentFailPunish());
        setting.setRescheduleAppointTime(appointmentCmd.getRescheduleAppointTime());
        setting.setLastAppointTime(appointmentCmd.lastTimePeriod());
        requirePersistence(
                assessmentSettingService.updateById(setting), "预约设置更新失败");

        // 获取需要添加和更新的预约设置列表
        List<AssessmentAppointmentSettingPO> appointmentSettingPOList = appointmentCmd.toAppointmentSettingPOList();
        List<AssessmentAppointmentSettingPO> existAppointments = appointmentSettingService.list(
                Wrappers.lambdaQuery(AssessmentAppointmentSettingPO.class)
                        .eq(AssessmentAppointmentSettingPO::getAssessmentId, assessmentId));
        List<AssessmentAppointmentSettingPO> needUpdate = appointmentSettingPOList.stream().filter(item -> item.getId() != null).collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(needUpdate)) {
            // 完成的考核
            List<StudentAssessmentRecordPO> records = recordService.list(Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                    .eq(StudentAssessmentRecordPO::getAssessmentId, assessmentId)
                    .in(StudentAssessmentRecordPO::getState, 1, 2));
            Map<String, StudentAssessmentRecordPO> studentAssessmentRecordMap = records.stream()
                    .collect(Collectors.toMap(StudentAssessmentRecordPO::assessmentIdAndStudentId, item -> item));
            Map<Integer, AssessmentAppointmentSettingPO> appointmentIdMap = existAppointments.stream().collect(Collectors.toMap(AssessmentAppointmentSettingPO::getId, item -> item));
            for (AssessmentAppointmentSettingPO assessmentAppointmentSettingPO : needUpdate) {
                AssessmentAppointmentSettingPO matchExistSetting = appointmentIdMap.get(assessmentAppointmentSettingPO.getId());
                if (matchExistSetting == null) {
                    throw new BusinessException(ErrorCodeEnums.ASSESSMENT_APPOINTMENT_SETTING_NOT_FOUND);
                }
                LocalDateTime oldTimePeriod = matchExistSetting.getTimePeriod();
                matchExistSetting.setTimePeriod(assessmentAppointmentSettingPO.getTimePeriod());
                matchExistSetting.setParticipantLimit(assessmentAppointmentSettingPO.getParticipantLimit());
                matchExistSetting.setLocation(assessmentAppointmentSettingPO.getLocation());
                requirePersistence(
                        appointmentSettingService.updateById(matchExistSetting), "预约时段更新失败");
                // 如果更新的时间段和数据库中的时间段不一致，则需要通知预约
                LocalDateTime newTimePeriod = assessmentAppointmentSettingPO.getTimePeriod();
                if (!Objects.equals(oldTimePeriod, newTimePeriod)) {
                    List<StudentAssessmentAppointmentPO> matchTimePeriodAppointments = studentAssessmentAppointmentService.list(Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                            .eq(StudentAssessmentAppointmentPO::getAssessmentId, assessmentId)
                            .eq(StudentAssessmentAppointmentPO::getTimePeriod, oldTimePeriod));
                    if (CollectionUtils.isNotEmpty(matchTimePeriodAppointments)) {
                        // 如果该预约设置有对应的考核信息（相同的考核id+学生id），那就不需要通知
                        List<StudentAssessmentAppointmentPO> updateAppointments = matchTimePeriodAppointments
                                .stream()
                                .filter(period -> studentAssessmentRecordMap.get(period.assessmentIdAndStudentId()) == null)
                                .peek(item -> {
                                    item.setState(0);
                                    item.setTimePeriod(newTimePeriod);
                                })
                                .toList();
                        if (CollectionUtils.isNotEmpty(updateAppointments)) {
                            requirePersistence(
                                    studentAssessmentAppointmentService.updateBatchById(updateAppointments),
                                    "学生预约时段更新失败");
                        }
                    }
                }
            }
        }

        // 即使所有提交项都是新时段，也要删除本次提交中已经移除的旧时段。
        Set<Integer> retainedAppointmentIds = needUpdate.stream()
                .map(AssessmentAppointmentSettingPO::getId)
                .collect(Collectors.toSet());
        List<AssessmentAppointmentSettingPO> needDeleteAppointments = existAppointments.stream()
                .filter(item -> !retainedAppointmentIds.contains(item.getId()))
                .toList();
        if (CollectionUtils.isNotEmpty(needDeleteAppointments)) {
            List<Integer> removeIds = needDeleteAppointments.stream()
                    .map(AssessmentAppointmentSettingPO::getId)
                    .toList();
            requirePersistence(
                    appointmentSettingService.removeBatchByIds(removeIds), "预约时段删除失败");
            // 删除关联的学生预约
            List<LocalDateTime> removeTimePeriods = needDeleteAppointments.stream()
                    .map(AssessmentAppointmentSettingPO::getTimePeriod)
                    .toList();
            studentAssessmentAppointmentService.remove(Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                    .eq(StudentAssessmentAppointmentPO::getAssessmentId, appointmentCmd.getAssessmentId())
                    .in(StudentAssessmentAppointmentPO::getTimePeriod, removeTimePeriods));
        }
        List<AssessmentAppointmentSettingPO> needAdd = appointmentSettingPOList.stream().filter(item -> item.getId() == null).collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(needAdd)) {
            requirePersistence(
                    appointmentSettingService.saveBatch(needAdd), "预约时段新增失败");
        }
        return true;
    }

    private static void requireUniqueAppointmentPeriods(AppointmentSettingCreateCmd appointmentCmd) {
        Set<LocalDateTime> timePeriods = new HashSet<>();
        for (AppointmentSettingCreateCmd.TimePeriod period : appointmentCmd.getTimePeriods()) {
            if (!timePeriods.add(period.getTimePeriod())) {
                throw new BusinessException(400, "预约时间段不能重复");
            }
        }
    }

    private static void requirePersistence(boolean persisted, String message) {
        if (!persisted) {
            throw new IllegalStateException(message);
        }
    }

    public AssessmentAppointSettingSummaryDTO settingAppointmentSummary(Integer teacherId, Integer assessmentId) {
        accessControlService.requireAssessmentManager(teacherId, assessmentId);
        List<AssessmentAppointmentSettingPO> appointmentSettingPOS = appointmentSettingService.list(Wrappers.lambdaQuery(AssessmentAppointmentSettingPO.class)
                .eq(AssessmentAppointmentSettingPO::getAssessmentId, assessmentId)
                .orderByAsc(AssessmentAppointmentSettingPO::getTimePeriod));

        List<StudentAssessmentAppointmentPO> studentAppointments = studentAssessmentAppointmentService.list(Wrappers.lambdaQuery(StudentAssessmentAppointmentPO.class)
                .eq(StudentAssessmentAppointmentPO::getAssessmentId, assessmentId)
                .orderByDesc(StudentAssessmentAppointmentPO::getAppointmentTime));

        // 总关联的学生
        List<AssessmentStudentRelationPO> relationStudents = assessmentStudentRelationService.list(Wrappers.lambdaQuery(AssessmentStudentRelationPO.class)
                .eq(AssessmentStudentRelationPO::getAssessmentId, assessmentId));
        List<Integer> allRelationStudentIds = relationStudents.stream().map(AssessmentStudentRelationPO::getStudentId).distinct().toList();
        Map<Integer, UserPO> userIdMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(allRelationStudentIds)) {
            // 查询学生信息
            List<UserPO> students = userService.listByIds(allRelationStudentIds);
            userIdMap.putAll(students.stream().collect(Collectors.toMap(UserPO::getId, item -> item)));
        }

        Map<LocalDateTime, List<StudentAssessmentAppointmentPO>> timePeriodMap = studentAppointments.stream().collect(Collectors.groupingBy(StudentAssessmentAppointmentPO::getTimePeriod));
        AssessmentAppointSettingSummaryDTO settingSummaryDTO = new AssessmentAppointSettingSummaryDTO();
        settingSummaryDTO.setAlreadyAppointCount(studentAppointments.size());

        // 剔除预约的学生，就是未预约的学生
        Set<Integer> studentIds = studentAppointments.stream().map(StudentAssessmentAppointmentPO::getStudentId).collect(Collectors.toSet());
        relationStudents.removeIf(item -> studentIds.contains(item.getStudentId()));
        settingSummaryDTO.setNotAppointCount(relationStudents.size());
        settingSummaryDTO.setNotAppointStudents(relationStudents.stream()
                .map(item -> {
                    UserPO user = userIdMap.getOrDefault(item.getStudentId(), UserPO.EMPTY_USER);
                    return AssessmentAppointSettingSummaryDTO.AppointmentStudentDTO.builder()
                            .id(user.getId())
                            .schoolClass(user.getSchoolClass())
                            .studentName(user.getName())
                            .build();
                })
                .toList());
        settingSummaryDTO.setTimePeriods(appointmentSettingPOS.stream()
                .map(item -> {
                    List<StudentAssessmentAppointmentPO> matchAppointments = timePeriodMap.getOrDefault(item.getTimePeriod(), Collections.emptyList());
                    return AssessmentAppointSettingDTO.builder()
                            .timePeriod(item.getTimePeriod())
                            .location(item.getLocation())
                            .appointmentStudents(matchAppointments.stream()
                                    .map(appointment -> toAppointmentStudentDTO(appointment, userIdMap.getOrDefault(appointment.getStudentId(), UserPO.EMPTY_USER)))
                                    .toList())
                            .alreadyAssessStudents(matchAppointments.stream()
                                    .filter(record -> Objects.equals(record.getState(), 2))
                                    .map(appointment -> toAppointmentStudentDTO(appointment, userIdMap.getOrDefault(appointment.getStudentId(), UserPO.EMPTY_USER)))
                                    .toList())
                            .expiredStudents(matchAppointments.stream()
                                    .filter(record -> Objects.equals(record.getState(), 3) || Objects.equals(record.getState(), 4))
                                    .map(appointment -> toAppointmentStudentDTO(appointment, userIdMap.getOrDefault(appointment.getStudentId(), UserPO.EMPTY_USER)))
                                    .toList())
                            .build();
                }).toList());
        return settingSummaryDTO;
    }

    private AssessmentAppointSettingDTO.AppointmentStudentDTO toAppointmentStudentDTO(StudentAssessmentAppointmentPO appointmentPO, UserPO userPO) {
        AssessmentAppointSettingDTO.AppointmentStudentDTO studentDTO = new AssessmentAppointSettingDTO.AppointmentStudentDTO();
        studentDTO.setId(appointmentPO.getId());
        studentDTO.setStudentName(userPO.getName());
        studentDTO.setSchoolClass(userPO.getSchoolClass());
        Integer punishState = appointmentPO.getPunishState();
        if (punishState != null && !Objects.equals(punishState, -1)) {
            studentDTO.setPunishState(punishState);
        }
        return studentDTO;
    }

    public Boolean liftPunish(Integer teacherId, LiftPunishCmd liftPunishCmd) {
        StudentAssessmentAppointmentPO appointment = Optional.ofNullable(
                        studentAssessmentAppointmentService.getById(liftPunishCmd.getId()))
                .orElseThrow(() -> new BusinessException(ErrorCodeEnums.ASSESSMENT_APPOINTMENT_SETTING_NOT_FOUND));
        accessControlService.requireAssessmentManager(teacherId, appointment.getAssessmentId());
        appointment.setState(4);
        appointment.setPunishState(1);
        return studentAssessmentAppointmentService.updateById(appointment);
    }

    public Boolean cancelStudentAppointment(Integer teacherId, CancelStudentAppointmentCmd cancelStudentAppointmentCmd) {
        StudentAssessmentAppointmentPO appointment = Optional.ofNullable(
                        studentAssessmentAppointmentService.getById(cancelStudentAppointmentCmd.getAppointmentId()))
                .orElseThrow(() -> new BusinessException(ErrorCodeEnums.ASSESSMENT_APPOINTMENT_SETTING_NOT_FOUND));
        accessControlService.requireAssessmentManager(teacherId, appointment.getAssessmentId());
        return studentAssessmentAppointmentService.removeById(cancelStudentAppointmentCmd.getAppointmentId());
    }
}
