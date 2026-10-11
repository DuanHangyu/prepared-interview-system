package system.assessment.defense.application.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
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
import system.assessment.defense.application.dto.backed.SchoolClassStudentSaveCmd;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.mapper.AssessmentSettingMapper;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentStudentRelationPO;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentAppointmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentInviteService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSchoolClassService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentStudentRelationService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssessmentParticipantValidationTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "assessment-participant-test"),
                UserPO.class);
    }

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
    void createRejectsTeacherIdUsedAsStudentBeforePersisting() {
        AssessmentSettingsCreateCmd command = new AssessmentSettingsCreateCmd();
        command.setStudentIds(List.of(11));
        when(userService.listByIds(any(Collection.class)))
                .thenReturn(List.of(UserPO.builder().id(11).type(1).build()));

        try (MockedStatic<StpUtil> stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);

            assertInvalidParticipant(() -> assessmentAppService.createAssessmentSettings(command));
        }

        verify(assessmentSettingService, never()).save(any(AssessmentSettingPO.class));
    }

    @Test
    void createRejectsSelfInvitationBeforePersisting() {
        AssessmentSettingsCreateCmd command = new AssessmentSettingsCreateCmd();
        command.setInviteTeachers(List.of(7));

        try (MockedStatic<StpUtil> stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);

            assertInvalidParticipant(() -> assessmentAppService.createAssessmentSettings(command));
        }

        verify(userService, never()).listByIds(any(Collection.class));
        verify(assessmentSettingService, never()).save(any(AssessmentSettingPO.class));
    }

    @Test
    void createRejectsDuplicateStudentReferencesBeforePersisting() {
        AssessmentSettingsCreateCmd command = new AssessmentSettingsCreateCmd();
        command.setStudentIds(List.of(11, 11));

        try (MockedStatic<StpUtil> stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);

            assertInvalidParticipant(() -> assessmentAppService.createAssessmentSettings(command));
        }

        verify(assessmentSettingService, never()).save(any(AssessmentSettingPO.class));
    }

    @Test
    void modifyRejectsStudentIdUsedAsInvitedTeacherBeforeRemovingRelations() {
        AssessmentSettingsModifyCmd command = new AssessmentSettingsModifyCmd();
        command.setId(21);
        command.setInviteTeachers(List.of(12));
        when(assessmentSettingService.getById(21))
                .thenReturn(AssessmentSettingPO.builder().id(21).build());
        when(userService.listByIds(any(Collection.class)))
                .thenReturn(List.of(UserPO.builder().id(12).type(0).build()));

        assertInvalidParticipant(() -> assessmentAppService.modifyAssessmentSettings(7, command));

        verify(relationService, never()).remove(any());
        verify(inviteService, never()).remove(any());
        verify(assessmentSettingService, never()).updateById(any(AssessmentSettingPO.class));
    }

    @Test
    void createRejectsMissingReferencedUsersBeforePersisting() {
        AssessmentSettingsCreateCmd command = new AssessmentSettingsCreateCmd();
        command.setStudentIds(List.of(11));
        when(userService.listByIds(any(Collection.class))).thenReturn(List.of());

        try (MockedStatic<StpUtil> stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);

            assertInvalidParticipant(() -> assessmentAppService.createAssessmentSettings(command));
        }

        verify(assessmentSettingService, never()).save(any(AssessmentSettingPO.class));
    }

    @Test
    void createRejectsAClassThatHasNoCurrentStudents() {
        AssessmentSettingsCreateCmd command = new AssessmentSettingsCreateCmd();
        command.setSchoolClassList(List.of(schoolClass("Class B", 11)));

        try (MockedStatic<StpUtil> stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);
            assertInvalidParticipant(() -> assessmentAppService.createAssessmentSettings(command));
        }

        verify(assessmentSettingService, never()).save(any(AssessmentSettingPO.class));
    }

    @Test
    void createResolvesTheCompleteClassRosterInsteadOfTrustingClientStudentIds() {
        AssessmentSettingsCreateCmd command = new AssessmentSettingsCreateCmd();
        command.setSchoolClassList(List.of(schoolClass("Class A", 11)));
        when(userService.list(any(Wrapper.class))).thenReturn(List.of(
                UserPO.builder().id(11).type(0).schoolClass("Class A").build(),
                UserPO.builder().id(12).type(0).schoolClass("Class A").build()));
        when(assessmentSettingService.save(any(AssessmentSettingPO.class))).thenAnswer(invocation -> {
            invocation.<AssessmentSettingPO>getArgument(0).setId(31);
            return true;
        });
        when(schoolClassService.saveBatch(any(Collection.class))).thenReturn(true);
        when(relationService.saveBatch(any(Collection.class))).thenReturn(true);

        try (MockedStatic<StpUtil> stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);
            assessmentAppService.createAssessmentSettings(command);
        }

        ArgumentCaptor<Collection<AssessmentStudentRelationPO>> relations =
                ArgumentCaptor.forClass(Collection.class);
        verify(relationService).saveBatch(relations.capture());
        assertThat(relations.getValue())
                .extracting(AssessmentStudentRelationPO::getStudentId)
                .containsExactlyInAnyOrder(11, 12);
    }

    @Test
    void createRollsBackWhenStudentRelationsCannotBePersisted() {
        AssessmentSettingsCreateCmd command = new AssessmentSettingsCreateCmd();
        command.setStudentIds(List.of(11));
        when(userService.listByIds(any(Collection.class)))
                .thenReturn(List.of(UserPO.builder().id(11).type(0).build()));
        when(assessmentSettingService.save(any(AssessmentSettingPO.class))).thenAnswer(invocation -> {
            invocation.<AssessmentSettingPO>getArgument(0).setId(31);
            return true;
        });
        when(relationService.saveBatch(any(Collection.class))).thenReturn(false);

        try (MockedStatic<StpUtil> stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);
            assertThatThrownBy(() -> assessmentAppService.createAssessmentSettings(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("学生关系");
        }
    }

    @Test
    void createRejectsDuplicateClassAssignmentsBeforePersisting() {
        AssessmentSettingsCreateCmd command = new AssessmentSettingsCreateCmd();
        command.setSchoolClassList(List.of(
                schoolClass("Class A", 11),
                schoolClass("Class A", 12)));

        try (MockedStatic<StpUtil> stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);
            assertInvalidParticipant(() -> assessmentAppService.createAssessmentSettings(command));
        }

        verify(userService, never()).listByIds(any(Collection.class));
        verify(assessmentSettingService, never()).save(any(AssessmentSettingPO.class));
    }

    private SchoolClassStudentSaveCmd schoolClass(String name, Integer studentId) {
        SchoolClassStudentSaveCmd schoolClass = new SchoolClassStudentSaveCmd();
        schoolClass.setSchoolClass(name);
        schoolClass.setStudents(List.of(studentId));
        return schoolClass;
    }

    private void assertInvalidParticipant(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(144);
    }
}
