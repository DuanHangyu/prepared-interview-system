package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.processing.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind;
import static system.assessment.defense.prepared.processing.PreparedProcessing.*;

/** Actual independent MySQL/original receipt; media and model contents remain explicit
 * synthetic fixtures. This is not real cloud, device, retake or final-QA acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedReviewSummaryLiveTest {
    final PreparedProcessingLiveTest f=new PreparedProcessingLiveTest();
    @TempDir Path folder;PreparedReviewSummaryService summaries;String newer;
    @BeforeEach void setup()throws Exception{
        f.folder=folder;f.setup();var manager=f.f.m.f.f.ends.q.f.tx.getTransactionManager();
        summaries=new PreparedReviewSummaryService(f.f.service,f.service,f.jdbc,manager);
    }
    @AfterEach void cleanup(){
        if(newer!=null){f.jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",f.attempt,f.assessment,f.student);f.jdbc.update("DELETE FROM prepared_attempt WHERE id=?",newer);}
        f.cleanup();
    }
    PreparedReviewSummaryService.Summary read(){return summaries.summary(f.actor,f.assessment,f.attempt);}
    List<String> answers(){return f.jdbc.queryForList("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal",String.class,f.assessment);}
    void human(){int n=0;for(String id:answers())f.service.adjust(f.actor,f.assessment,f.attempt,id,new AdjustCommand(UUID.randomUUID().toString(),0,new BigDecimal(List.of("0","7.25","8.5").get(n++)),"教师内部人工核验依据"));}
    void model(){for(String value:List.of("6.25","0","9.5")){f.transcribe(f.claim(Kind.TRANSCRIBE));f.score(f.claim(Kind.SCORE),value);}}
    void historical(){newer=UUID.randomUUID().toString();f.jdbc.update("INSERT INTO prepared_attempt(id,record_id,assessment_id,student_id,attempt_no,paper_version_id,started_at) SELECT ?,record_id,assessment_id,student_id,attempt_no+1,paper_version_id,CURRENT_TIMESTAMP(3) FROM prepared_attempt WHERE id=?",newer,f.attempt);f.jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",newer,f.assessment,f.student);}
    @Test void pendingAndPartialGradesNeverBecomeZeroOrACompleteTotal(){
        var first=read();assertThat(first.state()).isEqualTo("PROCESSING");assertThat(first.totalScore()).isNull();assertThat(first.resultVersion()).isNull();assertThat(first.completedAt()).isNull();assertThat(first.answers()).isEmpty();
        f.transcribe(f.claim(Kind.TRANSCRIBE));f.score(f.claim(Kind.SCORE),"8.5");assertThat(read().state()).isEqualTo("PROCESSING");assertThat(read().answers()).isEmpty();assertThat(f.count("prepared_processing_head")).isEqualTo(1);
    }
    @Test void completeModelGradesUseExactDecimalsAndFrozenRulesNotLegacyIntegers(){
        model();var first=read();assertThat(first.actorId()).isEqualTo(f.actor);assertThat(first.studentId()).isEqualTo(f.student);assertThat(first.assessmentId()).isEqualTo(f.assessment);assertThat(first.attemptId()).isEqualTo(f.attempt);assertThat(first.current()).isTrue();
        assertThat(first.state()).isEqualTo("READY");assertThat(first.totalScore()).isEqualByComparingTo("15.75");assertThat(first.totalMaxScore()).isEqualTo(30);assertThat(first.resultVersion()).matches("[a-f0-9]{64}");assertThat(first.completedAt()).isNotNull();
        assertThat(first.answers().stream().map(PreparedReviewSummaryService.Grade::ordinal)).containsExactly(1,2,3);assertThat(first.answers().get(1).score()).isEqualByComparingTo("0");assertThat(first.answers().getFirst().scoringSource()).isEqualTo("MODEL");assertThat(first.answers().getFirst().confirmed()).isFalse();
        f.jdbc.update("UPDATE student_assessment_record SET score=99,check_score=98 WHERE assessment_id=?",f.assessment);assertThat(read()).isEqualTo(first);
    }
    @Test void manualZeroIsValidAndSummaryDoesNotFetchOrExposeFullAnswerPayloads()throws Exception{
        human();var first=read();assertThat(first.totalScore()).isEqualByComparingTo("15.75");assertThat(first.answers().getFirst().score()).isEqualByComparingTo("0");assertThat(first.answers()).allMatch(a->a.confirmed()&&a.scoringSource().equals("TEACHER"));
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(first);assertThat(json).doesNotContain("教师内部人工核验依据","rubric","transcript","sentences","objectKey","url","jobId","inputJson","provider");assertThat(read()).isEqualTo(first);
    }
    @Test void hiddenStudentResultsDoNotPreventAuthorizedTeacherReviewOrAutomaticallyPublish(){
        human();f.jdbc.update("UPDATE assessment SET show_result=0 WHERE id=?",f.assessment);assertThat(read().state()).isEqualTo("READY");assertThat(f.jdbc.queryForObject("SELECT show_result FROM assessment WHERE id=?",Boolean.class,f.assessment)).isFalse();
    }
    @Test void confirmationAndManualAdjustmentChangeReadVersionButNeverResetCompletionOrSubmission(){
        model();var first=read();String answer=answers().getFirst();var v=f.view(answer);var submission=f.f.status();
        f.service.confirm(f.actor,f.assessment,f.attempt,answer,new ConfirmCommand(UUID.randomUUID().toString(),v.revision(),v.scoreId(),"核验"));var confirmed=read();assertThat(confirmed.resultVersion()).isNotEqualTo(first.resultVersion());assertThat(confirmed.answers().getFirst().confirmed()).isTrue();
        f.service.adjust(f.actor,f.assessment,f.attempt,answer,new AdjustCommand(UUID.randomUUID().toString(),f.view(answer).revision(),new BigDecimal("1.25"),"人工复核"));var changed=read();assertThat(changed.resultVersion()).isNotEqualTo(confirmed.resultVersion());assertThat(changed.totalScore()).isEqualByComparingTo("10.75");assertThat(changed.completedAt()).isEqualTo(first.completedAt());assertThat(f.f.status()).isEqualTo(submission);
    }
    @Test void historicalSummaryKeepsOriginalScoresAndCannotReviveAnyWriter(){
        human();var first=read();int jobs=f.count("prepared_job"),scores=f.count("prepared_manual_score");historical();var old=read();assertThat(old.current()).isFalse();assertThat(old.attemptId()).isEqualTo(f.attempt);assertThat(old.attemptNo()).isEqualTo(1);assertThat(old.totalScore()).isEqualByComparingTo(first.totalScore());assertThat(old.completedAt()).isEqualTo(first.completedAt());assertThat(old.resultVersion()).isNotEqualTo(first.resultVersion());
        assertThatThrownBy(()->f.f.status()).isInstanceOf(BusinessException.class);assertThatThrownBy(()->f.service.adjust(f.actor,f.assessment,f.attempt,answers().getFirst(),new AdjustCommand(UUID.randomUUID().toString(),1,BigDecimal.ONE,"历史不能新写"))).isInstanceOf(BusinessException.class);assertThat(f.count("prepared_job")).isEqualTo(jobs);assertThat(f.count("prepared_manual_score")).isEqualTo(scores);
        var current=summaries.summary(f.actor,f.assessment,newer);assertThat(current.current()).isTrue();assertThat(current.state()).isEqualTo("IN_PROGRESS");assertThat(current.totalScore()).isNull();assertThat(current.answers()).isEmpty();
    }
    @Test void historicalReadUsesCurrentManagerAuthorityNotCurrentStudentRoleOrAssignment(){
        human();historical();var tx=f.f.m.f.f.ends.q.f.tx;
        tx.executeWithoutResult(s->{f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.student);f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",f.assessment,f.student);assertThat(read().state()).isEqualTo("READY");assertThatThrownBy(()->summaries.summary(f.student,f.assessment,f.attempt)).isInstanceOf(BusinessException.class);s.setRollbackOnly();});
    }
    @Test void invalidRoleRevocationArchiveAndForeignAttemptFailBeforeReturningGrades(){
        human();assertThatThrownBy(()->summaries.summary(f.student,f.assessment,f.attempt)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->summaries.summary(f.actor,f.assessment,UUID.randomUUID().toString())).isInstanceOf(BusinessException.class);
        var tx=f.f.m.f.f.ends.q.f.tx;
        tx.executeWithoutResult(s->{f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.actor);assertThatThrownBy(this::read).isInstanceOf(BusinessException.class);s.setRollbackOnly();});
        tx.executeWithoutResult(s->{f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",f.assessment);assertThatThrownBy(this::read).isInstanceOf(BusinessException.class);s.setRollbackOnly();});
        assertThatThrownBy(()->summaries.summary(0,f.assessment,f.attempt)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->summaries.summary(f.actor,0,f.attempt)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->summaries.summary(f.actor,f.assessment,"bad")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingOrFabricatedCompletionAnchorCannotInventOrSuppressValidGrades(){
        var tx=f.f.m.f.f.ends.q.f.tx;
        tx.executeWithoutResult(s->{f.jdbc.update("UPDATE prepared_attempt SET first_results_completed_at=CURRENT_TIMESTAMP(3) WHERE id=?",f.attempt);assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
        human();tx.executeWithoutResult(s->{f.jdbc.update("UPDATE prepared_attempt SET first_results_completed_at=NULL WHERE id=?",f.attempt);assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
    }
    @Test void submittedStateWithoutItsOriginalReceiptFailsClosedEvenForAuthorizedManager(){
        // Scoped contradictory database fixture, not a successful real retake.
        historical();var tx=f.f.m.f.f.ends.q.f.tx;
        tx.executeWithoutResult(s->{f.jdbc.update("UPDATE prepared_attempt SET state='SUBMITTED' WHERE id=?",newer);assertThatThrownBy(()->summaries.summary(f.actor,f.assessment,newer)).isInstanceOf(IllegalStateException.class).hasMessageContaining("唯一回执");s.setRollbackOnly();});
    }
    @Test void originalReceiptPaperHeadOrGradeCorruptionFailsClosedNotAsAZero(){
        model();var tx=f.f.m.f.f.ends.q.f.tx;
        for(String sql:List.of("UPDATE prepared_submission SET snapshot_sha256=REPEAT('f',64) WHERE assessment_id=?","UPDATE prepared_processing_head SET score_id=NULL WHERE assessment_id=?","UPDATE prepared_processing_result SET content_sha256=REPEAT('f',64) WHERE assessment_id=?","UPDATE prepared_paper_version SET content_sha256=REPEAT('f',64) WHERE assessment_id=?"))
            tx.executeWithoutResult(s->{f.jdbc.update(sql,f.assessment);assertThatThrownBy(this::read).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        assertThat(read().state()).isEqualTo("READY");
    }
    @Test void readingDoesNotCreateModelCallsJobsScoresReceiptsOrAuditCommands(){
        human();var tables=List.of("prepared_model_call","prepared_job","prepared_manual_score","prepared_processing_result","prepared_submission","prepared_result_command","prepared_audit");var before=new LinkedHashMap<String,Integer>();
        for(String table:tables)before.put(table,table.equals("prepared_model_call")?f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_model_call c JOIN prepared_job j ON j.id=c.job_id WHERE j.assessment_id=?",Integer.class,f.assessment):f.count(table));
        for(int n=0;n<5;n++)assertThat(read().state()).isEqualTo("READY");
        for(String table:tables){int actual=table.equals("prepared_model_call")?f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_model_call c JOIN prepared_job j ON j.id=c.job_id WHERE j.assessment_id=?",Integer.class,f.assessment):f.count(table);assertThat(actual).isEqualTo(before.get(table));}
    }
}
