package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.prepared.processing.PreparedProcessing;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind;

/** ORIGINAL native DB/public service commands. Native audio, synthetic model
 * results and SDK doubles are fixtures, not cloud, deletion or physical QA. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedOriginalResultAuditLiveTest {
    final PreparedProcessingLiveTest p=new PreparedProcessingLiveTest();
    @TempDir Path folder;
    @BeforeEach void setup()throws Exception {p.folder=folder;p.setup();}
    @AfterEach void cleanup(){p.cleanup();}

    List<Map<String,Object>> audits(String action){return p.jdbc.queryForList(
        "SELECT actor_id,original_student_id,original_attempt_id,resource_id,resource_version FROM prepared_audit WHERE assessment_id=? AND action=? ORDER BY id",p.assessment,action);}
    void bound(Map<String,Object> row,int actor,String answer,long revision){
        assertThat(((Number)row.get("actor_id")).intValue()).isEqualTo(actor);
        assertThat(row.get("original_student_id")).isEqualTo(p.student);
        assertThat(row.get("original_attempt_id")).isEqualTo(p.attempt);
        assertThat(row.get("resource_id")).isEqualTo(answer);
        assertThat(((Number)row.get("resource_version")).longValue()).isEqualTo(revision);
    }
    String answer(){return p.jdbc.queryForObject(
        "SELECT answer_id FROM prepared_submission_answer WHERE attempt_id=? ORDER BY ordinal LIMIT 1",String.class,p.attempt);}
    PreparedProcessing.View scored(){var transcript=p.transcribe(p.claim(Kind.TRANSCRIBE));p.score(p.claim(Kind.SCORE),"7");return p.view(transcript.answerId());}

    @Test void submittedAuditBindsToTheOriginalStudentAndAttemptWithoutUsingAttemptIdAsAnAnswer(){
        var rows=audits("ATTEMPT_SUBMITTED");assertThat(rows).hasSize(1);
        long revision=p.jdbc.queryForObject("SELECT submitted_revision FROM prepared_submission WHERE attempt_id=?",Long.class,p.attempt);
        bound(rows.getFirst(),p.student,null,revision);
        var original=p.f.status();for(int n=0;n<3;n++)assertThat(p.f.submit(p.f.command())).isEqualTo(original);
        assertThat(audits("ATTEMPT_SUBMITTED")).isEqualTo(rows);
    }
    @ParameterizedTest @ValueSource(strings={"REGRADE_REQUESTED","SCORE_CONFIRMED","SCORE_ADJUSTED"})
    void resultCommandsUseVerifiedOriginalAnswerScopeRatherThanTeacherAndOverlappingRevision(String action){
        var view=scored();long revision;
        switch(action){
            case "REGRADE_REQUESTED"->revision=p.service.regrade(p.actor,p.assessment,p.attempt,view.answerId(),
                new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),view.revision(),view.transcriptId(),"原回答审计绑定")).revision();
            case "SCORE_CONFIRMED"->revision=p.service.confirm(p.actor,p.assessment,p.attempt,view.answerId(),
                new PreparedProcessing.ConfirmCommand(UUID.randomUUID().toString(),view.revision(),view.scoreId(),"原回答审计绑定")).revision();
            case "SCORE_ADJUSTED"->revision=p.service.adjust(p.actor,p.assessment,p.attempt,view.answerId(),
                new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),view.revision(),new BigDecimal("8"),"原回答审计绑定")).revision();
            default->throw new AssertionError(action);
        }
        var rows=audits(action);assertThat(rows).hasSize(1);bound(rows.getFirst(),p.actor,view.answerId(),revision);
    }
    @Test void lostCommandResponsesRecoverTheSameReceiptWithoutAppendingAnotherAudit(){
        var view=scored();var regrade=new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),view.revision(),view.transcriptId(),"重评原回执恢复");
        var requested=p.service.regrade(p.actor,p.assessment,p.attempt,view.answerId(),regrade);
        for(int n=0;n<5;n++)assertThat(p.service.regrade(p.actor,p.assessment,p.attempt,view.answerId(),regrade)).isEqualTo(requested);
        p.score(p.claim(Kind.SCORE),"8");var current=p.view(view.answerId());
        var confirm=new PreparedProcessing.ConfirmCommand(UUID.randomUUID().toString(),current.revision(),current.scoreId(),"核分原回执恢复");
        var confirmed=p.service.confirm(p.actor,p.assessment,p.attempt,view.answerId(),confirm);
        for(int n=0;n<5;n++)assertThat(p.service.confirm(p.actor,p.assessment,p.attempt,view.answerId(),confirm)).isEqualTo(confirmed);
        var adjust=new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),confirmed.revision(),new BigDecimal("9"),"人工原回执恢复");
        var adjusted=p.service.adjust(p.actor,p.assessment,p.attempt,view.answerId(),adjust);
        for(int n=0;n<5;n++)assertThat(p.service.adjust(p.actor,p.assessment,p.attempt,view.answerId(),adjust)).isEqualTo(adjusted);
        for(String action:List.of("REGRADE_REQUESTED","SCORE_CONFIRMED","SCORE_ADJUSTED"))assertThat(audits(action)).hasSize(1);
        bound(audits("REGRADE_REQUESTED").getFirst(),p.actor,view.answerId(),requested.revision());
        bound(audits("SCORE_CONFIRMED").getFirst(),p.actor,view.answerId(),confirmed.revision());
        bound(audits("SCORE_ADJUSTED").getFirst(),p.actor,view.answerId(),adjusted.revision());
    }
    @Test void identicalTeacherAndRevisionCannotConfuseTwoDifferentOriginalAnswers(){
        var answers=p.jdbc.queryForList("SELECT answer_id FROM prepared_submission_answer WHERE attempt_id=? ORDER BY ordinal",String.class,p.attempt);
        for(String answer:answers.subList(0,2))p.service.adjust(p.actor,p.assessment,p.attempt,answer,
            new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),0,new BigDecimal("7"),"同版本不同原回答"));
        var rows=audits("SCORE_ADJUSTED");assertThat(rows).hasSize(2);
        bound(rows.get(0),p.actor,answers.get(0),1);bound(rows.get(1),p.actor,answers.get(1),1);
        assertThat(rows).extracting(row->row.get("resource_id")).doesNotHaveDuplicates();
    }
    @Test void rejectedIdentityAndWrongAnswerCannotAppendOriginalResultAuditOrModifyTheHead(){
        var view=scored();var before=p.jdbc.queryForList("SELECT * FROM prepared_audit WHERE assessment_id=? ORDER BY id",p.assessment);
        var denied=new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),view.revision(),new BigDecimal("8"),"拒绝越权审计");
        assertThatThrownBy(()->p.service.adjust(p.student,p.assessment,p.attempt,view.answerId(),denied))
            .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(ErrorCodeEnums.NO_PERMISSION.getCode());
        assertThatThrownBy(()->p.service.adjust(p.actor,p.assessment,p.attempt,UUID.randomUUID().toString(),denied))
            .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThat(p.jdbc.queryForList("SELECT * FROM prepared_audit WHERE assessment_id=? ORDER BY id",p.assessment)).isEqualTo(before);
        assertThat(p.view(view.answerId())).isEqualTo(view);
    }
    @Test void existingDatabaseConstraintsRejectForgedOrHalfBoundOriginalAudit(){
        String answer=answer();p.service.adjust(p.actor,p.assessment,p.attempt,answer,
            new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),0,new BigDecimal("7"),"数据库原范围约束"));
        for(String sql:List.of("UPDATE prepared_audit SET original_student_id=original_student_id+1 WHERE assessment_id=? AND action='SCORE_ADJUSTED'",
            "UPDATE prepared_audit SET original_attempt_id=NULL WHERE assessment_id=? AND action='SCORE_ADJUSTED'",
            "UPDATE prepared_audit SET resource_id=UUID() WHERE assessment_id=? AND action='SCORE_ADJUSTED'"))
            assertThatThrownBy(()->p.jdbc.update(sql,p.assessment)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var rows=audits("SCORE_ADJUSTED");assertThat(rows).hasSize(1);bound(rows.getFirst(),p.actor,answer,1);
    }
}
