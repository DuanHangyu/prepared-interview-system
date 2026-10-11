package system.assessment.defense.application.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
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
import system.assessment.defense.application.dto.backed.StudentRecordExcelQuery;
import system.assessment.defense.domain.entity.TeacherSummary;
import system.assessment.defense.infrastructure.common.ExcelUtils;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudyRecordExportScopeTest {
    @org.mockito.Mock
    private system.assessment.defense.prepared.processing.PreparedRecordBridge preparedRecords;

    @BeforeAll
    static void initializeMyBatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "study-record-export-scope-test"),
                StudentAssessmentRecordPO.class);
    }

    @Mock private StudentAssessmentRecordService recordService;
    @Mock private StudentAssessmentQuestionAnswerService questionAnswerService;
    @Mock private UserService userService;
    @Mock private AssessmentSettingService assessmentSettingService;
    @Mock private ResourceAccessControlService accessControlService;
    @InjectMocks private StudyRecordService studyRecordService;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void themeExportQueriesOnlyAssessmentsManagedByTheCurrentTeacher() throws IOException {
        TeacherSummary summary = new TeacherSummary();
        summary.setId(7);
        summary.setStudentIds(List.of(20));
        summary.setManagedAssessmentIds(List.of(10));
        when(userService.findTeacherSummaryById(7)).thenReturn(Optional.of(summary));
        when(assessmentSettingService.list(any(Wrapper.class))).thenReturn(List.of(
                AssessmentSettingPO.builder().id(10).theme("Common").build(),
                AssessmentSettingPO.builder().id(11).theme("Common").build()));
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of());
        when(questionAnswerService.list(any(Wrapper.class))).thenReturn(List.of());
        when(userService.listByIds(any(Collection.class))).thenReturn(List.of());

        StudentRecordExcelQuery query = new StudentRecordExcelQuery();
        query.setTheme("Common");
        try (MockedStatic<ExcelUtils> ignored = mockStatic(ExcelUtils.class)) {
            studyRecordService.exportRecordExcel(7, query);
        }

        ArgumentCaptor<Wrapper<StudentAssessmentRecordPO>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(recordService).list(wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment()).contains("LIMIT 1001");
        Collection<Object> parameters = ((AbstractWrapper<?, ?, ?>) wrapper.getValue())
                .getParamNameValuePairs()
                .values();
        assertThat(parameters).contains(10).doesNotContain(11);
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedStoredEvaluationIsReportedAsAStableBusinessError() {
        StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                .id(31)
                .assessmentId(10)
                .studentId(20)
                .state(1)
                .defenseResult("{not-json")
                .build();
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(record));
        when(assessmentSettingService.listByIds(any(Collection.class))).thenReturn(List.of(
                AssessmentSettingPO.builder().id(10).theme("Common").totalScore(100).build()));
        when(questionAnswerService.list(any(Wrapper.class))).thenReturn(List.of());
        when(userService.listByIds(any(Collection.class))).thenReturn(List.of(
                UserPO.builder().id(20).name("Student").account("student-20").build()));

        StudentRecordExcelQuery query = new StudentRecordExcelQuery();
        query.setRecordIds(List.of(31));
        try (MockedStatic<ExcelUtils> ignored = mockStatic(ExcelUtils.class)) {
            assertThatThrownBy(() -> studyRecordService.exportRecordExcel(7, query))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCodeEnums.ANALYSIS_CHECK_FAIL.getCode());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void themeExportRejectsMoreThanTheSafeBatchSize() {
        TeacherSummary summary = new TeacherSummary();
        summary.setId(7);
        summary.setManagedAssessmentIds(List.of(10));
        when(userService.findTeacherSummaryById(7)).thenReturn(Optional.of(summary));
        when(assessmentSettingService.list(any(Wrapper.class))).thenReturn(List.of(
                AssessmentSettingPO.builder().id(10).theme("Common").build()));
        when(recordService.list(any(Wrapper.class))).thenReturn(IntStream.rangeClosed(1, 1_001)
                .mapToObj(id -> StudentAssessmentRecordPO.builder()
                        .id(id).assessmentId(10).studentId(id).state(1).build())
                .toList());

        StudentRecordExcelQuery query = new StudentRecordExcelQuery();
        query.setTheme("Common");

        assertThatThrownBy(() -> studyRecordService.exportRecordExcel(7, query))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1000");
    }
}
