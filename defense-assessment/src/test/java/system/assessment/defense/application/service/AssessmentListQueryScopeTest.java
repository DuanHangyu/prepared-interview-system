package system.assessment.defense.application.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import system.assessment.defense.application.dto.AssessmentSettingPagingQuery;
import system.assessment.defense.application.dto.AssessmentSettingsCreateCmd;
import system.assessment.defense.application.dto.backed.SchoolClassStudentSaveCmd;
import system.assessment.defense.infrastructure.repository.dao.mapper.AssessmentSettingMapper;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentInvitePO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSchoolClassPO;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssessmentListQueryScopeTest {

    @BeforeAll
    static void initializeTableMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "assessment-list-query-scope-test");
        TableInfoHelper.initTableInfo(assistant, AssessmentSettingPO.class);
        TableInfoHelper.initTableInfo(assistant, UserPO.class);
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
    @SuppressWarnings("unchecked")
    void creatingAssessmentPersistsTheClassAssignmentSource() {
        SchoolClassStudentSaveCmd schoolClass = new SchoolClassStudentSaveCmd();
        schoolClass.setSchoolClass("Class 1");
        schoolClass.setStudents(List.of(11, 12));
        AssessmentSettingsCreateCmd command = new AssessmentSettingsCreateCmd();
        command.setSchoolClassList(List.of(schoolClass));
        when(userService.list(any(Wrapper.class))).thenReturn(List.of(
                UserPO.builder().id(11).type(0).schoolClass("Class 1").build(),
                UserPO.builder().id(12).type(0).schoolClass("Class 1").build()));
        doAnswer(invocation -> {
            AssessmentSettingPO setting = invocation.getArgument(0);
            setting.setId(101);
            return true;
        }).when(assessmentSettingService).save(any(AssessmentSettingPO.class));
        when(schoolClassService.saveBatch(any())).thenReturn(true);
        when(relationService.saveBatch(any())).thenReturn(true);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);
            assessmentAppService.createAssessmentSettings(command);
        }

        ArgumentCaptor<List<AssessmentSchoolClassPO>> assignments = ArgumentCaptor.forClass(List.class);
        verify(schoolClassService).saveBatch(assignments.capture());
        assertThat(assignments.getValue())
                .extracting(AssessmentSchoolClassPO::getAssessmentId,
                        AssessmentSchoolClassPO::getSchoolClass)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(101, "Class 1"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listLoadsOnlyUsersReferencedByTheCurrentPage() throws Exception {
        AssessmentSettingPO setting = AssessmentSettingPO.builder().id(101).theme("安全答辩").build();
        Page<AssessmentSettingPO> page = new Page<>(1, 20);
        page.setRecords(List.of(setting));
        page.setTotal(1);

        AssessmentStudentRelationPO relation = AssessmentStudentRelationPO.builder()
                .assessmentId(101).studentId(11).relationType(0).build();
        AssessmentInvitePO invite = AssessmentInvitePO.builder()
                .assessmentId(101).inviteeTeacherId(12).build();
        UserPO student = UserPO.builder().id(11).name("学生").schoolClass("一班").build();
        UserPO teacher = UserPO.builder().id(12).name("教师").type(1).build();

        when(userService.getById(1)).thenReturn(UserPO.builder().id(1).type(2).build());
        when(assessmentSettingService.page(any(Page.class), any())).thenReturn(page);
        when(relationService.list(any(Wrapper.class))).thenReturn(List.of(relation));
        when(inviteService.list(any(Wrapper.class))).thenReturn(List.of(invite));
        when(appointmentSettingService.list(any(Wrapper.class))).thenReturn(List.of());
        when(userService.listByIds(any(Collection.class))).thenReturn(List.of(student, teacher));

        var result = assessmentAppService.list(1, new AssessmentSettingPagingQuery());

        assertThat(result.getRecords()).hasSize(1);
        ArgumentCaptor<Collection<Integer>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(userService).listByIds(ids.capture());
        assertThat(Set.copyOf(ids.getValue())).containsExactlyInAnyOrder(11, 12);
        verify(userService, never()).list();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listSkipsStaleUserReferencesAndLegacyNullRelationTypes() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder().id(101).theme("安全答辩").build();
        Page<AssessmentSettingPO> page = new Page<>(1, 20);
        page.setRecords(List.of(setting));
        page.setTotal(1);

        AssessmentStudentRelationPO missingStudent = AssessmentStudentRelationPO.builder()
                .assessmentId(101).studentId(11).relationType(0).build();
        AssessmentStudentRelationPO legacyRelation = AssessmentStudentRelationPO.builder()
                .assessmentId(101).studentId(13).relationType(null).build();
        AssessmentInvitePO missingTeacher = AssessmentInvitePO.builder()
                .assessmentId(101).inviteeTeacherId(12).build();

        when(userService.getById(1)).thenReturn(UserPO.builder().id(1).type(2).build());
        when(assessmentSettingService.page(any(Page.class), any())).thenReturn(page);
        when(relationService.list(any(Wrapper.class))).thenReturn(List.of(missingStudent, legacyRelation));
        when(inviteService.list(any(Wrapper.class))).thenReturn(List.of(missingTeacher));
        when(appointmentSettingService.list(any(Wrapper.class))).thenReturn(List.of());
        when(userService.listByIds(any(Collection.class))).thenReturn(List.of());

        var result = assessmentAppService.list(1, new AssessmentSettingPagingQuery());

        assertThat(result.getRecords()).singleElement().satisfies(dto -> {
            assertThat(dto.getInviteTeachers()).isEmpty();
            assertThat(dto.getStudents()).isEmpty();
            assertThat(dto.getSchoolClassList()).isEmpty();
            assertThat(dto.getParticipatingStudents()).isEmpty();
        });
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void invitedAssessmentSearchAppliesTheThemeToEveryVisibleAssessment() {
        AssessmentInvitePO invite = AssessmentInvitePO.builder()
                .assessmentId(101).inviteeTeacherId(7).build();
        Page<AssessmentSettingPO> emptyPage = new Page<>(1, 20);
        emptyPage.setRecords(List.of());

        when(userService.getById(7)).thenReturn(UserPO.builder().id(7).type(1).build());
        when(inviteService.list(any(Wrapper.class))).thenReturn(List.of(invite));
        when(assessmentSettingService.page(any(Page.class), any())).thenReturn(emptyPage);

        AssessmentSettingPagingQuery query = new AssessmentSettingPagingQuery();
        query.setTheme("安全");
        assessmentAppService.list(7, query);

        ArgumentCaptor<Wrapper<AssessmentSettingPO>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(assessmentSettingService).page(any(Page.class), wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sql).contains("create_id", "id IN", "theme LIKE");
        assertThat(sql.indexOf(" OR "))
                .as("visibility must be grouped before the shared theme filter: %s", sql)
                .isLessThan(sql.indexOf("theme LIKE"));
    }
}
