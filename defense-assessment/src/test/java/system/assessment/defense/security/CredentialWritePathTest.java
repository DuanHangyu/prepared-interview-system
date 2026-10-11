package system.assessment.defense.security;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import system.assessment.defense.application.dto.StudentCreateCmd;
import system.assessment.defense.application.dto.StudentExcelDTO;
import system.assessment.defense.application.dto.StudentModifyCmd;
import system.assessment.defense.application.dto.TeacherCreateCmd;
import system.assessment.defense.application.dto.TeacherModifyCmd;
import system.assessment.defense.application.manage.OpenAiManager;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.application.service.ManagedObjectCleanupService;
import system.assessment.defense.application.service.StudentAppService;
import system.assessment.defense.application.service.TeacherAppService;
import system.assessment.defense.infrastructure.common.HttpUtils;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentStudentRelationPO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSchoolClassPO;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSchoolClassService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentStudentRelationService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CredentialWritePathTest {

    @Mock private UserService userService;
    @Mock private AssessmentSettingService settingService;
    @Mock private StudentAssessmentAppointmentService appointmentService;
    @Mock private AssessmentStudentRelationService relationService;
    @Mock private AssessmentSchoolClassService schoolClassService;
    @Mock private StudentAssessmentRecordService recordService;
    @Mock private OpenAiManager openAiManager;
    @Mock private HttpUtils httpUtils;
    @Mock private StudentAssessmentQuestionAnswerService questionAnswerService;
    @Mock private ThreadPoolExecutor threadPoolExecutor;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private PasswordSecurityService passwordSecurityService;
    @Mock private ManagedObjectCleanupService objectCleanupService;

    @InjectMocks private StudentAppService studentAppService;
    @InjectMocks private TeacherAppService teacherAppService;

    private void stubPasswordEncoding() {
        when(passwordSecurityService.encode(any())).thenAnswer(invocation -> "$2-secure-" + invocation.getArgument(0));
    }

    @Test
    void creatingAndModifyingStudentNeverWritesRawPassword() {
        stubPasswordEncoding();
        when(userService.save(any(UserPO.class))).thenReturn(true);

        StudentCreateCmd create = new StudentCreateCmd();
        create.setName("Student");
        create.setAccount("s-1");
        create.setPassword("create-secret");
        create.setSchoolClass("Class 1");
        studentAppService.createStudent(create);

        ArgumentCaptor<UserPO> created = ArgumentCaptor.forClass(UserPO.class);
        verify(userService).save(created.capture());
        assertThat(created.getValue().getPassword()).isEqualTo("$2-secure-create-secret");

        StudentModifyCmd modify = new StudentModifyCmd();
        modify.setId(1);
        modify.setName("Student");
        modify.setAccount("s-1");
        modify.setPassword("modify-secret");
        modify.setSchoolClass("Class 1");
        when(userService.getById(1)).thenReturn(UserPO.builder().id(1).type(0).build());
        studentAppService.modifyStudent(modify);

        ArgumentCaptor<UserPO> modified = ArgumentCaptor.forClass(UserPO.class);
        verify(userService).updateById(modified.capture());
        assertThat(modified.getValue().getPassword()).isEqualTo("$2-secure-modify-secret");
    }

    @Test
    void importingStudentsHashesEveryPasswordBeforeBatchSave() {
        stubPasswordEncoding();
        when(userService.list(any(Wrapper.class))).thenReturn(List.of());
        StudentExcelDTO row = StudentExcelDTO.builder()
                .name("Imported")
                .account("imported-1")
                .password("import-secret")
                .schoolClass("Class 1")
                .build();

        studentAppService.importStudent(List.of(row));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserPO>> students = ArgumentCaptor.forClass(List.class);
        verify(userService).saveBatch(students.capture());
        assertThat(students.getValue())
                .extracting(UserPO::getPassword)
                .containsExactly("$2-secure-import-secret");
    }

    @Test
    void oversizedStudentImportIsRejectedBeforeHashingOrPersistence() {
        StudentExcelDTO row = StudentExcelDTO.builder()
                .name("Imported")
                .account("imported-1")
                .password("import-secret")
                .schoolClass("Class 1")
                .build();

        assertThatThrownBy(() -> studentAppService.importStudent(Collections.nCopies(1_001, row)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(400);

        verifyNoInteractions(userService);
        verify(passwordSecurityService, never()).encode(any());
    }

    @Test
    void duplicateAccountsInsideStudentImportAreRejectedBeforeHashingOrPersistence() {
        StudentExcelDTO first = StudentExcelDTO.builder()
                .name("First")
                .account("duplicate-account")
                .password("first-secret")
                .schoolClass("Class 1")
                .build();
        StudentExcelDTO second = StudentExcelDTO.builder()
                .name("Second")
                .account("duplicate-account")
                .password("second-secret")
                .schoolClass("Class 1")
                .build();

        assertThatThrownBy(() -> studentAppService.importStudent(List.of(first, second)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCodeEnums.EXIST_STUDENT.getCode());

        verifyNoInteractions(userService);
        verify(passwordSecurityService, never()).encode(any());
    }

    @Test
    void databaseOversizedStudentImportFieldsAreRejectedBeforePersistence() {
        List<StudentExcelDTO> invalidRows = List.of(
                importedRow("x".repeat(256), "account", "Class 1"),
                importedRow("Imported", "x".repeat(256), "Class 1"),
                importedRow("Imported", "account", "x".repeat(256))
        );

        for (StudentExcelDTO row : invalidRows) {
            assertThatThrownBy(() -> studentAppService.importStudent(List.of(row)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(400);
        }

        verifyNoInteractions(userService);
        verify(passwordSecurityService, never()).encode(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void importedStudentRelationsArePersistedBeforeTheTransactionReturns() {
        stubPasswordEncoding();
        StudentExcelDTO row = StudentExcelDTO.builder()
                .name("Imported")
                .account("imported-1")
                .password("import-secret")
                .schoolClass("Class 1")
                .build();
        when(userService.list(any(Wrapper.class))).thenReturn(List.of());
        doAnswer(invocation -> {
            List<UserPO> saved = invocation.getArgument(0);
            saved.getFirst().setId(20);
            return true;
        }).when(userService).saveBatch(any());
        when(schoolClassService.list(any(Wrapper.class))).thenReturn(List.of(
                AssessmentSchoolClassPO.builder()
                        .assessmentId(10)
                        .schoolClass("Class 1")
                        .build()));
        when(relationService.saveBatch(any())).thenReturn(true);

        studentAppService.importStudent(List.of(row));

        ArgumentCaptor<List<AssessmentStudentRelationPO>> relations = ArgumentCaptor.forClass(List.class);
        verify(relationService).saveBatch(relations.capture());
        assertThat(relations.getValue())
                .extracting(AssessmentStudentRelationPO::getAssessmentId,
                        AssessmentStudentRelationPO::getStudentId,
                        AssessmentStudentRelationPO::getRelationType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(10, 20, 1));
        verify(threadPoolExecutor, never()).execute(any());
    }

    @Test
    void studentImportRollsBackWhenClassRelationsCannotBePersisted() {
        stubPasswordEncoding();
        StudentExcelDTO row = StudentExcelDTO.builder()
                .name("Imported")
                .account("imported-1")
                .password("import-secret")
                .schoolClass("Class 1")
                .build();
        when(userService.list(any(Wrapper.class))).thenReturn(List.of());
        doAnswer(invocation -> {
            List<UserPO> saved = invocation.getArgument(0);
            saved.getFirst().setId(20);
            return true;
        }).when(userService).saveBatch(any());
        when(schoolClassService.list(any(Wrapper.class))).thenReturn(List.of(
                AssessmentSchoolClassPO.builder()
                        .assessmentId(10)
                        .schoolClass("Class 1")
                        .build()));
        when(relationService.saveBatch(any())).thenReturn(false);

        assertThatThrownBy(() -> studentAppService.importStudent(List.of(row)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("学生考核关系");
    }

    @Test
    @SuppressWarnings("unchecked")
    void modifyingAStudentsClassReconcilesMaterializedClassAssignments() {
        stubPasswordEncoding();
        when(userService.getById(20)).thenReturn(UserPO.builder()
                .id(20)
                .type(0)
                .schoolClass("Old Class")
                .build());
        when(userService.updateById(any(UserPO.class))).thenReturn(true);
        when(schoolClassService.list(any(Wrapper.class))).thenReturn(List.of(
                AssessmentSchoolClassPO.builder()
                        .assessmentId(10)
                        .schoolClass("New Class")
                        .build()));
        when(relationService.list(any(Wrapper.class))).thenReturn(List.of());
        when(relationService.saveBatch(any())).thenReturn(true);
        StudentModifyCmd modify = new StudentModifyCmd();
        modify.setId(20);
        modify.setName("Student");
        modify.setAccount("student-20");
        modify.setPassword("modify-secret");
        modify.setSchoolClass("New Class");

        studentAppService.modifyStudent(modify);

        verify(relationService).remove(any(Wrapper.class));
        ArgumentCaptor<List<AssessmentStudentRelationPO>> relations = ArgumentCaptor.forClass(List.class);
        verify(relationService).saveBatch(relations.capture());
        assertThat(relations.getValue())
                .extracting(AssessmentStudentRelationPO::getAssessmentId,
                        AssessmentStudentRelationPO::getStudentId,
                        AssessmentStudentRelationPO::getRelationType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(10, 20, 1));
    }

    @Test
    void creatingAndModifyingTeacherNeverWritesRawPassword() {
        stubPasswordEncoding();
        TeacherCreateCmd create = new TeacherCreateCmd();
        create.setName("Teacher");
        create.setAccount("t-1");
        create.setPassword("create-secret");
        create.setPhone("10086");
        teacherAppService.createStudent(create);

        ArgumentCaptor<UserPO> created = ArgumentCaptor.forClass(UserPO.class);
        verify(userService).save(created.capture());
        assertThat(created.getValue().getPassword()).isEqualTo("$2-secure-create-secret");

        TeacherModifyCmd modify = new TeacherModifyCmd();
        modify.setId(2);
        modify.setName("Teacher");
        modify.setAccount("t-1");
        modify.setPassword("modify-secret");
        modify.setPhone("10086");
        when(userService.getById(2)).thenReturn(UserPO.builder().id(2).type(1).build());
        teacherAppService.modifyStudent(modify);

        ArgumentCaptor<UserPO> modified = ArgumentCaptor.forClass(UserPO.class);
        verify(userService).updateById(modified.capture());
        assertThat(modified.getValue().getPassword()).isEqualTo("$2-secure-modify-secret");
    }

    private StudentExcelDTO importedRow(String name, String account, String schoolClass) {
        return StudentExcelDTO.builder()
                .name(name)
                .account(account)
                .password("import-secret")
                .schoolClass(schoolClass)
                .build();
    }
}
