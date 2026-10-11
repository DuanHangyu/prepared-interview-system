package system.assessment.defense.security;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import system.assessment.defense.application.service.FileAccessControlService;
import system.assessment.defense.application.service.FileSecurityService;
import system.assessment.defense.application.service.ManagedUploadService;
import system.assessment.defense.application.service.ResourceAccessControlService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FileAccessControlServiceTest {

    private UserService userService;
    private ResourceAccessControlService resourceAccess;
    private AssessmentSettingService settingService;
    private StudentAssessmentRecordService recordService;
    private StudentAssessmentQuestionAnswerService answerService;
    private FileSecurityService fileSecurityService;
    private FileAccessControlService fileAccess;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        resourceAccess = mock(ResourceAccessControlService.class);
        settingService = mock(AssessmentSettingService.class);
        recordService = mock(StudentAssessmentRecordService.class);
        answerService = mock(StudentAssessmentQuestionAnswerService.class);
        fileSecurityService = new FileSecurityService();
        fileAccess = new FileAccessControlService(
                userService, resourceAccess, settingService, recordService, answerService, fileSecurityService);
    }

    @Test
    void userOwnedObjectCanOnlyBeReadByOwnerOrAdmin() {
        String key = "defense-assessment-new/users/12/2026/10/02/0123456789abcdef0123456789abcdef.pdf";
        when(userService.findById(12)).thenReturn(Optional.of(user(12, 0)));
        when(userService.findById(13)).thenReturn(Optional.of(user(13, 0)));
        when(userService.findById(9)).thenReturn(Optional.of(user(9, 2)));

        assertThatCode(() -> fileAccess.requireReadAccess(12, key)).doesNotThrowAnyException();
        assertThatThrownBy(() -> fileAccess.requireReadAccess(13, key))
                .isInstanceOf(BusinessException.class);
        assertThatCode(() -> fileAccess.requireReadAccess(9, key)).doesNotThrowAnyException();
    }

    @Test
    void legacyAssessmentFileIsAvailableOnlyToItsManager() {
        String key = "defense-assessment-new/2025/08/10/0123456789abcdef0123456789abcdef.pdf";
        when(userService.findById(7)).thenReturn(Optional.of(user(7, 1)));
        when(settingService.list(any(Wrapper.class))).thenReturn(List.of(
                AssessmentSettingPO.builder().id(21).assessmentFiles("[{\"fileUrl\":\"" + key + "\"}]").build()));
        when(resourceAccess.isAssessmentManager(7, 21)).thenReturn(true);

        assertThatCode(() -> fileAccess.requireReadAccess(7, key)).doesNotThrowAnyException();

        when(resourceAccess.isAssessmentManager(7, 21)).thenReturn(false);
        assertThatThrownBy(() -> fileAccess.requireReadAccess(7, key))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void newBindingAcceptsOnlyTheActorsOwnUploadedObject() {
        String owned = "defense-assessment-new/users/7/2026/10/03/0123456789abcdef0123456789abcdef.pdf";
        String foreign = "defense-assessment-new/users/8/2026/10/03/0123456789abcdef0123456789abcdef.pdf";

        assertThatCode(() -> fileAccess.requireOwnedUpload(7, owned))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> fileAccess.requireOwnedUpload(7, foreign))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void newOwnedBindingConsumesItsPendingUploadRegistration() {
        String owned = "defense-assessment-new/users/7/2026/10/03/0123456789abcdef0123456789abcdef.pdf";
        ManagedUploadService managedUploadService = mock(ManagedUploadService.class);
        FileAccessControlService trackedAccess = new FileAccessControlService(
                userService, resourceAccess, settingService, recordService,
                answerService, fileSecurityService, managedUploadService);

        trackedAccess.requireOwnedUpload(7, owned);

        verify(managedUploadService).consumeForBinding(7, owned);
    }

    @Test
    void retainingAnExistingBusinessReferenceDoesNotRequireASecondUploadRegistration() {
        String owned = "defense-assessment-new/users/7/2026/10/03/0123456789abcdef0123456789abcdef.pdf";
        ManagedUploadService managedUploadService = mock(ManagedUploadService.class);
        FileAccessControlService trackedAccess = new FileAccessControlService(
                userService, resourceAccess, settingService, recordService,
                answerService, fileSecurityService, managedUploadService);
        when(settingService.getById(21)).thenReturn(AssessmentSettingPO.builder()
                .id(21)
                .assessmentFiles("[{\"fileUrl\":\"" + owned + "\"}]")
                .build());
        when(recordService.findByStudentIdAndAssessmentId(7, 21)).thenReturn(Optional.of(
                StudentAssessmentRecordPO.builder()
                        .studentId(7)
                        .assessmentId(21)
                        .defenseFile("{\"fileUrl\":\"" + owned + "\"}")
                        .build()));

        trackedAccess.requireAssessmentMaterialBinding(7, 21, owned);
        trackedAccess.requireStudentDefenseFileBinding(7, 21, owned);

        verifyNoInteractions(managedUploadService);
    }

    @Test
    void assessmentModificationMayRetainItsExistingFileButCannotLaunderAnotherObject() {
        String existing = "defense-assessment-new/users/8/2026/10/03/0123456789abcdef0123456789abcdef.pdf";
        String foreign = "defense-assessment-new/users/9/2026/10/03/0123456789abcdef0123456789abcdef.pdf";
        when(settingService.getById(21)).thenReturn(AssessmentSettingPO.builder()
                .id(21)
                .assessmentFiles("[{\"fileUrl\":\"" + existing + "\"}]")
                .build());
        when(resourceAccess.isAssessmentManager(8, 21)).thenReturn(true);

        assertThatCode(() -> fileAccess.requireAssessmentMaterialBinding(7, 21, existing))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> fileAccess.requireAssessmentMaterialBinding(7, 21, foreign))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void forgedStudentRecordReferenceCannotLaunderAnotherStudentsObject() {
        String ownersKey = "defense-assessment-new/users/12/2026/10/03/0123456789abcdef0123456789abcdef.pdf";
        when(userService.findById(13)).thenReturn(Optional.of(user(13, 0)));
        when(settingService.list(any(Wrapper.class))).thenReturn(List.of());
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(
                StudentAssessmentRecordPO.builder()
                        .assessmentId(21)
                        .studentId(13)
                        .defenseFile("{\"fileUrl\":\"" + ownersKey + "\"}")
                        .build()));
        when(answerService.list(any(Wrapper.class))).thenReturn(List.of());

        assertThatThrownBy(() -> fileAccess.requireReadAccess(13, ownersKey))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void assessmentManagerCanReadAnExactRecordReferenceOwnedByThatStudent() {
        String studentsKey = "defense-assessment-new/users/12/2026/10/03/0123456789abcdef0123456789abcdef.pdf";
        when(userService.findById(7)).thenReturn(Optional.of(user(7, 1)));
        when(settingService.list(any(Wrapper.class))).thenReturn(List.of());
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(
                StudentAssessmentRecordPO.builder()
                        .assessmentId(21)
                        .studentId(12)
                        .defenseFile("{\"fileUrl\":\"" + studentsKey + "\"}")
                        .build()));
        when(resourceAccess.isAssessmentManager(7, 21)).thenReturn(true);

        assertThatCode(() -> fileAccess.requireReadAccess(7, studentsKey))
                .doesNotThrowAnyException();
    }

    @Test
    void assessmentReadRequiresAnExactReferenceAndAValidMaterialOwner() {
        String key = "defense-assessment-new/users/7/2026/10/03/0123456789abcdef0123456789abcdef.pdf";
        when(userService.findById(12)).thenReturn(Optional.of(user(12, 0)));
        when(settingService.list(any(Wrapper.class))).thenReturn(List.of(
                AssessmentSettingPO.builder()
                        .id(21)
                        .assessmentFiles("[{\"fileUrl\":\"" + key + "-suffix\"}]")
                        .build()));
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of());
        when(answerService.list(any(Wrapper.class))).thenReturn(List.of());
        when(resourceAccess.isAssignedStudent(12, 21)).thenReturn(true);
        when(resourceAccess.isAssessmentManager(7, 21)).thenReturn(true);

        assertThatThrownBy(() -> fileAccess.requireReadAccess(12, key))
                .isInstanceOf(BusinessException.class);

        when(settingService.list(any(Wrapper.class))).thenReturn(List.of(
                AssessmentSettingPO.builder()
                        .id(21)
                        .assessmentFiles("[{\"fileUrl\":\"" + key + "\"}]")
                        .build()));
        assertThatCode(() -> fileAccess.requireReadAccess(12, key))
                .doesNotThrowAnyException();
    }

    @Test
    void answerReadRequiresExactReferenceAndMatchingStudentOwner() {
        String key = "defense-assessment-new/users/12/2026/10/03/0123456789abcdef0123456789abcdef.pdf";
        when(userService.findById(7)).thenReturn(Optional.of(user(7, 1)));
        when(settingService.list(any(Wrapper.class))).thenReturn(List.of());
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of());
        when(resourceAccess.isAssessmentManager(7, 21)).thenReturn(true);
        when(answerService.list(any(Wrapper.class))).thenReturn(List.of(
                StudentAssessmentQuestionAnswerPO.builder()
                        .assessmentId(21)
                        .studentId(12)
                        .answerVoice(key + "-suffix")
                        .build()));

        assertThatThrownBy(() -> fileAccess.requireReadAccess(7, key))
                .isInstanceOf(BusinessException.class);

        when(answerService.list(any(Wrapper.class))).thenReturn(List.of(
                StudentAssessmentQuestionAnswerPO.builder()
                        .assessmentId(21)
                        .studentId(12)
                        .answerVoice(key)
                        .build()));
        assertThatCode(() -> fileAccess.requireReadAccess(7, key))
                .doesNotThrowAnyException();
    }

    private UserPO user(int id, int type) {
        return UserPO.builder().id(id).type(type).build();
    }
}
