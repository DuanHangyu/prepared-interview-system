package system.assessment.defense.prepared.processing;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.publication.PreparedPublication;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit internal contract doubles for defensive branches unreachable through a
 * correctly verified submission. Never real permission, database or cloud evidence. */
class PreparedReviewSummaryGuardTest {
    final String attempt="11111111-1111-4111-8111-111111111111";
    PreparedSubmissionService submissions;PreparedProcessingService processing;JdbcTemplate jdbc;
    PreparedAttemptStart.Summary scope;PreparedPublication.Snapshot paper;PreparedReviewSummaryService service;
    @BeforeEach void setup()throws Exception{
        submissions=mock(PreparedSubmissionService.class);processing=mock(PreparedProcessingService.class);jdbc=mock(JdbcTemplate.class);scope=mock(PreparedAttemptStart.Summary.class);paper=mock(PreparedPublication.Snapshot.class);
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());service=new PreparedReviewSummaryService(submissions,processing,jdbc,manager);
        var rs=mock(ResultSet.class);when(rs.getInt("student_id")).thenReturn(8);when(rs.getString("name")).thenReturn("明确标记的边界替身");
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),eq(21),eq(attempt))).thenAnswer(invocation->List.of(invocation.<RowMapper<Object>>getArgument(1).mapRow(rs,0)));
        when(scope.recordId()).thenReturn(9);when(scope.attemptNo()).thenReturn(1);when(scope.state()).thenReturn("SUBMITTED");when(scope.paperVersionId()).thenReturn(attempt);
        when(paper.rules()).thenReturn(new PreparedPublication.Rules("冻结规则替身",10,6,1,false));when(paper.questions()).thenReturn(List.of(mock(PreparedPublication.Question.class)));
        when(jdbc.queryForList(anyString(),eq(String.class),eq(21),eq(8),eq(9))).thenReturn(List.of(attempt));
        when(jdbc.queryForObject(contains("show_result"),eq(Boolean.class),eq(21))).thenReturn(false);
        sources(List.of(mock(PreparedSubmissionService.ProcessingSource.class)));
    }
    void sources(List<PreparedSubmissionService.ProcessingSource> values){when(submissions.reviewSources(7,8,21,attempt)).thenReturn(new PreparedSubmissionService.ReviewSources(scope,paper,values));}
    void reject(String message){assertThatThrownBy(()->service.summary(7,21,attempt)).isInstanceOf(IllegalStateException.class).hasMessageContaining(message);verify(jdbc,never()).update(anyString(),any(Object[].class));}
    @Test void invalidOriginalAnchorCardinalityOrNullNeverBecomesCurrentHistory(){
        when(jdbc.queryForList(anyString(),eq(String.class),eq(21),eq(8),eq(9))).thenReturn(List.of());reject("锚点");
        when(jdbc.queryForList(anyString(),eq(String.class),eq(21),eq(8),eq(9))).thenReturn(Arrays.asList((String)null));reject("锚点");
    }
    @Test void unknownOrContradictoryUnsubmittedStatesNeverFabricateTotals(){
        sources(List.of());when(scope.state()).thenReturn("BROKEN");reject("状态");
        when(scope.state()).thenReturn("IN_PROGRESS");when(jdbc.queryForObject(contains("first_results"),eq(Timestamp.class),eq(attempt))).thenReturn(Timestamp.from(java.time.Instant.parse("2026-10-01T00:00:00Z")));reject("状态");
        when(jdbc.queryForObject(contains("first_results"),eq(Timestamp.class),eq(attempt))).thenReturn(null);sources(List.of(mock(PreparedSubmissionService.ProcessingSource.class)));reject("状态");
    }
    @Test void missingOversizedOrMismatchedOriginalSubmissionsNeverProducePartialTotal(){
        sources(List.of());reject("题数");
        sources(Collections.nCopies(51,mock(PreparedSubmissionService.ProcessingSource.class)));reject("题数");
        sources(Collections.nCopies(2,mock(PreparedSubmissionService.ProcessingSource.class)));reject("题数");
    }
    @Test void completeEffectiveHeadCannotReplaceTheFrozenTotalScore(){
        var grade=mock(PreparedProcessingService.StudentGrade.class);when(grade.answerId()).thenReturn(attempt);when(grade.score()).thenReturn(BigDecimal.ZERO);when(grade.maxScore()).thenReturn(9);when(grade.scoringSource()).thenReturn("TEACHER");
        when(processing.studentGrade(any())).thenReturn(grade);when(jdbc.queryForObject(contains("first_results"),eq(Timestamp.class),eq(attempt))).thenReturn(Timestamp.from(java.time.Instant.parse("2026-10-01T00:00:00Z")));reject("分值");
    }
}
