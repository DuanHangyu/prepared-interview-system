package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.processing.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.processing.PreparedProcessing.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;

/** Real independent MySQL/original native file fixture; cloud/model doubles are NOT live QA. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedManualScoreLiveTest {
    final PreparedProcessingLiveTest f=new PreparedProcessingLiveTest();@TempDir Path folder;
    @BeforeEach void setup()throws Exception{f.folder=folder;f.setup();}
    @AfterEach void cleanup(){
        f.jdbc.update("DELETE FROM prepared_processing_head WHERE assessment_id=?",f.assessment);
        var ids=f.jdbc.queryForList("SELECT id FROM prepared_manual_score WHERE assessment_id=? ORDER BY version_no DESC",String.class,f.assessment);
        for(String id:ids)f.jdbc.update("DELETE FROM prepared_manual_score WHERE id=?",id);
        f.cleanup();
    }
    String answer(){return f.jdbc.queryForObject("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal LIMIT 1",String.class,f.assessment);}
    AdjustCommand command(long revision,String score){return new AdjustCommand(UUID.randomUUID().toString(),revision,new BigDecimal(score),"听审原录音后按冻结标准核验");}
    PreparedProcessingService.AdjustReceipt adjust(String answer,AdjustCommand command){return f.service.adjust(f.actor,f.assessment,f.attempt,answer,command);}
    PreparedProcessingService.ManualPage history(String answer){return f.service.manualHistory(f.actor,f.assessment,f.attempt,answer,null,20);}
    @Test void humanCanGradeSubmittedOriginalWithoutInventingTranscriptOrModelJobAndRecoverOneReceipt(){
        var original=f.f.status();int originalJobs=f.count("prepared_job");String answer=answer();var command=command(0,"8.50");var receipt=adjust(answer,command);
        for(int n=0;n<100;n++)assertThat(adjust(answer,command)).isEqualTo(receipt);
        var manual=history(answer).current();assertThat(manual.id()).isEqualTo(receipt.manualScoreId());assertThat(manual.score()).isEqualByComparingTo("8.5");
        assertThat(manual.recordedBy()).isEqualTo(f.actor);assertThat(manual.reason()).isEqualTo(command.reason());assertThat(manual.revision()).isEqualTo(1);
        assertThat(manual.transcriptId()).isNull();assertThat(manual.scoreId()).isNull();assertThat(manual.audioSha256()).hasSize(64);
        var view=f.view(answer);assertThat(view.manualScoreId()).isEqualTo(manual.id());assertThat(view.confirmed()).isTrue();assertThat(view.scoreId()).isNull();
        assertThat(f.count("prepared_processing_result")).isZero();assertThat(f.count("prepared_manual_score")).isEqualTo(1);
        assertThat(f.f.status()).isEqualTo(original);assertThat(f.count("prepared_job")).isEqualTo(originalJobs);
        assertThat(f.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,f.attempt)).isNull();
    }
    @Test void differentKeySameRevisionConflictsAndHistoryRetainsHumanReasonsAndBaseAiScore(){
        var transcript=f.transcribe(f.claim(Kind.TRANSCRIBE));var ai=f.score(f.claim(Kind.SCORE),"6");String answer=transcript.answerId();
        var first=adjust(answer,command(f.view(answer).revision(),"8"));var second=adjust(answer,command(first.revision(),"9"));
        var page=history(answer);assertThat(page.history()).hasSize(2);assertThat(page.current().id()).isEqualTo(second.manualScoreId());
        assertThat(page.current().previousManualScoreId()).isEqualTo(first.manualScoreId());assertThat(page.current().scoreId()).isEqualTo(ai.id());
        assertThat(f.view(answer).history()).contains(transcript,ai);f.conflict(()->adjust(answer,command(first.revision(),"7")));
        var original=command(second.revision(),"8");adjust(answer,original);
        f.conflict(()->adjust(answer,new AdjustCommand(original.idempotencyKey(),original.expectedRevision(),new BigDecimal("7"),original.reason())));
        f.conflict(()->f.service.confirm(f.actor,f.assessment,f.attempt,answer,new ConfirmCommand(UUID.randomUUID().toString(),f.view(answer).revision(),ai.id(),"不能确认已被人工替代的分数")));
    }
    @Test void lateTranscriptionAndInitialScoreNeverOverwriteHumanGradeButExplicitRegradeCanReplaceIt(){
        String answer=answer();var first=adjust(answer,command(0,"8"));
        Result transcript=null;for(int n=0;n<3;n++){var result=f.transcribe(f.claim(Kind.TRANSCRIBE));if(answer.equals(result.answerId()))transcript=result;}
        assertThat(transcript).isNotNull();for(int n=0;n<3;n++)f.score(f.claim(Kind.SCORE),"5");
        assertThat(history(answer).current().id()).isEqualTo(first.manualScoreId());assertThat(f.view(answer).scoreId()).isNull();
        var view=f.view(answer);f.service.regrade(f.actor,f.assessment,f.attempt,answer,new RegradeCommand(UUID.randomUUID().toString(),view.revision(),transcript.id(),"明确请求重新模型评分"));
        assertThat(history(answer).current().id()).isEqualTo(first.manualScoreId());assertThat(f.view(answer).confirmed()).isFalse();
        var fresh=f.score(f.claim(Kind.SCORE),"9");assertThat(history(answer).current()).isNull();assertThat(f.view(answer).scoreId()).isEqualTo(fresh.id());
        assertThat(history(answer).history()).extracting(ManualScore::id).containsExactly(first.manualScoreId());
    }
    @Test void currentManualChangeFencesAlreadyRunningRegradeAndPreservesBothResults(){
        var t=f.transcribe(f.claim(Kind.TRANSCRIBE));f.score(f.claim(Kind.SCORE),"6");var view=f.view(t.answerId());
        f.service.regrade(f.actor,f.assessment,f.attempt,t.answerId(),new RegradeCommand(UUID.randomUUID().toString(),view.revision(),t.id(),"重评"));var running=f.claim(Kind.SCORE);
        var manual=adjust(t.answerId(),command(f.view(t.answerId()).revision(),"8"));var late=f.score(running,"9");
        assertThat(history(t.answerId()).current().id()).isEqualTo(manual.manualScoreId());assertThat(f.view(t.answerId()).history()).contains(late);
    }
    @Test void completionAndRetentionAnchorAreEstablishedOnceFromValidManualGradesNotMissingModels(){
        var answers=f.jdbc.queryForList("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal",String.class,f.assessment);
        for(String answer:answers)adjust(answer,command(0,"7"));var first=f.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,f.attempt);assertThat(first).isNotNull();
        adjust(answers.getFirst(),command(f.view(answers.getFirst()).revision(),"8"));
        assertThat(f.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,f.attempt)).isEqualTo(first);
        assertThat(f.jdbc.queryForObject("SELECT state FROM student_assessment_record WHERE assessment_id=?",Integer.class,f.assessment)).isEqualTo(2);
    }
    @Test void boundsRevisionReasonAndCurrentPermissionAreCheckedWithoutCreatingAnyManualGrade(){
        String answer=answer();for(String score:List.of("-0.01","10.01","1.001"))assertThatThrownBy(()->adjust(answer,command(0,score))).isInstanceOf(IllegalArgumentException.class);
        for(var invalid:Arrays.asList(null,new AdjustCommand(UUID.randomUUID().toString(),0,null,"原因"),new AdjustCommand(UUID.randomUUID().toString(),-1,BigDecimal.ONE,"原因"),new AdjustCommand("bad",0,BigDecimal.ONE,"原因"),new AdjustCommand(UUID.randomUUID().toString(),0,BigDecimal.ONE," ")))
            assertThatThrownBy(()->adjust(answer,invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.service.adjust(f.student,f.assessment,f.attempt,answer,command(0,"7"))).isInstanceOf(BusinessException.class);
        f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.actor);try{assertThatThrownBy(()->adjust(answer,command(0,"7"))).isInstanceOf(BusinessException.class);}finally{f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.actor);}
        assertThat(f.count("prepared_manual_score")).isZero();assertThat(f.count("prepared_processing_head")).isZero();
        var zero=adjust(answer,command(0,"0"));assertThat(history(answer).current().score()).isEqualByComparingTo("0");assertThat(zero.revision()).isEqualTo(1);
    }
    @Test void concurrentSameCommandMakesOneAuditedVersionAndDifferentCommandsCannotLoseUpdates()throws Exception{
        String answer=answer();var command=command(0,"8");var latch=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var results=new ArrayList<Future<PreparedProcessingService.AdjustReceipt>>();for(int n=0;n<12;n++)results.add(pool.submit(()->{latch.await();return adjust(answer,command);}));latch.countDown();
            var receipt=results.getFirst().get(30,TimeUnit.SECONDS);for(var result:results)assertThat(result.get(30,TimeUnit.SECONDS)).isEqualTo(receipt);
        }
        assertThat(f.count("prepared_manual_score")).isEqualTo(1);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='SCORE_ADJUSTED'",Integer.class,f.assessment)).isEqualTo(1);
        f.conflict(()->adjust(answer,command(0,"9")));
    }
    @Test void simultaneousDifferentQuestionGradesStillEstablishOneWholeAttemptCompletionAnchor()throws Exception{
        var answers=f.jdbc.queryForList("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal",String.class,f.assessment);var latch=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var results=new ArrayList<Future<PreparedProcessingService.AdjustReceipt>>();for(String answer:answers)results.add(pool.submit(()->{latch.await();return adjust(answer,command(0,"8"));}));latch.countDown();
            for(var result:results)assertThat(result.get(30,TimeUnit.SECONDS).revision()).isEqualTo(1);
        }
        assertThat(f.count("prepared_manual_score")).isEqualTo(3);assertThat(f.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,f.attempt)).isNotNull();
    }
    @Test void completionValidationFailureRollsBackLastDecisionCommandAndAuditButKeepsOriginalReceipt(){
        var answers=f.jdbc.queryForList("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal",String.class,f.assessment);
        var first=adjust(answers.get(0),command(0,"8"));adjust(answers.get(1),command(0,"8"));var original=f.f.status();var last=command(0,"8");var tx=f.f.m.f.f.ends.q.f.tx;
        tx.executeWithoutResult(s->{f.jdbc.update("UPDATE prepared_manual_score SET reason='tampered' WHERE id=?",first.manualScoreId());
            assertThatThrownBy(()->adjust(answers.get(2),last)).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        assertThat(f.count("prepared_manual_score")).isEqualTo(2);assertThat(f.count("prepared_result_command")).isEqualTo(2);assertThat(f.f.status()).isEqualTo(original);
        assertThat(adjust(answers.get(2),last).revision()).isEqualTo(1);
    }
    @Test void historicalPaginationIsBoundedScopedAndDoesNotHideCurrentGrade(){
        String answer=answer();long revision=0;for(int n=0;n<5;n++)revision=adjust(answer,command(revision,"8")).revision();
        var first=f.service.manualHistory(f.actor,f.assessment,f.attempt,answer,null,2);assertThat(first.history()).hasSize(2);assertThat(first.nextManualScoreId()).isEqualTo(first.history().getLast().id());
        var second=f.service.manualHistory(f.actor,f.assessment,f.attempt,answer,first.nextManualScoreId(),2);assertThat(second.history()).hasSize(2);assertThat(second.current()).isEqualTo(first.current());
        var last=f.service.manualHistory(f.actor,f.assessment,f.attempt,answer,second.nextManualScoreId(),2);assertThat(last.history()).hasSize(1);assertThat(last.nextManualScoreId()).isNull();
        for(int limit:new int[]{0,21})assertThatThrownBy(()->f.service.manualHistory(f.actor,f.assessment,f.attempt,answer,null,limit)).isInstanceOf(IllegalArgumentException.class);
        f.conflict(()->f.service.manualHistory(f.actor,f.assessment,f.attempt,answer,UUID.randomUUID().toString(),2));
    }
    @Test void corruptManualProofOrManagementReceiptCannotRecoverFalseSuccessAndRollsBack(){
        String answer=answer();var command=command(0,"8");var first=adjust(answer,command);var tx=f.f.m.f.f.ends.q.f.tx;
        for(String sql:List.of("UPDATE prepared_manual_score SET score_value=9 WHERE id=?","UPDATE prepared_manual_score SET reason='changed' WHERE id=?","UPDATE prepared_manual_score SET input_json='{}' WHERE id=?","UPDATE prepared_manual_score SET input_sha256=REPEAT('f',64) WHERE id=?"))
            tx.executeWithoutResult(s->{f.jdbc.update(sql,first.manualScoreId());assertThatThrownBy(()->history(answer)).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->adjust(answer,command)).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        tx.executeWithoutResult(s->{f.jdbc.update("UPDATE prepared_result_command SET receipt_json='{}' WHERE assessment_id=? AND action='ADJUST'",f.assessment);assertThatThrownBy(()->adjust(answer,command)).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        tx.executeWithoutResult(s->{f.jdbc.update("DELETE FROM prepared_processing_head WHERE answer_id=?",answer);assertThatThrownBy(()->adjust(answer,command(0,"9"))).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        assertThat(history(answer).current().id()).isEqualTo(first.manualScoreId());
    }
    @Test void originalIncompleteOrChangedScopeNeverBecomesGradeableThroughManualFallback(){
        String answer=answer();var tx=f.f.m.f.f.ends.q.f.tx;
        tx.executeWithoutResult(s->{f.jdbc.update("UPDATE prepared_answer_media SET proof_json=NULL,verified_at=NULL WHERE answer_id=?",answer);assertThatThrownBy(()->adjust(answer,command(0,"7"))).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        assertThatThrownBy(()->f.service.adjust(f.actor,f.assessment,UUID.randomUUID().toString(),answer,command(0,"7"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->adjust(UUID.randomUUID().toString(),command(0,"7"))).isInstanceOf(BusinessException.class);assertThat(f.count("prepared_manual_score")).isZero();
    }
    @Test void frozenInputHumanIdentityVersionQuestionAndModelSourcesAreIndependentlyVerified(){
        var t=f.transcribe(f.claim(Kind.TRANSCRIBE));var ai=f.score(f.claim(Kind.SCORE),"6");String answer=t.answerId();var receipt=adjust(answer,command(f.view(answer).revision(),"8"));var tx=f.f.m.f.f.ends.q.f.tx;
        String input=f.jdbc.queryForObject("SELECT input_json FROM prepared_manual_score WHERE id=?",String.class,receipt.manualScoreId());
        var changes=new LinkedHashMap<String,Object>();changes.put("schemaVersion",2);changes.put("original.student",f.student+1);changes.put("question.body","changed");changes.put("actor",f.actor+1);
        changes.put("command.idempotencyKey",UUID.randomUUID().toString());changes.put("command.expectedRevision",0);changes.put("command.score",9);changes.put("command.reason","changed");changes.put("version",99);
        changes.put("transcriptId",ai.id());changes.put("scoreId",t.id());changes.put("previousManualScoreId",receipt.manualScoreId());
        for(var change:changes.entrySet())tx.executeWithoutResult(s->{String corrupted=f.damage(input,change.getKey(),change.getValue());
            f.jdbc.update("UPDATE prepared_manual_score SET input_json=?,input_sha256=? WHERE id=?",corrupted,system.assessment.defense.prepared.jobs.PreparedJobInput.digest(corrupted),receipt.manualScoreId());
            assertThatThrownBy(()->history(answer)).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        for(String sql:List.of("UPDATE prepared_manual_score SET question_id='11111111-1111-4111-8111-111111111111' WHERE id=?","UPDATE prepared_manual_score SET max_score=11 WHERE id=?","UPDATE prepared_manual_score SET audio_sha256=REPEAT('f',64) WHERE id=?"))
            tx.executeWithoutResult(s->{f.jdbc.update(sql,receipt.manualScoreId());assertThatThrownBy(()->history(answer)).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        assertThat(history(answer).current().id()).isEqualTo(receipt.manualScoreId());
    }
    @Test void humanHistoryRequiresMatchingOriginalCommandAndReceiptNotOnlySelfConsistentScoreRows(){
        String answer=answer();var original=command(0,"8");var receipt=adjust(answer,original);var tx=f.f.m.f.f.ends.q.f.tx;
        for(String sql:List.of("DELETE FROM prepared_result_command WHERE assessment_id=? AND action='ADJUST'","UPDATE prepared_result_command SET receipt_json='{}' WHERE assessment_id=? AND action='ADJUST'","UPDATE prepared_result_command SET request_sha256=REPEAT('f',64) WHERE assessment_id=? AND action='ADJUST'"))
            tx.executeWithoutResult(s->{f.jdbc.update(sql,f.assessment);assertThatThrownBy(()->history(answer)).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        String input=f.jdbc.queryForObject("SELECT input_json FROM prepared_manual_score WHERE id=?",String.class,receipt.manualScoreId());
        tx.executeWithoutResult(s->{String changed=f.damage(input,"command.score",9);f.jdbc.update("UPDATE prepared_manual_score SET score_value=9,input_json=?,input_sha256=? WHERE id=?",changed,system.assessment.defense.prepared.jobs.PreparedJobInput.digest(changed),receipt.manualScoreId());
            assertThatThrownBy(()->history(answer)).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        assertThat(history(answer).current().score()).isEqualByComparingTo("8");
    }
    @Test void browserAdjustCommandRejectsCoercionsUnknownFieldsMissingVersionAndInvalidPrecision(){
        String good="{\"idempotencyKey\":\""+UUID.randomUUID()+"\",\"expectedRevision\":0,\"score\":8.5,\"reason\":\"听审依据\"}";
        assertThat(PreparedReviewCommands.adjust(good.getBytes(StandardCharsets.UTF_8)).score()).isEqualByComparingTo("8.5");
        for(String raw:List.of(good.replace("\"expectedRevision\":0,",""),good.replace(":0,",":\"0\","),good.replace(":0,",":0.5,"),good.replace("8.5","\"8.5\""),good.replace("8.5","null"),good.replace("8.5","8.001"),good.replace("{","{\"studentId\":1,"),good.replace("8.5","8.5,\"score\":7"),good+"{}"))
            assertThatThrownBy(()->PreparedReviewCommands.adjust(raw.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedReviewCommands.adjust(new byte[]{(byte)0xC3,0x28})).isInstanceOf(IllegalArgumentException.class);
        for(String raw:List.of(good.replace(":0,",":9007199254740991,"),good.replace("8.5","1000001")))assertThatThrownBy(()->PreparedReviewCommands.adjust(raw.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
}
