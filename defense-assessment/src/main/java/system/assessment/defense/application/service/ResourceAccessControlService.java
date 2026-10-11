package system.assessment.defense.application.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.emuns.UserRoleEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentInvitePO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentStudentRelationPO;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentInviteService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentStudentRelationService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.Objects;
import java.util.Optional;

/**
 * 集中执行对象级访问控制，避免仅依赖前端隐藏入口。
 */
@Service
@RequiredArgsConstructor
public class ResourceAccessControlService {

    private final UserService userService;
    private final AssessmentSettingService assessmentSettingService;
    private final AssessmentInviteService assessmentInviteService;
    private final AssessmentStudentRelationService relationService;

    public void requireAssessmentManager(Integer userId, Integer assessmentId) {
        if (!isAssessmentManager(userId, assessmentId)) {
            throw new BusinessException(ErrorCodeEnums.NO_PERMISSION);
        }
    }

    public void requireAssignedStudent(Integer studentId, Integer assessmentId) {
        if (!isAssignedStudent(studentId, assessmentId)) {
            throw new BusinessException(ErrorCodeEnums.NO_PERMISSION);
        }
    }

    public boolean isAssessmentManager(Integer userId, Integer assessmentId) {
        Optional<UserPO> userOptional = userService.findById(userId);
        Optional<AssessmentSettingPO> assessmentOptional = assessmentSettingService.findById(assessmentId);
        if (userOptional.isEmpty() || assessmentOptional.isEmpty()) {
            return false;
        }
        UserPO user = userOptional.get();
        AssessmentSettingPO assessment = assessmentOptional.get();
        if (Objects.equals(user.getType(), UserRoleEnums.ADMIN.getRole())) {
            return true;
        }
        if (!Objects.equals(user.getType(), UserRoleEnums.TEACHER.getRole())) {
            return false;
        }
        if (Objects.equals(assessment.getCreateId(), userId)) {
            return true;
        }
        return assessmentInviteService.count(
                Wrappers.lambdaQuery(AssessmentInvitePO.class)
                        .eq(AssessmentInvitePO::getAssessmentId, assessmentId)
                        .eq(AssessmentInvitePO::getInviteeTeacherId, userId)) > 0;
    }

    public boolean isAssignedStudent(Integer studentId, Integer assessmentId) {
        Optional<UserPO> userOptional = userService.findById(studentId);
        if (userOptional.isEmpty()
                || !Objects.equals(userOptional.get().getType(), UserRoleEnums.STUDENT.getRole())
                || assessmentSettingService.findById(assessmentId).isEmpty()) {
            return false;
        }
        return relationService.count(
                Wrappers.lambdaQuery(AssessmentStudentRelationPO.class)
                        .eq(AssessmentStudentRelationPO::getAssessmentId, assessmentId)
                        .eq(AssessmentStudentRelationPO::getStudentId, studentId)) > 0;
    }

}
