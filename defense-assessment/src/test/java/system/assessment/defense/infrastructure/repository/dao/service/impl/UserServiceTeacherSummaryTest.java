package system.assessment.defense.infrastructure.repository.dao.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import system.assessment.defense.domain.entity.TeacherSummary;
import system.assessment.defense.infrastructure.repository.dao.mapper.AssessmentInviteMapper;
import system.assessment.defense.infrastructure.repository.dao.mapper.AssessmentSettingMapper;
import system.assessment.defense.infrastructure.repository.dao.mapper.AssessmentStudentRelationMapper;
import system.assessment.defense.infrastructure.repository.dao.mapper.StudentAssessmentAppointmentMapper;
import system.assessment.defense.infrastructure.repository.dao.mapper.StudentAssessmentRecordMapper;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentInvitePO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentStudentRelationPO;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class UserServiceTeacherSummaryTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        initialize(configuration, UserPO.class);
        initialize(configuration, AssessmentSettingPO.class);
        initialize(configuration, AssessmentInvitePO.class);
        initialize(configuration, AssessmentStudentRelationPO.class);
    }

    @Test
    void teacherSummaryIncludesOnlyOwnedOrInvitedAssessmentsAndTheirStudents() {
        AssessmentStudentRelationMapper relationMapper = mock(AssessmentStudentRelationMapper.class);
        AssessmentSettingMapper settingMapper = mock(AssessmentSettingMapper.class);
        AssessmentInviteMapper inviteMapper = mock(AssessmentInviteMapper.class);
        UserServiceImpl service = spy(newService(relationMapper, settingMapper, inviteMapper));
        doReturn(Optional.of(UserPO.builder().id(7).type(1).build()))
                .when(service).findById(7);
        when(settingMapper.selectList(any())).thenReturn(List.of(
                AssessmentSettingPO.builder().id(10).build(),
                AssessmentSettingPO.builder().id(12).build()));
        when(inviteMapper.selectList(any())).thenReturn(List.of(
                AssessmentInvitePO.builder().assessmentId(11).build(),
                AssessmentInvitePO.builder().assessmentId(12).build()));
        when(relationMapper.selectList(any())).thenReturn(List.of(
                AssessmentStudentRelationPO.builder().studentId(100).build(),
                AssessmentStudentRelationPO.builder().studentId(101).build(),
                AssessmentStudentRelationPO.builder().studentId(100).build()));

        TeacherSummary summary = service.findTeacherSummaryById(7).orElseThrow();

        assertThat(summary.getId()).isEqualTo(7);
        assertThat(summary.getType()).isEqualTo(1);
        assertThat(summary.getManagedAssessmentIds()).containsExactly(10, 12, 11);
        assertThat(summary.getStudentIds()).containsExactly(100, 101);
    }

    @Test
    @SuppressWarnings("unchecked")
    void administratorSummaryKeepsAccessToEveryStudentAndAssessment() {
        AssessmentStudentRelationMapper relationMapper = mock(AssessmentStudentRelationMapper.class);
        AssessmentSettingMapper settingMapper = mock(AssessmentSettingMapper.class);
        AssessmentInviteMapper inviteMapper = mock(AssessmentInviteMapper.class);
        UserServiceImpl service = spy(newService(relationMapper, settingMapper, inviteMapper));
        doReturn(Optional.of(UserPO.builder().id(2).type(2).build()))
                .when(service).findById(2);
        doReturn(List.of(
                UserPO.builder().id(100).type(0).build(),
                UserPO.builder().id(101).type(0).build()))
                .when(service).list(any(Wrapper.class));
        when(settingMapper.selectList(any())).thenReturn(List.of(
                AssessmentSettingPO.builder().id(10).build(),
                AssessmentSettingPO.builder().id(20).build()));

        TeacherSummary summary = service.findTeacherSummaryById(2).orElseThrow();

        assertThat(summary.getId()).isEqualTo(2);
        assertThat(summary.getType()).isEqualTo(2);
        assertThat(summary.getStudentIds()).containsExactly(100, 101);
        assertThat(summary.getManagedAssessmentIds()).containsExactly(10, 20);
    }

    private static UserServiceImpl newService(AssessmentStudentRelationMapper relationMapper,
                                              AssessmentSettingMapper settingMapper,
                                              AssessmentInviteMapper inviteMapper) {
        return new UserServiceImpl(
                mock(StudentAssessmentRecordMapper.class),
                relationMapper,
                mock(StudentAssessmentAppointmentMapper.class),
                mock(StudentAssessmentAppointmentMapper.class),
                settingMapper,
                inviteMapper);
    }

    private static void initialize(MybatisConfiguration configuration, Class<?> entityClass) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "teacher-summary-test-" + entityClass.getSimpleName()),
                entityClass);
    }
}
