package system.assessment.defense.application.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import system.assessment.defense.application.dto.StudyRecordDTO;
import system.assessment.defense.application.dto.StudyRecordPagingQuery;
import system.assessment.defense.domain.entity.TeacherSummary;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;
import system.assessment.defense.prepared.processing.PreparedRecordBridge;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudyRecordVisibilityTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "study-record-visibility-record-test"),
                StudentAssessmentRecordPO.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "study-record-visibility-user-test"),
                UserPO.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "study-record-visibility-assessment-test"),
                AssessmentSettingPO.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void teacherQueryIntersectsManagedAssessmentsStudentsAndSearchFilters() {
        StudentAssessmentRecordService recordService = mock(StudentAssessmentRecordService.class);
        UserService userService = mock(UserService.class);
        AssessmentSettingService settingService = mock(AssessmentSettingService.class);
        StudyRecordService service = newService(recordService, userService, settingService);
        TeacherSummary summary = new TeacherSummary();
        summary.setId(7);
        summary.setType(1);
        summary.setStudentIds(List.of(101, 102));
        summary.setManagedAssessmentIds(List.of(10));
        when(userService.findTeacherSummaryById(7)).thenReturn(Optional.of(summary));
        when(userService.list(any(Wrapper.class))).thenReturn(List.of(
                UserPO.builder().id(101).name("Alice").type(0).build()));
        when(settingService.list(any(Wrapper.class))).thenReturn(List.of(
                AssessmentSettingPO.builder().id(10).theme("Java").build()));
        when(recordService.page(any(IPage.class), any(Wrapper.class)))
                .thenReturn(new Page<StudentAssessmentRecordPO>(2, 25));
        StudyRecordPagingQuery query = new StudyRecordPagingQuery();
        query.setPage(2);
        query.setSize(25);
        query.setStudentName("Alice");
        query.setTheme("Java");

        IPage<StudyRecordDTO> result = service.recordList(7, query);

        assertThat(result.getCurrent()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(25);
        ArgumentCaptor<Wrapper<UserPO>> userSearch = ArgumentCaptor.forClass(Wrapper.class);
        verify(userService).list(userSearch.capture());
        assertThat(userSearch.getValue().getSqlSegment()).contains("id IN");
        assertThat(((AbstractWrapper<?, ?, ?>) userSearch.getValue())
                .getParamNameValuePairs().values()).contains(101, 102);

        ArgumentCaptor<Wrapper<AssessmentSettingPO>> assessmentSearch =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(settingService).list(assessmentSearch.capture());
        assertThat(assessmentSearch.getValue().getSqlSegment()).contains("id IN");
        assertThat(((AbstractWrapper<?, ?, ?>) assessmentSearch.getValue())
                .getParamNameValuePairs().values()).contains(10);

        ArgumentCaptor<Wrapper<StudentAssessmentRecordPO>> wrapperCaptor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(recordService).page(any(IPage.class), wrapperCaptor.capture());
        Wrapper<StudentAssessmentRecordPO> wrapper = wrapperCaptor.getValue();
        AbstractWrapper<?, ?, ?> abstractWrapper = (AbstractWrapper<?, ?, ?>) wrapper;
        String sql = wrapper.getSqlSegment();
        assertThat(count(sql, "student_id IN")).isEqualTo(2);
        assertThat(count(sql, "assessment_id IN")).isEqualTo(2);
        assertThat(sql).contains("state").contains("ORDER BY").contains("update_time");
        assertThat(abstractWrapper.getParamNameValuePairs().values())
                .contains(101, 102, 10);
        assertThat(abstractWrapper.getParamNameValuePairs().values().stream()
                .filter(value -> Integer.valueOf(10).equals(value)))
                .hasSize(2);
    }

    @Test
    void emptyManagedAssessmentScopeReturnsEmptyPageWithoutQueryingRecords() {
        StudentAssessmentRecordService recordService = mock(StudentAssessmentRecordService.class);
        UserService userService = mock(UserService.class);
        StudyRecordService service = newService(
                recordService,
                userService,
                mock(AssessmentSettingService.class));
        TeacherSummary summary = new TeacherSummary();
        summary.setId(7);
        summary.setType(1);
        summary.setStudentIds(List.of(101));
        summary.setManagedAssessmentIds(List.<Integer>of());
        when(userService.findTeacherSummaryById(7)).thenReturn(Optional.of(summary));
        StudyRecordPagingQuery query = new StudyRecordPagingQuery();
        query.setPage(1);
        query.setSize(20);

        IPage<StudyRecordDTO> result = service.recordList(7, query);

        assertThat(result.getRecords()).isEmpty();
        verify(recordService, never()).page(any(), any());
    }

    private static StudyRecordService newService(StudentAssessmentRecordService recordService,
                                                 UserService userService,
                                                 AssessmentSettingService settingService) {
        return new StudyRecordService(
                recordService,
                mock(StudentAssessmentQuestionAnswerService.class),
                userService,
                settingService,
                mock(ResourceAccessControlService.class), mock(PreparedRecordBridge.class));
    }

    private static long count(String value, String fragment) {
        return value.lines()
                .flatMap(line -> java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(fragment))
                        .matcher(line).results())
                .count();
    }
}
