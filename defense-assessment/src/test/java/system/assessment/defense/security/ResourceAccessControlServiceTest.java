package system.assessment.defense.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentInvitePO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentStudentRelationPO;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentInviteService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentStudentRelationService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceAccessControlServiceTest {

    private UserService userService;
    private AssessmentSettingService settingService;
    private AssessmentInviteService inviteService;
    private AssessmentStudentRelationService relationService;
    private ResourceAccessControlService accessControlService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        settingService = mock(AssessmentSettingService.class);
        inviteService = mock(AssessmentInviteService.class);
        relationService = mock(AssessmentStudentRelationService.class);
        accessControlService = new ResourceAccessControlService(
                userService, settingService, inviteService, relationService);
    }

    @Test
    void adminCanManageAnyExistingAssessment() {
        when(userService.findById(9)).thenReturn(Optional.of(user(9, 2)));
        when(settingService.findById(21)).thenReturn(Optional.of(setting(21, 4)));

        assertThatCode(() -> accessControlService.requireAssessmentManager(9, 21))
                .doesNotThrowAnyException();
    }

    @Test
    void creatingAndInvitedTeachersCanManageAssessment() {
        when(userService.findById(4)).thenReturn(Optional.of(user(4, 1)));
        when(settingService.findById(21)).thenReturn(Optional.of(setting(21, 4)));
        assertThatCode(() -> accessControlService.requireAssessmentManager(4, 21))
                .doesNotThrowAnyException();

        when(userService.findById(7)).thenReturn(Optional.of(user(7, 1)));
        when(inviteService.count(any())).thenReturn(1L);
        assertThatCode(() -> accessControlService.requireAssessmentManager(7, 21))
                .doesNotThrowAnyException();
    }

    @Test
    void unrelatedTeacherCannotManageAssessment() {
        when(userService.findById(7)).thenReturn(Optional.of(user(7, 1)));
        when(settingService.findById(21)).thenReturn(Optional.of(setting(21, 4)));
        when(inviteService.count(any())).thenReturn(0L);

        assertThatThrownBy(() -> accessControlService.requireAssessmentManager(7, 21))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void onlyAssignedStudentCanEnterAssessment() {
        when(userService.findById(12)).thenReturn(Optional.of(user(12, 0)));
        when(settingService.findById(21)).thenReturn(Optional.of(setting(21, 4)));
        when(relationService.count(any())).thenReturn(1L);
        assertThatCode(() -> accessControlService.requireAssignedStudent(12, 21))
                .doesNotThrowAnyException();

        when(relationService.count(any())).thenReturn(0L);
        assertThatThrownBy(() -> accessControlService.requireAssignedStudent(12, 21))
                .isInstanceOf(BusinessException.class);
    }

    private UserPO user(int id, int type) {
        return UserPO.builder().id(id).type(type).build();
    }

    private AssessmentSettingPO setting(int id, int creatorId) {
        return AssessmentSettingPO.builder().id(id).createId(creatorId).build();
    }
}
