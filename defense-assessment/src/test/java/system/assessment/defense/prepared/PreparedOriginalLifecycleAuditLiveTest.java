package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Native public-command audit regression. Native codec, synthetic teacher audio,
 * model contents and SDK doubles are not actual cloud, devices or final QA. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedOriginalLifecycleAuditLiveTest {
    @TempDir Path folder;
    List<Map<String,Object>> audits(JdbcTemplate jdbc,int assessment,String action){return jdbc.queryForList(
        "SELECT actor_id,original_student_id,original_attempt_id,resource_id,resource_version,resource_sha256 FROM prepared_audit WHERE assessment_id=? AND action=? ORDER BY id",assessment,action);}
    void bound(Map<String,Object> row,int actor,int student,String attempt,String answer,long revision){
        assertThat(((Number)row.get("actor_id")).intValue()).isEqualTo(actor);
        assertThat(row.get("original_student_id")).isEqualTo(student);
        assertThat(row.get("original_attempt_id")).isEqualTo(attempt);
        assertThat(row.get("resource_id")).isEqualTo(answer);
        assertThat(((Number)row.get("resource_version")).longValue()).isEqualTo(revision);
    }
    @ParameterizedTest @ValueSource(strings={"ATTEMPT_STARTED","QUESTION_STARTED","ANSWER_END_DECLARED","ANSWER_MEDIA_SAVED"})
    void actualStartedEndedAndSavedEventsBindVerifiedOriginalScope(String action)throws Exception{
        var f=new PreparedSubmissionLiveTest();f.folder=folder;f.setup();
        try{
            f.savedAll();var q=f.m.f.f.ends.q;var rows=audits(q.f.jdbc,q.f.assessment,action);
            if(action.equals("ATTEMPT_STARTED")){
                assertThat(rows).hasSize(1);bound(rows.getFirst(),q.student,q.student,q.attempt.id(),null,1);
            }else{
                var answers=q.f.jdbc.queryForList("SELECT id,ordinal,revision FROM prepared_answer WHERE attempt_id=? ORDER BY ordinal",q.attempt.id());
                assertThat(answers).hasSize(3);assertThat(rows).hasSize(3);
                for(int n=0;n<3;n++){
                    var original=answers.get(n);String answer=(String)original.get("id");long revision;
                    if(action.equals("QUESTION_STARTED"))revision=((Number)original.get("ordinal")).longValue();
                    else if(action.equals("ANSWER_END_DECLARED"))revision=q.f.jdbc.queryForObject("SELECT answer_revision FROM prepared_answer_end WHERE answer_id=?",Long.class,answer);
                    else revision=((Number)original.get("revision")).longValue();
                    bound(rows.get(n),q.student,q.student,q.attempt.id(),answer,revision);
                }
            }
            assertThat(audits(q.f.jdbc,q.f.assessment,action)).isEqualTo(rows);
            assertThat(f.status().submitted()).isFalse();
        }finally{f.cleanup();}
    }
    @ParameterizedTest @ValueSource(strings={"SUPPLEMENT_APPROVED","ANSWER_MEDIA_RESUMED"})
    void approvedAndResumedOriginalTaskUsesAnswerScopeAndDoesNotDuplicateAudit(String action)throws Exception{
        var m=new PreparedAnswerMediaLiveTest();m.folder=folder;m.setup();
        try{
            String hash=m.nativeEnd();m.stagedCloudDouble();var queued=m.request(hash);m.finishOriginalQuestions(true);
            m.worker().runOnce();assertThat(m.status().phase()).isEqualTo("WAITING_APPROVAL");
            var approved=m.approveOriginal();
            for(int n=0;n<20;n++)assertThat(m.request(hash).jobId()).isEqualTo(queued.jobId());
            var q=m.f.f.ends.q;String answer=m.f.f.grant.answerId();var rows=audits(q.f.jdbc,q.f.assessment,action);
            assertThat(rows).hasSize(1);long revision=action.equals("SUPPLEMENT_APPROVED")?approved.approvalRevision():
                q.f.jdbc.queryForObject("SELECT answer_revision FROM prepared_answer_end WHERE answer_id=?",Long.class,answer);
            bound(rows.getFirst(),action.equals("SUPPLEMENT_APPROVED")?q.f.actor:q.student,q.student,q.attempt.id(),answer,revision);
            assertThat(approved.answerId()).isEqualTo(answer);assertThat(approved.attemptId()).isEqualTo(q.attempt.id());
            assertThat(approved.studentId()).isEqualTo(q.student);assertThat(audits(q.f.jdbc,q.f.assessment,action)).isEqualTo(rows);
            assertThat(m.f.f.ends.count("prepared_submission")).isZero();
        }finally{m.cleanup();}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void teacherCurrentAndHistoricalExportsBindAttemptNotAnswerWithoutChangingReportAuthority(boolean historical)throws Exception{
        var f=new PreparedReviewSummaryLiveTest();f.folder=folder;f.setup();
        try{
            f.human();f.f.jdbc.update("UPDATE assessment SET show_result=0 WHERE id=?",f.f.assessment);
            if(historical)f.historical();var summary=f.read();
            assertThat(summary.current()).isEqualTo(!historical);assertThat(summary.showResult()).isFalse();
            int jobs=f.f.count("prepared_job"),scores=f.f.count("prepared_manual_score");
            f.f.f.m.f.f.ends.q.f.tx.executeWithoutResult(s->{
                if(historical){
                    f.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.f.student);
                    f.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",f.f.assessment,f.f.student);
                }
                var report=f.summaries.exportReport(f.f.actor,f.f.assessment,f.f.attempt,summary.resultVersion());
                assertThat(report.summary()).isEqualTo(summary);assertThat(report.answers()).hasSize(3);
                var rows=audits(f.f.jdbc,f.f.assessment,"TEACHER_REPORT_EXPORT_AUTHORIZED");assertThat(rows).hasSize(1);
                bound(rows.getFirst(),f.f.actor,f.f.student,f.f.attempt,null,report.schemaVersion());
                assertThat(rows.getFirst().get("resource_sha256")).isEqualTo(summary.resultVersion());
                assertThat(f.summaries.report(f.f.actor,f.f.assessment,f.f.attempt,summary.resultVersion())).isEqualTo(report);
                assertThat(audits(f.f.jdbc,f.f.assessment,"TEACHER_REPORT_EXPORT_AUTHORIZED")).isEqualTo(rows);
                assertThat(f.f.count("prepared_job")).isEqualTo(jobs);assertThat(f.f.count("prepared_manual_score")).isEqualTo(scores);
                s.setRollbackOnly();
            });
        }finally{f.cleanup();}
    }
    @Test void studentOwnVisibleExportBindsOriginalAttemptWithoutUsingAnAnswerForeignKey()throws Exception{
        var f=new PreparedStudentResultLiveTest();f.folder=folder;f.setup();
        try{
            f.humanGrades();var summary=f.read();int jobs=f.f.count("prepared_job");
            var report=f.results.exportReport(f.f.student,f.f.assessment,summary.resultVersion());
            assertThat(report.summary()).isEqualTo(summary);assertThat(report.answers()).hasSize(3);
            var rows=audits(f.f.jdbc,f.f.assessment,"STUDENT_REPORT_EXPORT_AUTHORIZED");assertThat(rows).hasSize(1);
            bound(rows.getFirst(),f.f.student,f.f.student,f.f.attempt,null,report.schemaVersion());
            assertThat(rows.getFirst().get("resource_sha256")).isEqualTo(summary.resultVersion());
            assertThat(f.results.report(f.f.student,f.f.assessment,summary.resultVersion())).isEqualTo(report);
            assertThat(audits(f.f.jdbc,f.f.assessment,"STUDENT_REPORT_EXPORT_AUTHORIZED")).isEqualTo(rows);
            assertThat(f.f.count("prepared_job")).isEqualTo(jobs);assertThat(f.modelCalls()).isZero();
        }finally{f.cleanup();}
    }
}
