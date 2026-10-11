package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.processing.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Real owned MySQL/original receipt and current manager authority. All media,
 * model output and historical-pointer fixtures remain explicitly synthetic. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedReviewReportLiveTest {
    final PreparedReviewSummaryLiveTest f=new PreparedReviewSummaryLiveTest();@TempDir Path folder;
    @BeforeEach void setup()throws Exception{f.folder=folder;f.setup();}
    @AfterEach void cleanup(){f.cleanup();}
    PreparedReviewSummaryService.Report read(String version){return f.summaries.report(f.f.actor,f.f.assessment,f.f.attempt,version);}
    PreparedReviewSummaryService.Report export(String version){return f.summaries.exportReport(f.f.actor,f.f.assessment,f.f.attempt,version);}
    int exports(){return f.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='TEACHER_REPORT_EXPORT_AUTHORIZED'",Integer.class,f.f.assessment);}
    void code(Runnable action,int expected){assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(expected);}
    @Test void completeReportKeepsOriginalSummaryAndContentWithoutChangingAnyScoringOrReceipt()throws Exception{
        f.model();var summary=f.read();int jobs=f.f.count("prepared_job"),results=f.f.count("prepared_processing_result");
        var report=read(summary.resultVersion());assertThat(report.schemaVersion()).isEqualTo(1);assertThat(report.summary()).isEqualTo(summary);assertThat(report.answers()).hasSize(3);
        for(int n=0;n<report.answers().size();n++){
            var answer=report.answers().get(n);var grade=summary.answers().get(n);
            assertThat(answer.studentId()).isEqualTo(summary.studentId());assertThat(answer.attemptId()).isEqualTo(summary.attemptId());
            assertThat(answer.ordinal()).isEqualTo(n+1);assertThat(answer.answerId()).isEqualTo(grade.answerId());assertThat(answer.score()).isEqualByComparingTo(grade.score());
            assertThat(answer.body()).isNotBlank();assertThat(answer.transcript()).isNotNull();
        }
        var json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(report);
        assertThat(json).doesNotContain("rubric","不应泄漏的教师标准","objectKey","provider","jobId","inputJson","url","recordedBy");
        assertThat(read(summary.resultVersion())).isEqualTo(report);assertThat(exports()).isZero();
        assertThat(f.f.count("prepared_job")).isEqualTo(jobs);assertThat(f.f.count("prepared_processing_result")).isEqualTo(results);
    }
    @Test void authorizedTeacherCanReadHiddenHistoryWithoutCurrentStudentRoleOrAssignment(){
        f.human();f.f.jdbc.update("UPDATE assessment SET show_result=0 WHERE id=?",f.f.assessment);f.historical();var summary=f.read();
        var tx=f.f.f.m.f.f.ends.q.f.tx;
        tx.executeWithoutResult(s->{
            f.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.f.student);
            f.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",f.f.assessment,f.f.student);
            var report=read(summary.resultVersion());assertThat(report.summary().current()).isFalse();assertThat(report.summary().showResult()).isFalse();
            assertThat(report.answers()).allMatch(a->a.confirmed()&&a.scoringSource().equals("TEACHER")&&a.feedback()==null&&a.transcript()==null);
            assertThat(exports()).isZero();s.setRollbackOnly();
        });
    }
    @Test void exportAuditBindsOnlyExactAuthenticatedManagerOriginalAttemptSchemaAndDigest()throws Exception{
        f.human();var summary=f.read();assertThat(export(summary.resultVersion()).summary()).isEqualTo(summary);
        var row=f.f.jdbc.queryForMap("SELECT actor_id,original_student_id,original_attempt_id,resource_id,resource_sha256,resource_version,authorization_expires_at FROM prepared_audit WHERE assessment_id=? AND action='TEACHER_REPORT_EXPORT_AUTHORIZED'",f.f.assessment);
        assertThat(((Number)row.get("actor_id")).intValue()).isEqualTo(f.f.actor);assertThat(row.get("resource_id")).isNull();
        assertThat(((Number)row.get("original_student_id")).intValue()).isEqualTo(f.f.student);assertThat(row.get("original_attempt_id")).isEqualTo(f.f.attempt);
        assertThat(row.get("resource_sha256")).isEqualTo(summary.resultVersion());assertThat(((Number)row.get("resource_version")).intValue()).isEqualTo(1);
        assertThat(row.get("authorization_expires_at")).isNull();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(export(summary.resultVersion())))
            .doesNotContain("教师内部人工核验依据","reason","recordedBy","audioSha256");
    }
    @Test void incompleteStaleVisibilityAndCurrentPointerChangesNeverAuthorizeAMixedReport(){
        code(()->read("a".repeat(64)),409);code(()->export("a".repeat(64)),409);f.human();var summary=f.read();
        f.f.service.adjust(f.f.actor,f.f.assessment,f.f.attempt,f.answers().getFirst(),new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),1,new BigDecimal("1.25"),"内部依据"));
        code(()->read(summary.resultVersion()),409);code(()->export(summary.resultVersion()),409);
        var updated=f.read();f.f.jdbc.update("UPDATE assessment SET show_result=NOT show_result WHERE id=?",f.f.assessment);
        code(()->read(updated.resultVersion()),409);var visible=f.read();f.historical();code(()->export(visible.resultVersion()),409);
        assertThat(exports()).isZero();assertThat(read(f.read().resultVersion()).summary().current()).isFalse();
    }
    @Test void revokedStudentForeignArchivedAndCorruptedOriginalScopeFailClosedWithoutAnExportAudit(){
        f.human();var summary=f.read();String version=summary.resultVersion();
        code(()->f.summaries.report(f.f.student,f.f.assessment,f.f.attempt,version),system.assessment.defense.infrastructure.emuns.ErrorCodeEnums.NO_PERMISSION.getCode());
        code(()->f.summaries.exportReport(f.f.actor,f.f.assessment,UUID.randomUUID().toString(),version),403);
        var tx=f.f.f.m.f.f.ends.q.f.tx;
        tx.executeWithoutResult(s->{f.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.f.actor);code(()->export(version),system.assessment.defense.infrastructure.emuns.ErrorCodeEnums.NO_PERMISSION.getCode());s.setRollbackOnly();});
        tx.executeWithoutResult(s->{f.f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",f.f.assessment);code(()->export(version),system.assessment.defense.infrastructure.emuns.ErrorCodeEnums.NO_PERMISSION.getCode());s.setRollbackOnly();});
        tx.executeWithoutResult(s->{f.f.jdbc.update("UPDATE prepared_submission SET snapshot_sha256=REPEAT('f',64) WHERE assessment_id=?",f.f.assessment);
            assertThatThrownBy(()->export(version)).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        assertThat(exports()).isZero();
    }
    @Test void invalidRequestIdentifiersAreRejectedBeforeReadingOrCreatingAudits(){
        for(String version:java.util.Arrays.asList(null,"","bad","A".repeat(64))){
            assertThatThrownBy(()->read(version)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->export(version)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->f.summaries.report(0,f.f.assessment,f.f.attempt,"a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.summaries.exportReport(f.f.actor,0,"bad","a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThat(exports()).isZero();
    }
}
