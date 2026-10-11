package system.assessment.defense.security;

// Regression: ISSUE-003 — profile edits incorrectly forced administrators to reset passwords.
// Found by /qa on 2026-10-03
// Report: .gstack/qa-reports/qa-report-localhost-2026-10-03.md

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import system.assessment.defense.application.dto.StudentModifyCmd;
import system.assessment.defense.application.dto.TeacherModifyCmd;
import system.assessment.defense.application.service.ManagedObjectCleanupService;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.application.service.StudentAppService;
import system.assessment.defense.application.service.TeacherAppService;
import system.assessment.defense.infrastructure.common.HttpUtils;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentInviteService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSchoolClassService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentStudentRelationService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OptionalPasswordProfileEditRegressionTest {

    @Mock private UserService userService;
    @Mock private PasswordSecurityService passwordSecurityService;
    @Mock private AssessmentSettingService assessmentSettingService;
    @Mock private AssessmentInviteService assessmentInviteService;
    @Mock private StudentAssessmentAppointmentService appointmentService;
    @Mock private AssessmentStudentRelationService relationService;
    @Mock private AssessmentSchoolClassService schoolClassService;
    @Mock private StudentAssessmentRecordService recordService;
    @Mock private StudentAssessmentQuestionAnswerService questionAnswerService;
    @Mock private ManagedObjectCleanupService objectCleanupService;
    @Mock private HttpUtils httpUtils;

    @InjectMocks private StudentAppService studentAppService;
    @InjectMocks private TeacherAppService teacherAppService;

    @Test
    void omittedPasswordsAreValidForStudentAndTeacherProfileEdits() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(studentCommand()).stream()
                    .filter(violation -> violation.getPropertyPath().toString().equals("password")))
                    .isEmpty();
            assertThat(validator.validate(teacherCommand()).stream()
                    .filter(violation -> violation.getPropertyPath().toString().equals("password")))
                    .isEmpty();
        }
    }

    @Test
    void studentProfileEditExcludesPasswordFromTheUpdatePatch() {
        when(userService.getById(7)).thenReturn(UserPO.builder()
                .id(7).type(0).schoolClass("Class 1").password("stored-student-hash").build());
        when(userService.updateById(any(UserPO.class))).thenReturn(true);

        studentAppService.modifyStudent(studentCommand());

        ArgumentCaptor<UserPO> updated = ArgumentCaptor.forClass(UserPO.class);
        verify(userService).updateById(updated.capture());
        // Do not write a read-before-update snapshot over a concurrent password change.
        // Actual mapper SQL preservation is covered by the owned native database regression.
        assertThat(updated.getValue().getPassword()).isNull();
        verify(passwordSecurityService, never()).encode(any());
    }

    @Test
    void teacherProfileEditExcludesPasswordFromTheUpdatePatch() {
        when(userService.getById(8)).thenReturn(UserPO.builder()
                .id(8).type(1).password("stored-teacher-hash").build());
        when(userService.updateById(any(UserPO.class))).thenReturn(true);

        teacherAppService.modifyStudent(teacherCommand());

        ArgumentCaptor<UserPO> updated = ArgumentCaptor.forClass(UserPO.class);
        verify(userService).updateById(updated.capture());
        assertThat(updated.getValue().getPassword()).isNull();
        verify(passwordSecurityService, never()).encode(any());
    }

    private StudentModifyCmd studentCommand() {
        StudentModifyCmd command = new StudentModifyCmd();
        command.setId(7);
        command.setName("Student");
        command.setAccount("student-7");
        command.setSchoolClass("Class 1");
        return command;
    }

    private TeacherModifyCmd teacherCommand() {
        TeacherModifyCmd command = new TeacherModifyCmd();
        command.setId(8);
        command.setName("Teacher");
        command.setAccount("teacher-8");
        command.setPhone("13800000000");
        return command;
    }
}
