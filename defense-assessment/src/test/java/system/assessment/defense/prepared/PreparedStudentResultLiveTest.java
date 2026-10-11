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

/** Independent real DB and original native-media receipts. Cloud/model content is an explicit
 * synthetic fixture, not real-provider, physical-device or final-QA acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedStudentResultLiveTest {
    final PreparedProcessingLiveTest f=new PreparedProcessingLiveTest();@TempDir Path folder;
    PreparedStudentResultService results;
    @BeforeEach void setup()throws Exception{f.folder=folder;f.setup();f.jdbc.update("UPDATE assessment SET show_result=1 WHERE id=?",f.assessment);results=new PreparedStudentResultService(f.f.service,f.service,f.jdbc,f.f.m.f.f.ends.q.f.tx.getTransactionManager());}
    @AfterEach void cleanup(){f.cleanup();}
    PreparedStudentResultService.Summary read(){return results.summary(f.student,f.assessment);}
    void modelGrades(){for(String score:List.of("6.25","0","9.5")){f.transcribe(f.claim(Kind.TRANSCRIBE));f.score(f.claim(Kind.SCORE),score);}}
    List<String> answers(){return f.jdbc.queryForList("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal",String.class,f.assessment);}
    int modelCalls(){return f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_model_call c JOIN prepared_job j ON j.id=c.job_id WHERE j.assessment_id=?",Integer.class,f.assessment);}
    void humanGrades(){for(String answer:answers())f.service.adjust(f.actor,f.assessment,f.attempt,answer,new AdjustCommand(UUID.randomUUID().toString(),0,BigDecimal.ZERO,"内部核验理由，不应返回学生"));}
    void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,int expected){assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(expected);}
    @Test void processingNeverLeaksPartialOrLegacyGradesAndDoesNotCreateResultsOrCallModels(){
        var first=read();assertThat(first.prepared()).isTrue();assertThat(first.state()).isEqualTo("PROCESSING");assertThat(first.totalScore()).isNull();assertThat(first.answers()).isEmpty();
        var transcript=f.transcribe(f.claim(Kind.TRANSCRIBE));f.score(f.claim(Kind.SCORE),"8.5");assertThat(read().state()).isEqualTo("PROCESSING");assertThat(read().answers()).isEmpty();
        assertThat(f.count("prepared_processing_head")).isEqualTo(1);assertThat(f.count("prepared_processing_result")).isEqualTo(2);assertThat(modelCalls()).isZero();
        code(()->results.answer(f.student,f.assessment,transcript.answerId(),"a".repeat(64)),409);
    }
    @Test void ownVisibleCompleteCurrentResultUsesDecimalsAndFrozenRulesNotLegacyIntegers()throws Exception{
        modelGrades();f.jdbc.update("UPDATE student_assessment_record SET check_score=99 WHERE assessment_id=?",f.assessment);
        var result=read();assertThat(result.state()).isEqualTo("READY");assertThat(result.totalScore()).isEqualByComparingTo("15.75");assertThat(result.totalMaxScore()).isEqualTo(30);assertThat(result.answers()).hasSize(3);
        assertThat(result.answers().stream().map(PreparedStudentResultService.Grade::ordinal)).containsExactly(1,2,3);assertThat(result.resultVersion()).matches("[a-f0-9]{64}");
        var answer=results.answer(f.student,f.assessment,result.answers().getFirst().answerId(),result.resultVersion());assertThat(answer.score()).isEqualByComparingTo("6.25");assertThat(answer.scoringSource()).isEqualTo("MODEL");assertThat(answer.transcript().sentences()).hasSize(1);
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(answer);assertThat(json).doesNotContain("rubric","不应泄漏的教师标准","objectKey","jobId","provider","configSha256","inputJson","url");
        assertThat(read()).isEqualTo(result);assertThat(f.f.status().submitted()).isTrue();assertThat(modelCalls()).isZero();
    }
    @Test void manualZeroIsValidWithoutFabricatingTranscriptOrExposingInternalTeacherReason()throws Exception{
        humanGrades();var current=read();assertThat(current.totalScore()).isEqualByComparingTo("0");assertThat(current.state()).isEqualTo("READY");
        var answer=results.answer(f.student,f.assessment,current.answers().getFirst().answerId(),current.resultVersion());assertThat(answer.scoringSource()).isEqualTo("TEACHER");assertThat(answer.confirmed()).isTrue();assertThat(answer.transcript()).isNull();assertThat(answer.feedback()).isNull();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(answer)).doesNotContain("内部核验理由","recordedBy","audioSha256");
    }
    @Test void hiddenResultsAndKnownAnswerIdentifiersCannotBypassCurrentVisibilityOrReadPartialContent(){
        humanGrades();var original=read();f.jdbc.update("UPDATE assessment SET show_result=0 WHERE id=?",f.assessment);
        var hidden=read();assertThat(hidden.state()).isEqualTo("HIDDEN");assertThat(hidden.resultVersion()).isNull();assertThat(hidden.totalScore()).isNull();assertThat(hidden.answers()).isEmpty();assertThat(hidden.completedAt()).isNull();
        code(()->results.answer(f.student,f.assessment,original.answers().getFirst().answerId(),original.resultVersion()),403);
        f.jdbc.update("UPDATE assessment SET show_result=1 WHERE id=?",f.assessment);assertThat(read()).isEqualTo(original);
    }
    @Test void teacherConfirmationChangesTheReadVersionAndStaleDetailsFailInsteadOfMixingTwoVersions(){
        modelGrades();var initial=read();String answer=initial.answers().getFirst().answerId();var v=f.view(answer);
        f.service.confirm(f.actor,f.assessment,f.attempt,answer,new ConfirmCommand(UUID.randomUUID().toString(),v.revision(),v.scoreId(),"核验原评分"));
        assertThat(read().resultVersion()).isNotEqualTo(initial.resultVersion());assertThat(read().answers().getFirst().confirmed()).isTrue();code(()->results.answer(f.student,f.assessment,answer,initial.resultVersion()),409);
    }
    @Test void anotherRoleStudentAssignmentOrArchivedParentIsRejectedOnEveryRead(){
        code(()->results.summary(f.actor,f.assessment),403);code(()->results.summary(f.student+1000000,f.assessment),403);
        var tx=f.f.m.f.f.ends.q.f.tx;
        for(String sql:List.of("UPDATE `user` SET type=1 WHERE id=?","DELETE FROM assessment_student_relation WHERE student_id=?"))
            tx.executeWithoutResult(s->{f.jdbc.update(sql,f.student);code(this::read,403);s.setRollbackOnly();});
        tx.executeWithoutResult(s->{f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",f.assessment);code(this::read,403);s.setRollbackOnly();});
    }
    @Test void missingAnchorAndWrongRecordNeverDowngradePreparedHistoryToLegacyOrAnotherStudentsResult(){
        var tx=f.f.m.f.f.ends.q.f.tx;
        tx.executeWithoutResult(s->{f.jdbc.update("DELETE FROM prepared_attempt_anchor WHERE assessment_id=?",f.assessment);assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
        tx.executeWithoutResult(s->{f.jdbc.update("UPDATE prepared_attempt SET state='IN_PROGRESS' WHERE id=?",f.attempt);assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
    }
    @Test void damagedEffectiveHeadOriginalSubmissionOrResultProofDoesNotBecomeAValidScore(){
        modelGrades();var tx=f.f.m.f.f.ends.q.f.tx;String answer=answers().getFirst();
        for(String sql:List.of("UPDATE prepared_processing_head SET transcript_id=score_id WHERE answer_id=?","UPDATE prepared_processing_head SET score_id=NULL WHERE answer_id=?","UPDATE prepared_processing_result SET content_sha256=REPEAT('f',64) WHERE answer_id=?"))
            tx.executeWithoutResult(s->{f.jdbc.update(sql,answer);assertThatThrownBy(this::read).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        tx.executeWithoutResult(s->{f.jdbc.update("UPDATE prepared_submission SET snapshot_sha256=REPEAT('f',64) WHERE assessment_id=?",f.assessment);assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
    }
    @Test void completeAnchorAloneCannotInventGradesOrPublishResultsBeforeValidCompletion(){
        f.jdbc.update("UPDATE prepared_attempt SET first_results_completed_at=CURRENT_TIMESTAMP(3) WHERE id=?",f.attempt);assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);
    }
    @Test void foreignAnswerStaleScopeMalformedIdentifiersAndVersionAreRejected(){
        humanGrades();var view=read();code(()->results.answer(f.student,f.assessment,UUID.randomUUID().toString(),view.resultVersion()),403);
        code(()->results.answer(f.actor,f.assessment,view.answers().getFirst().answerId(),view.resultVersion()),403);
        assertThatThrownBy(()->results.answer(f.student,f.assessment,"bad",view.resultVersion())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->results.answer(f.student,f.assessment,view.answers().getFirst().answerId(),"bad")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->results.summary(0,f.assessment)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->results.summary(f.student,0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void validLegacyNotStartedAndUnsubmittedAttemptsHaveNoGradesAndDoNotLeakDrafts(){
        var tx=f.f.m.f.f.ends.q.f.tx;
        tx.executeWithoutResult(s->{
            f.jdbc.update("INSERT INTO assessment(theme,create_id,deleted,total_score,pass_score,question_count) VALUES ('旧考核结果隔离',?,0,10,6,1)",f.actor);
            int legacy=f.jdbc.queryForObject("SELECT MAX(id) FROM assessment WHERE create_id=?",Integer.class,f.actor);
            f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",legacy,f.student);
            var result=results.summary(f.student,legacy);assertThat(result.state()).isEqualTo("LEGACY");assertThat(result.prepared()).isFalse();assertThat(result.answers()).isEmpty();s.setRollbackOnly();
        });
        var q=new PreparedQuestionGrantLiveTest();q.setup();
        try{
            // The standby fixture owns a separate DataSource object; reads and rollback edits
            // must use its same transaction manager, just as one Spring application does.
            var standby=new PreparedStudentResultService(f.f.service,f.service,q.f.jdbc,q.f.tx.getTransactionManager());
            var result=standby.summary(q.student,q.f.assessment);assertThat(result.state()).isEqualTo("IN_PROGRESS");assertThat(result.totalScore()).isNull();assertThat(result.answers()).isEmpty();
            q.f.tx.executeWithoutResult(s->{q.f.jdbc.update("DELETE FROM prepared_audit WHERE assessment_id=?",q.f.assessment);
                q.f.jdbc.update("DELETE FROM prepared_question_start_receipt WHERE assessment_id=?",q.f.assessment);
                // No question has yet been authorized in this fixture's setup.
                q.f.jdbc.update("DELETE FROM prepared_attempt_start_receipt WHERE assessment_id=?",q.f.assessment);q.f.jdbc.update("DELETE FROM prepared_attempt_anchor WHERE assessment_id=?",q.f.assessment);
                q.f.jdbc.update("DELETE FROM prepared_attempt WHERE assessment_id=?",q.f.assessment);
                var ready=standby.summary(q.student,q.f.assessment);assertThat(ready.state()).isEqualTo("NOT_STARTED");assertThat(ready.prepared()).isTrue();assertThat(ready.attemptId()).isNull();s.setRollbackOnly();
            });
        }finally{q.cleanupOnlyThisFixture();}
    }
}
