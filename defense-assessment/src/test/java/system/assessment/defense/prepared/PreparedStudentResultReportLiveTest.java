package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.UUID;
import system.assessment.defense.prepared.processing.PreparedProcessing;
import static org.assertj.core.api.Assertions.*;

/** Actual independent database/immutable original submission. Original media and provider
 * content are explicitly synthetic fixtures, not physical/cloud/model/final-QA acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedStudentResultReportLiveTest {
    final PreparedStudentResultLiveTest f=new PreparedStudentResultLiveTest();@TempDir Path folder;
    @BeforeEach void setup()throws Exception{f.folder=folder;f.setup();}
    @AfterEach void cleanup(){f.cleanup();}
    int exports(){return f.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='STUDENT_REPORT_EXPORT_AUTHORIZED'",Integer.class,f.f.assessment);}
    @Test void completeReportIsOneCoherentCurrentVisibleVersionWithoutExtraScoringOrReadAudits()throws Exception {
        f.modelGrades();var summary=f.read();int before=f.modelCalls();
        var report=f.results.report(f.f.student,f.f.assessment,summary.resultVersion());
        assertThat(report.schemaVersion()).isEqualTo(1);assertThat(report.summary()).isEqualTo(summary);assertThat(report.answers()).hasSize(3);
        for(var answer:report.answers())assertThat(answer).isEqualTo(f.results.answer(f.f.student,f.f.assessment,answer.answerId(),summary.resultVersion()));
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(report)).doesNotContain("rubric","不应泄漏的教师标准","objectKey","provider","jobId","inputJson","url");
        assertThat(f.results.report(f.f.student,f.f.assessment,summary.resultVersion())).isEqualTo(report);assertThat(f.modelCalls()).isEqualTo(before);assertThat(exports()).isZero();
    }
    @Test void exportAuthorizationAuditsOnlyOriginalAttemptAndExactResultDigest()throws Exception {
        f.humanGrades();var summary=f.read();var report=f.results.exportReport(f.f.student,f.f.assessment,summary.resultVersion());
        assertThat(report.summary()).isEqualTo(summary);assertThat(report.answers()).allMatch(a->a.score().signum()==0&&a.transcript()==null&&a.feedback()==null);
        var audit=f.f.jdbc.queryForMap("SELECT actor_id,original_student_id,original_attempt_id,resource_id,resource_sha256,resource_version,authorization_expires_at FROM prepared_audit WHERE assessment_id=? AND action='STUDENT_REPORT_EXPORT_AUTHORIZED'",f.f.assessment);
        assertThat(((Number)audit.get("actor_id")).intValue()).isEqualTo(f.f.student);assertThat(audit.get("resource_id")).isNull();
        assertThat(((Number)audit.get("original_student_id")).intValue()).isEqualTo(f.f.student);assertThat(audit.get("original_attempt_id")).isEqualTo(f.f.attempt);
        assertThat(audit.get("resource_sha256")).isEqualTo(summary.resultVersion());assertThat(((Number)audit.get("resource_version")).intValue()).isEqualTo(1);assertThat(audit.get("authorization_expires_at")).isNull();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(report)).doesNotContain("内部核验理由","recordedBy","audioSha256");assertThat(f.modelCalls()).isZero();
    }
    @Test void hiddenRevokedCrossRoleOrArchivedScopeCannotGetAReportOrExportAudit() {
        f.humanGrades();var summary=f.read();String version=summary.resultVersion();
        f.code(()->f.results.report(f.f.actor,f.f.assessment,version),403);f.code(()->f.results.exportReport(f.f.actor,f.f.assessment,version),403);
        f.code(()->f.results.exportReport(f.f.student+1000000,f.f.assessment,version),403);
        var tx=f.f.f.m.f.f.ends.q.f.tx;
        for(String sql:java.util.List.of("UPDATE assessment SET show_result=0 WHERE id=?","UPDATE assessment SET deleted=1 WHERE id=?"))
            tx.executeWithoutResult(s->{f.f.jdbc.update(sql,f.f.assessment);f.code(()->f.results.report(f.f.student,f.f.assessment,version),403);f.code(()->f.results.exportReport(f.f.student,f.f.assessment,version),403);assertThat(exports()).isZero();s.setRollbackOnly();});
        tx.executeWithoutResult(s->{f.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?",f.f.assessment);f.code(()->f.results.exportReport(f.f.student,f.f.assessment,version),403);assertThat(exports()).isZero();s.setRollbackOnly();});
    }
    @Test void staleOrIncompleteReportsCannotCombineVersionsOrAuthorizeAnExport() {
        f.code(()->f.results.report(f.f.student,f.f.assessment,"a".repeat(64)),409);f.code(()->f.results.exportReport(f.f.student,f.f.assessment,"a".repeat(64)),409);
        f.humanGrades();var old=f.read();String answer=old.answers().getFirst().answerId();
        f.f.service.adjust(f.f.actor,f.f.assessment,f.f.attempt,answer,new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),1,new BigDecimal("1.25"),"内部理由"));
        f.code(()->f.results.report(f.f.student,f.f.assessment,old.resultVersion()),409);f.code(()->f.results.exportReport(f.f.student,f.f.assessment,old.resultVersion()),409);
        assertThat(exports()).isZero();var current=f.read();assertThat(f.results.report(f.f.student,f.f.assessment,current.resultVersion()).summary().totalScore()).isEqualByComparingTo("1.25");
    }
    @Test void malformedVersionCannotReadOrCreateAnAudit() {
        for(String version:java.util.Arrays.asList(null,"", "bad", "A".repeat(64))) {
            assertThatThrownBy(()->f.results.report(f.f.student,f.f.assessment,version)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->f.results.exportReport(f.f.student,f.f.assessment,version)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(exports()).isZero();
    }
}
