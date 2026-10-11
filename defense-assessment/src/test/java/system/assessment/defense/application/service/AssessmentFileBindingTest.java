package system.assessment.defense.application.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import system.assessment.defense.application.dto.AssessmentSettingsCreateCmd;
import system.assessment.defense.application.dto.AssessmentSettingsModifyCmd;
import system.assessment.defense.application.dto.AssessmentSimpleDTO;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.mapper.AssessmentSettingMapper;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentAppointmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentInviteService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSchoolClassService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentStudentRelationService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssessmentFileBindingTest {

    private static final String OWNED_KEY =
            "defense-assessment-new/users/7/2026/10/03/0123456789abcdef0123456789abcdef.pdf";
    private static final String FOREIGN_KEY =
            "defense-assessment-new/users/99/2026/10/03/0123456789abcdef0123456789abcdef.pdf";

    @Mock private UserService userService;
    @Mock private AssessmentSettingService assessmentSettingService;
    @Mock private AssessmentSettingMapper assessmentSettingMapper;
    @Mock private StudentAssessmentRecordService recordService;
    @Mock private StudentAssessmentQuestionAnswerService questionAnswerService;
    @Mock private AssessmentStudentRelationService relationService;
    @Mock private AssessmentSchoolClassService schoolClassService;
    @Mock private AssessmentInviteService inviteService;
    @Mock private AssessmentAppointmentSettingService appointmentSettingService;
    @Mock private StudentAssessmentAppointmentService studentAppointmentService;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private ResourceAccessControlService accessControlService;
    @Mock private ManagedObjectCleanupService objectCleanupService;
    @Mock private FileAccessControlService fileAccessControlService;

    @InjectMocks private AssessmentAppService assessmentAppService;

    @Test
    void createRejectsForeignMaterialBeforePersistingAssessment() {
        AssessmentSettingsCreateCmd command = createCommand(FOREIGN_KEY, false);
        doThrow(new BusinessException(ErrorCodeEnums.NO_PERMISSION))
                .when(fileAccessControlService).requireOwnedUpload(7, FOREIGN_KEY);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);

            assertThatThrownBy(() -> assessmentAppService.createAssessmentSettings(command))
                    .isInstanceOf(BusinessException.class);
        }

        verify(assessmentSettingService, never()).save(any(AssessmentSettingPO.class));
    }

    @Test
    void createStoresOnlyTheNarrowClientFileReference() {
        AssessmentSettingsCreateCmd command = createCommand(OWNED_KEY, true);
        doAnswer(invocation -> {
            AssessmentSettingPO setting = invocation.getArgument(0);
            setting.setId(21);
            return true;
        }).when(assessmentSettingService).save(any(AssessmentSettingPO.class));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);

            assessmentAppService.createAssessmentSettings(command);
        }

        ArgumentCaptor<AssessmentSettingPO> setting = ArgumentCaptor.forClass(AssessmentSettingPO.class);
        verify(assessmentSettingService).save(setting.capture());
        assertThat(setting.getValue().getAssessmentFiles())
                .contains(OWNED_KEY, "design.pdf")
                .doesNotContain("attacker supplied parsed text", "presignedUrl", "materialStatus");
        verify(fileAccessControlService).requireOwnedUpload(7, OWNED_KEY);
    }

    @Test
    void modifyValidatesAllMaterialsBeforeRemovingExistingRelations() {
        AssessmentSettingsModifyCmd command = modifyCommand(FOREIGN_KEY);
        when(assessmentSettingService.getById(21)).thenReturn(
                AssessmentSettingPO.builder().id(21).assessmentFiles("[]").build());
        doThrow(new BusinessException(ErrorCodeEnums.NO_PERMISSION))
                .when(fileAccessControlService).requireAssessmentMaterialBinding(7, 21, FOREIGN_KEY);

        assertThatThrownBy(() -> assessmentAppService.modifyAssessmentSettings(7, command))
                .isInstanceOf(BusinessException.class);

        verify(relationService, never()).remove(any());
        verify(inviteService, never()).remove(any());
        verify(assessmentSettingService, never()).updateById(any(AssessmentSettingPO.class));
    }

    @Test
    void modifyQueuesAReplacedMaterialAfterTheDatabaseUpdateSucceeds() {
        String oldKey =
                "defense-assessment-new/users/7/2026/10/02/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.pdf";
        AssessmentSettingsModifyCmd command = modifyCommand(OWNED_KEY);
        when(assessmentSettingService.getById(21)).thenReturn(
                AssessmentSettingPO.builder()
                        .id(21)
                        .assessmentFiles("[{\"fileUrl\":\"" + oldKey + "\"}]")
                        .build());
        when(assessmentSettingService.updateById(any(AssessmentSettingPO.class))).thenReturn(true);
        when(objectCleanupService.extractFileReferences(any(String.class)))
                .thenReturn(List.of(oldKey), List.of(OWNED_KEY));

        assertThat(assessmentAppService.modifyAssessmentSettings(7, command)).isTrue();

        verify(objectCleanupService).queueRemovedReferences(
                List.of(oldKey), List.of(OWNED_KEY));
    }

    @Test
    void studentAssessmentDetailDoesNotTransmitArchivedDigitalHumanData() throws Exception {
        when(assessmentSettingService.findById(21)).thenReturn(
                java.util.Optional.of(AssessmentSettingPO.builder().id(21).theme("Architecture").build()));
        when(recordService.findByStudentIdAndAssessmentId(12, 21))
                .thenReturn(java.util.Optional.empty());

        AssessmentSimpleDTO detail = assessmentAppService.detail(12, 21);
        String responseJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(detail);

        assertThat(responseJson)
                .doesNotContain("digitalHuman")
                .doesNotContain("lipShape");
    }

    private AssessmentSettingsCreateCmd createCommand(String key, boolean includeServerFields) {
        String serverFields = includeServerFields
                ? ",\"presignedUrl\":\"https://evil.invalid/token\",\"materialStatus\":\"ready\",\"materialText\":\"attacker supplied parsed text\""
                : "";
        return JSONUtil.toBean("""
                {"theme":"Architecture","assessmentCriteria":"Correctness","duration":30,
                 "answerTime":60,"followUp":false,"assessmentFiles":[
                   {"fileUrl":"%s","fileName":"design.pdf","fileSize":"1024"%s}
                 ],"studentIds":[],"schoolClassList":[],"inviteTeachers":[]}
                """.formatted(key, serverFields), AssessmentSettingsCreateCmd.class);
    }

    private AssessmentSettingsModifyCmd modifyCommand(String key) {
        return JSONUtil.toBean("""
                {"id":21,"theme":"Architecture","assessmentCriteria":"Correctness","fullMarks":100,
                 "duration":30,"questionCount":5,"answerTime":60,"followUp":false,
                 "assessmentFiles":[{"fileUrl":"%s","fileName":"foreign.pdf","fileSize":"1024"}],
                 "studentIds":[],"schoolClassList":[],"inviteTeachers":[]}
                """.formatted(key), AssessmentSettingsModifyCmd.class);
    }
}
