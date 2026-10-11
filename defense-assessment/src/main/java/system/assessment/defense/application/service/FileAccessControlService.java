package system.assessment.defense.application.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.emuns.UserRoleEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentQuestionAnswerPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.List;
import java.util.Objects;

/**
 * 校验文件对象归属。新对象直接按用户前缀判断，旧对象回查其业务资源引用。
 */
@Service
public class FileAccessControlService {

    private final UserService userService;
    private final ResourceAccessControlService resourceAccessControlService;
    private final AssessmentSettingService settingService;
    private final StudentAssessmentRecordService recordService;
    private final StudentAssessmentQuestionAnswerService answerService;
    private final FileSecurityService fileSecurityService;
    private final ManagedUploadService managedUploadService;

    public FileAccessControlService(UserService userService,
                                    ResourceAccessControlService resourceAccessControlService,
                                    AssessmentSettingService settingService,
                                    StudentAssessmentRecordService recordService,
                                    StudentAssessmentQuestionAnswerService answerService,
                                    FileSecurityService fileSecurityService) {
        this(userService, resourceAccessControlService, settingService, recordService,
                answerService, fileSecurityService, null);
    }

    @Autowired
    public FileAccessControlService(UserService userService,
                                    ResourceAccessControlService resourceAccessControlService,
                                    AssessmentSettingService settingService,
                                    StudentAssessmentRecordService recordService,
                                    StudentAssessmentQuestionAnswerService answerService,
                                    FileSecurityService fileSecurityService,
                                    ManagedUploadService managedUploadService) {
        this.userService = userService;
        this.resourceAccessControlService = resourceAccessControlService;
        this.settingService = settingService;
        this.recordService = recordService;
        this.answerService = answerService;
        this.fileSecurityService = fileSecurityService;
        this.managedUploadService = managedUploadService;
    }

    public void requireOwnedUpload(Integer userId, String fileKey) {
        fileSecurityService.requireOwnedPdfKey(userId, fileKey);
        consumeTrackedUpload(userId, fileKey);
    }

    /**
     * A manager may add a newly uploaded object of their own, or retain an
     * object already attached to this assessment. The latter keeps legacy and
     * invited-teacher edits working without permitting arbitrary key reuse.
     */
    public void requireAssessmentMaterialBinding(Integer userId, Integer assessmentId, String fileKey) {
        fileSecurityService.requireSafePdfKey(fileKey);
        AssessmentSettingPO setting = settingService.getById(assessmentId);
        if (fileSecurityService.isOwnedPdfKey(userId, fileKey)) {
            if (setting != null && containsExactFileReference(setting.getAssessmentFiles(), fileKey)) {
                return;
            }
            consumeTrackedUpload(userId, fileKey);
            return;
        }
        if (setting != null
                && containsExactFileReference(setting.getAssessmentFiles(), fileKey)
                && hasValidAssessmentMaterialOwner(assessmentId, fileKey)) {
            return;
        }
        throw new BusinessException(ErrorCodeEnums.NO_PERMISSION);
    }

    public void requireStudentDefenseFileBinding(Integer studentId, Integer assessmentId, String fileKey) {
        fileSecurityService.requireOwnedPdfKey(studentId, fileKey);
        boolean alreadyBound = recordService.findByStudentIdAndAssessmentId(studentId, assessmentId)
                .map(StudentAssessmentRecordPO::getDefenseFile)
                .filter(files -> containsExactFileReference(files, fileKey))
                .isPresent();
        if (!alreadyBound) {
            consumeTrackedUpload(studentId, fileKey);
        }
    }

    private void consumeTrackedUpload(Integer userId, String fileKey) {
        if (managedUploadService != null) {
            managedUploadService.consumeForBinding(userId, fileKey);
        }
    }

    public void requireReadAccess(Integer userId, String fileKey) {
        fileSecurityService.requireSafePdfKey(fileKey);
        UserPO user = userService.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodeEnums.USER_NOT_FOUND));
        if (Objects.equals(user.getType(), UserRoleEnums.ADMIN.getRole())) {
            return;
        }

        if (fileSecurityService.ownerIdOfUserPdfKey(fileKey)
                .filter(ownerId -> Objects.equals(ownerId, userId))
                .isPresent()) {
            return;
        }

        List<AssessmentSettingPO> settings = settingService.list(
                Wrappers.lambdaQuery(AssessmentSettingPO.class)
                        .like(AssessmentSettingPO::getAssessmentFiles, fileKey));
        if (settings.stream().anyMatch(setting ->
                containsExactFileReference(setting.getAssessmentFiles(), fileKey)
                        && hasValidAssessmentMaterialOwner(setting.getId(), fileKey)
                        && canReadAssessment(user, setting.getId()))) {
            return;
        }

        List<StudentAssessmentRecordPO> records = recordService.list(
                Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                        .like(StudentAssessmentRecordPO::getDefenseFile, fileKey));
        if (records.stream().anyMatch(record ->
                containsExactFileReference(record.getDefenseFile(), fileKey)
                        && hasMatchingStudentOwner(record.getStudentId(), fileKey)
                        && canReadRecord(user, record))) {
            return;
        }

        List<StudentAssessmentQuestionAnswerPO> answers = answerService.list(
                Wrappers.lambdaQuery(StudentAssessmentQuestionAnswerPO.class)
                        .and(query -> query.like(StudentAssessmentQuestionAnswerPO::getAnswerVoice, fileKey)
                                .or()
                                .like(StudentAssessmentQuestionAnswerPO::getFollowAnswerVoice, fileKey)));
        if (answers.stream().anyMatch(answer ->
                isExactAnswerReference(answer, fileKey)
                        && hasMatchingStudentOwner(answer.getStudentId(), fileKey)
                        && canReadAnswer(user, answer))) {
            return;
        }
        throw new BusinessException(ErrorCodeEnums.NO_PERMISSION);
    }

    private boolean canReadAssessment(UserPO user, Integer assessmentId) {
        if (Objects.equals(user.getType(), UserRoleEnums.STUDENT.getRole())) {
            return resourceAccessControlService.isAssignedStudent(user.getId(), assessmentId);
        }
        return resourceAccessControlService.isAssessmentManager(user.getId(), assessmentId);
    }

    private boolean canReadRecord(UserPO user, StudentAssessmentRecordPO record) {
        if (Objects.equals(user.getType(), UserRoleEnums.STUDENT.getRole())) {
            return Objects.equals(user.getId(), record.getStudentId());
        }
        return resourceAccessControlService.isAssessmentManager(user.getId(), record.getAssessmentId());
    }

    private boolean canReadAnswer(UserPO user, StudentAssessmentQuestionAnswerPO answer) {
        if (Objects.equals(user.getType(), UserRoleEnums.STUDENT.getRole())) {
            return Objects.equals(user.getId(), answer.getStudentId());
        }
        return resourceAccessControlService.isAssessmentManager(user.getId(), answer.getAssessmentId());
    }

    private boolean containsExactFileReference(String storedFiles, String fileKey) {
        if (StrUtil.isBlank(storedFiles)) {
            return false;
        }
        if (storedFiles.equals(fileKey)) {
            return true;
        }
        try {
            Object parsed = JSONUtil.parse(storedFiles);
            if (parsed instanceof JSONObject file) {
                return fileKey.equals(file.getStr("fileUrl"));
            }
            if (parsed instanceof JSONArray files) {
                return files.stream()
                        .filter(JSONObject.class::isInstance)
                        .map(JSONObject.class::cast)
                        .map(file -> file.getStr("fileUrl"))
                        .anyMatch(fileKey::equals);
            }
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean hasValidAssessmentMaterialOwner(Integer assessmentId, String fileKey) {
        return fileSecurityService.ownerIdOfUserPdfKey(fileKey)
                .map(ownerId -> resourceAccessControlService.isAssessmentManager(ownerId, assessmentId))
                .orElse(true);
    }

    private boolean hasMatchingStudentOwner(Integer studentId, String fileKey) {
        return fileSecurityService.ownerIdOfUserPdfKey(fileKey)
                .map(ownerId -> Objects.equals(ownerId, studentId))
                .orElse(true);
    }

    private boolean isExactAnswerReference(StudentAssessmentQuestionAnswerPO answer, String fileKey) {
        return Objects.equals(fileKey, answer.getAnswerVoice())
                || Objects.equals(fileKey, answer.getFollowAnswerVoice());
    }
}
