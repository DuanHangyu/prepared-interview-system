package system.assessment.defense.application.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import system.assessment.defense.application.dto.StudentRemoveCmd;
import system.assessment.defense.application.dto.StudentModifyCmd;
import system.assessment.defense.application.dto.TeacherModifyCmd;
import system.assessment.defense.application.dto.TeacherRemoveCmd;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentInviteService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentStudentRelationService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDeletionIntegrityTest {

    @Mock private UserService userService;
    @Mock private PasswordSecurityService passwordSecurityService;
    @Mock private AssessmentSettingService assessmentSettingService;
    @Mock private AssessmentInviteService assessmentInviteService;
    @Mock private StudentAssessmentAppointmentService appointmentService;
    @Mock private AssessmentStudentRelationService relationService;
    @Mock private StudentAssessmentRecordService recordService;
    @Mock private StudentAssessmentQuestionAnswerService questionAnswerService;
    @Mock private ManagedObjectCleanupService objectCleanupService;

    @InjectMocks private TeacherAppService teacherAppService;
    @InjectMocks private StudentAppService studentAppService;

    @Test
    void teacherEndpointCannotDeleteAUserWithAnotherRole() {
        TeacherRemoveCmd command = teacherCommand(9);
        when(userService.getById(9)).thenReturn(UserPO.builder().id(9).type(2).build());

        assertThatThrownBy(() -> teacherAppService.deleteStudent(command))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(108);

        verify(userService, never()).removeById(any(Integer.class));
        verify(assessmentSettingService, never()).count(any(Wrapper.class));
    }

    @Test
    void teacherEndpointCannotConvertAUserWithAnotherRole() {
        TeacherModifyCmd command = new TeacherModifyCmd();
        command.setId(9);
        command.setPassword("secure-password");
        when(userService.getById(9)).thenReturn(UserPO.builder().id(9).type(2).build());

        assertThatThrownBy(() -> teacherAppService.modifyStudent(command))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(108);

        verify(userService, never()).updateById(any(UserPO.class));
    }

    @Test
    void studentEndpointCannotDeleteAUserWithAnotherRole() {
        StudentRemoveCmd command = studentCommand(9);
        when(userService.getById(9)).thenReturn(UserPO.builder().id(9).type(1).build());

        assertThatThrownBy(() -> studentAppService.deleteStudent(command))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(104);

        verify(userService, never()).removeById(any(Integer.class));
        verify(recordService, never()).list(any(Wrapper.class));
    }

    @Test
    void studentEndpointCannotConvertAUserWithAnotherRole() {
        StudentModifyCmd command = new StudentModifyCmd();
        command.setId(9);
        command.setPassword("secure-password");
        when(userService.getById(9)).thenReturn(UserPO.builder().id(9).type(1).build());

        assertThatThrownBy(() -> studentAppService.modifyStudent(command))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(104);

        verify(userService, never()).updateById(any(UserPO.class));
    }

    @Test
    void teacherWithOwnedAssessmentsCannotBeDeleted() {
        TeacherRemoveCmd command = teacherCommand(7);
        when(userService.getById(7)).thenReturn(UserPO.builder().id(7).type(1).build());
        when(assessmentSettingService.count(any(Wrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> teacherAppService.deleteStudent(command))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(143);

        verify(assessmentInviteService, never()).remove(any(Wrapper.class));
        verify(userService, never()).removeById(any(Integer.class));
    }

    @Test
    void teacherInvitesAreRemovedBeforeTheTeacherAccount() {
        TeacherRemoveCmd command = teacherCommand(7);
        when(userService.getById(7)).thenReturn(UserPO.builder().id(7).type(1).build());
        when(assessmentSettingService.count(any(Wrapper.class))).thenReturn(0L);
        when(assessmentInviteService.remove(any(Wrapper.class))).thenReturn(true);
        when(userService.removeById(7)).thenReturn(true);

        assertThat(teacherAppService.deleteStudent(command)).isTrue();

        InOrder order = inOrder(assessmentInviteService, userService);
        order.verify(assessmentInviteService).remove(any(Wrapper.class));
        order.verify(userService).removeById(7);
    }

    @Test
    void failedTeacherAccountDeleteRaisesAnErrorForTransactionRollback() {
        TeacherRemoveCmd command = teacherCommand(7);
        when(userService.getById(7)).thenReturn(UserPO.builder().id(7).type(1).build());
        when(assessmentSettingService.count(any(Wrapper.class))).thenReturn(0L);
        when(assessmentInviteService.remove(any(Wrapper.class))).thenReturn(true);
        when(userService.removeById(7)).thenReturn(false);

        assertThatThrownBy(() -> teacherAppService.deleteStudent(command))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(108);
    }

    @Test
    void failedStudentAccountDeleteRaisesAnErrorAndDoesNotScheduleArtifactRemoval() {
        StudentRemoveCmd command = studentCommand(9);
        when(userService.getById(9)).thenReturn(UserPO.builder().id(9).type(0).build());
        when(userService.removeById(9)).thenReturn(false);

        assertThatThrownBy(() -> studentAppService.deleteStudent(command))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(104);

        verifyNoInteractions(objectCleanupService);
    }

    private TeacherRemoveCmd teacherCommand(int id) {
        TeacherRemoveCmd command = new TeacherRemoveCmd();
        command.setId(id);
        return command;
    }

    private StudentRemoveCmd studentCommand(int id) {
        StudentRemoveCmd command = new StudentRemoveCmd();
        command.setId(id);
        return command;
    }
}
