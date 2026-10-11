package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.*;
import system.assessment.defense.infrastructure.repository.dao.service.*;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.processing.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;
import static system.assessment.defense.prepared.processing.PreparedProcessing.*;
import static system.assessment.defense.prepared.processing.PreparedProcessingCodec.*;

/** Actual MySQL, original native media/receipt and permission logic with JDBC-backed repository
 * adapters. Cloud is an explicit SDK double; ASR/score contents are synthetic, NOT model QA. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedProcessingLiveTest {
    final PreparedSubmissionLiveTest f=new PreparedSubmissionLiveTest();@TempDir Path folder;
    PreparedProcessingService service;JdbcTemplate jdbc;int actor,assessment,student;String attempt;
    final List<Integer> extraUsers=new ArrayList<>();
    final PreparedProcessingService.Execution asr=new PreparedProcessingService.Execution("dashscope","qwen3-asr-flash-filetrans","a".repeat(64));
    final PreparedProcessingService.Execution scorer=new PreparedProcessingService.Execution("dashscope","test-score-model","b".repeat(64));
    @BeforeEach void setup()throws Exception {
        f.folder=folder;f.setup();var q=f.m.f.f.ends.q;jdbc=q.f.jdbc;actor=q.f.actor;assessment=q.f.assessment;student=q.student;attempt=q.attempt.id();
        var users=mock(UserService.class);when(users.findById(anyInt())).thenAnswer(i->jdbc.query("SELECT id,type FROM `user` WHERE id=?",(rs,n)->{var u=new UserPO();u.setId(rs.getInt(1));u.setType(rs.getInt(2));return u;},i.getArgument(0,Integer.class)).stream().findFirst());
        var assessments=mock(AssessmentSettingService.class);when(assessments.findById(anyInt())).thenAnswer(i->jdbc.query("SELECT id,create_id FROM assessment WHERE id=? AND deleted=0",(rs,n)->{var a=new AssessmentSettingPO();a.setId(rs.getInt(1));a.setCreateId(rs.getInt(2));return a;},i.getArgument(0,Integer.class)).stream().findFirst());
        var invitations=mock(AssessmentInviteService.class);when(invitations.count(any())).thenReturn(0L);
        var access=new ResourceAccessControlService(users,assessments,invitations,mock(AssessmentStudentRelationService.class));
        service=new PreparedProcessingService(f.service,f.m.jobs,access,jdbc,q.f.tx.getTransactionManager());f.savedAll();f.submit(f.command());
    }
    @AfterEach void cleanup(){
        jdbc.update("DELETE FROM prepared_result_command WHERE assessment_id=?",assessment);jdbc.update("DELETE FROM prepared_score_request WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_processing_head WHERE assessment_id=?",assessment);
        for(String id:jdbc.queryForList("SELECT id FROM prepared_manual_score WHERE assessment_id=? ORDER BY version_no DESC",String.class,assessment))jdbc.update("DELETE FROM prepared_manual_score WHERE id=?",id);
        jdbc.update("DELETE FROM prepared_processing_result WHERE assessment_id=? AND kind='SCORE'",assessment);
        jdbc.update("DELETE FROM prepared_processing_result WHERE assessment_id=? AND kind='TRANSCRIBE'",assessment);f.cleanup();
        for(int id:extraUsers)jdbc.update("DELETE FROM `user` WHERE id=?",id);
    }
    Lease claim(Kind kind){return f.m.jobs.claim(kind,"processing-fixture",300).orElseThrow();}
    Transcript transcript(PreparedProcessingService.Plan plan){var media=plan.original().media().proof();return new Transcript(1,asr.provider(),asr.model(),media.sha256(),media.durationMs(),List.of(new Sentence(0,media.durationMs(),"这是明确标注的合成转写：故障恢复保留原录音，不重答。")));}
    Result transcribe(Lease lease){return service.completeTranscript(lease,encode(transcript(service.plan(lease))),asr);}
    Score score(PreparedProcessingService.Plan plan,String number){return new Score(1,plan.original().question().questionId(),plan.source().id(),plan.source().contentSha256(),plan.original().question().maxScore(),new BigDecimal(number),"合成评分测试",List.of(new Evidence(1,"保留原录音")));}
    Result score(Lease lease,String number){return service.completeScore(lease,encode(score(service.plan(lease),number)),scorer);}
    View view(String answer){return service.review(actor,assessment,attempt,answer);}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE assessment_id=?",Integer.class,assessment);}
    void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);}
    @Test void completeOriginalFileAndFrozenRubricProduceImmutableBoundResultsSeparateFromSubmission() {
        var original=f.status();var lease=claim(Kind.TRANSCRIBE);var plan=service.plan(lease);assertThat(plan.original().media().objectKey()).contains("/media/").endsWith(".wav");
        assertThat(plan.original().question().rubric()).startsWith("不应泄漏的教师标准");var transcript=transcribe(lease);var scoring=claim(Kind.SCORE);var score=score(scoring,"8.50");
        var view=view(transcript.answerId());assertThat(view.transcriptId()).isEqualTo(transcript.id());assertThat(view.scoreId()).isEqualTo(score.id());assertThat(view.history()).containsExactly(transcript,score);assertThat(view.revision()).isEqualTo(2);
        assertThat(score.sourceResultId()).isEqualTo(transcript.id());assertThat(score.provider()).isEqualTo(scorer.provider());assertThat(score.model()).isEqualTo(scorer.model());
        assertThat(f.status()).isEqualTo(original);assertThat(count("prepared_submission")).isEqualTo(1);assertThat(count("prepared_processing_result")).isEqualTo(2);
        conflict(()->transcribe(lease));conflict(()->score(scoring,"8.50"));assertThat(count("prepared_processing_result")).isEqualTo(2);
    }
    @Test void completeAllThreeKeepsFirstResultCompletionAnchorAndNeverChangesPerQuestionOrUploadDeadlines() {
        var original=f.status();for(int n=0;n<3;n++){transcribe(claim(Kind.TRANSCRIBE));score(claim(Kind.SCORE),"7");}
        var first=jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,attempt);assertThat(first).isNotNull();
        String answer=jdbc.queryForObject("SELECT answer_id FROM prepared_processing_head WHERE assessment_id=? ORDER BY answer_id LIMIT 1",String.class,assessment);var v=view(answer);
        service.regrade(actor,assessment,attempt,answer,new RegradeCommand(UUID.randomUUID().toString(),v.revision(),v.transcriptId(),"核对评分"));score(claim(Kind.SCORE),"9");
        assertThat(jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,attempt)).isEqualTo(first);assertThat(f.status()).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT state FROM student_assessment_record WHERE assessment_id=?",Integer.class,assessment)).isEqualTo(2); // no false published/confirmed legacy grade
    }
    @Test void invalidOrEmptyTranscriptAndInvalidScoreNeverWriteFakeAnswersZeroOrRevokeReceipt() {
        var original=f.status();var lease=claim(Kind.TRANSCRIBE);var plan=service.plan(lease);var good=transcript(plan);
        assertThatThrownBy(()->service.completeTranscript(lease,encode(new Transcript(1,asr.provider(),asr.model(),good.audioSha256(),good.durationMs(),List.of())),asr)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("prepared_processing_result")).isZero();var transcript=transcribe(lease);var scoring=claim(Kind.SCORE);
        assertThatThrownBy(()->score(scoring,"10.01")).isInstanceOf(IllegalArgumentException.class);assertThat(view(transcript.answerId()).scoreId()).isNull();assertThat(f.status()).isEqualTo(original);
    }
    @Test void unsettledPaidCallAndExpiredLeaseRollBackResultAndEffectivePointersButKeepOriginals() {
        var original=f.status();var lease=claim(Kind.TRANSCRIBE);String scope="prepared-result-"+UUID.randomUUID(),call=UUID.randomUUID().toString();
        f.m.jobs.initializeBudget(scope,new BigDecimal("100"),new BigDecimal("80"));assertThat(f.m.jobs.reserve(lease,scope,call,new BigDecimal("1")).approved()).isTrue();
        try{conflict(()->transcribe(lease));assertThat(count("prepared_processing_result")).isZero();assertThat(f.m.jobs.cancelBeforeDispatch(call)).isTrue();
            jdbc.update("UPDATE prepared_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",lease.job().id());conflict(()->transcribe(lease));
            assertThat(count("prepared_processing_result")).isZero();assertThat(f.status()).isEqualTo(original);
        }finally{jdbc.update("DELETE FROM prepared_budget_event WHERE scope_id=?",scope);jdbc.update("DELETE FROM prepared_model_call WHERE scope_id=?",scope);jdbc.update("DELETE FROM prepared_budget WHERE scope_id=?",scope);}
    }
    @Test void teacherRegradeAndConfirmationAreIdempotentAndLateOlderResultBecomesHistoryOnly() {
        var transcript=transcribe(claim(Kind.TRANSCRIBE));var initial=score(claim(Kind.SCORE),"6");var v=view(transcript.answerId());
        var request=new RegradeCommand(UUID.randomUUID().toString(),v.revision(),transcript.id(),"重新核验标准");var receipt=service.regrade(actor,assessment,attempt,transcript.answerId(),request);
        for(int n=0;n<10;n++)assertThat(service.regrade(actor,assessment,attempt,transcript.answerId(),request)).isEqualTo(receipt);
        var older=claim(Kind.SCORE);var next=service.regrade(actor,assessment,attempt,transcript.answerId(),new RegradeCommand(UUID.randomUUID().toString(),receipt.revision(),transcript.id(),"改用已核验配置"));var newer=claim(Kind.SCORE);
        assertThat(newer.job().id()).isEqualTo(next.jobId());var effective=score(newer,"9");var current=view(transcript.answerId());
        var confirm=new ConfirmCommand(UUID.randomUUID().toString(),current.revision(),effective.id(),"教师已核验");var confirmed=service.confirm(actor,assessment,attempt,transcript.answerId(),confirm);
        assertThat(service.confirm(actor,assessment,attempt,transcript.answerId(),confirm)).isEqualTo(confirmed);var late=score(older,"5");
        assertThat(view(transcript.answerId()).scoreId()).isEqualTo(effective.id());assertThat(view(transcript.answerId()).confirmed()).isTrue();assertThat(view(transcript.answerId()).revision()).isEqualTo(confirmed.revision());
        assertThat(view(transcript.answerId()).history()).contains(transcript,initial,effective,late);assertThat(decode(f.m.jobs.get(older.job().id()).orElseThrow().outputJson(),Receipt.class).effective()).isFalse();
        conflict(()->service.regrade(actor,assessment,attempt,transcript.answerId(),new RegradeCommand(request.idempotencyKey(),request.expectedRevision(),transcript.id(),"同键改原因")));
        conflict(()->service.confirm(actor,assessment,attempt,transcript.answerId(),new ConfirmCommand(confirm.idempotencyKey(),confirm.expectedRevision(),initial.id(),"同键改评分")));
    }
    @Test void currentRoleAssignmentArchiveAndOriginalJobScopeAreRecheckedBeforeLateWrite() {
        var lease=claim(Kind.TRANSCRIBE);var input=service.plan(lease).original().binding().input();
        jdbc.update("UPDATE `user` SET type=1 WHERE id=?",student);try{assertThatThrownBy(()->transcribe(lease)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}finally{jdbc.update("UPDATE `user` SET type=0 WHERE id=?",student);}
        jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",assessment);try{assertThatThrownBy(()->transcribe(lease)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}finally{jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",assessment);}
        var impostor=f.m.jobs.enqueue(student,assessment,Kind.TRANSCRIBE,input.answerRevision(),UUID.randomUUID().toString(),encode(input));
        jdbc.update("UPDATE prepared_job SET state='RUNNING',attempts=1,lease_generation=1,lease_owner='processing-fixture',lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 300 SECOND) WHERE id=?",impostor.id());
        var forged=new Lease(f.m.jobs.get(impostor.id()).orElseThrow(),1,"processing-fixture");conflict(()->service.plan(forged));assertThat(count("prepared_processing_result")).isZero();
    }
    @Test void managementPermissionsDoNotAllowStudentsUnrelatedTeachersOrWrongAnswerAndRevocationBlocksRegradeCompletion() {
        var transcript=transcribe(claim(Kind.TRANSCRIBE));score(claim(Kind.SCORE),"7");var v=view(transcript.answerId());
        String account="prepared-processing-"+UUID.randomUUID();jdbc.update("INSERT INTO `user`(account,password,type) VALUES (?,'not-a-login-credential',1)",account);int unrelated=jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);extraUsers.add(unrelated);
        for(int user:new int[]{student,unrelated})assertThatThrownBy(()->service.review(user,assessment,attempt,transcript.answerId())).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(system.assessment.defense.infrastructure.emuns.ErrorCodeEnums.NO_PERMISSION.getCode());
        assertThatThrownBy(()->service.review(actor,assessment,attempt,UUID.randomUUID().toString())).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        service.regrade(actor,assessment,attempt,transcript.answerId(),new RegradeCommand(UUID.randomUUID().toString(),v.revision(),v.transcriptId(),"重新核验"));var lease=claim(Kind.SCORE);
        jdbc.update("UPDATE `user` SET type=0 WHERE id=?",actor);try{assertThatThrownBy(()->score(lease,"8")).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(system.assessment.defense.infrastructure.emuns.ErrorCodeEnums.NO_PERMISSION.getCode());}finally{jdbc.update("UPDATE `user` SET type=1 WHERE id=?",actor);}
        assertThat(f.status().submitted()).isTrue();assertThat(view(transcript.answerId()).history()).hasSize(2);
    }
    @Test void changedResultContentInputModelOrTaskReceiptCannotBeUsedForReviewOrNextScore() {
        var transcript=transcribe(claim(Kind.TRANSCRIBE));var q=f.m.f.f.ends.q.f;
        for(String sql:List.of("UPDATE prepared_processing_result SET content_sha256=REPEAT('a',64) WHERE id=?","UPDATE prepared_processing_result SET input_sha256=REPEAT('a',64) WHERE id=?",
            "UPDATE prepared_processing_result SET input_json=NULL WHERE id=?","UPDATE prepared_processing_result SET model='changed' WHERE id=?","UPDATE prepared_processing_result SET provider='changed' WHERE id=?"))
            q.tx.executeWithoutResult(s->{jdbc.update(sql,transcript.id());assertThatThrownBy(()->view(transcript.answerId())).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        q.tx.executeWithoutResult(s->{jdbc.update("UPDATE prepared_job SET output_json='{}' WHERE id=?",transcript.jobId());assertThatThrownBy(()->view(transcript.answerId())).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        assertThat(view(transcript.answerId()).transcriptId()).isEqualTo(transcript.id());
    }
    @Test void schemaRejectsDuplicateJobVersionOrCrossOriginalSource() {
        var first=transcribe(claim(Kind.TRANSCRIBE));var second=transcribe(claim(Kind.TRANSCRIBE));var scoring=claim(Kind.SCORE);var score=score(scoring,"7");
        String foreign=score.sourceResultId().equals(first.id())?second.id():first.id();
        assertThatThrownBy(()->jdbc.update("UPDATE prepared_processing_result SET source_result_id=? WHERE id=?",foreign,score.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE prepared_processing_result SET version_no=0 WHERE id=?",first.id())).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("ck_prepared_result_version");
        assertThatThrownBy(()->jdbc.update("UPDATE prepared_processing_result SET job_id=? WHERE id=?",first.jobId(),second.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void reviewIsReadOnlyBeforeProcessingAndInvalidCommandsCannotCreateResultOrUnboundedJobs() {
        String answer=jdbc.queryForObject("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal LIMIT 1",String.class,assessment);
        assertThat(view(answer).history()).isEmpty();assertThat(count("prepared_processing_head")).isZero();
        for(var invalid:Arrays.asList(null,new RegradeCommand(UUID.randomUUID().toString(),-1,UUID.randomUUID().toString(),"原因"),
            new RegradeCommand("not-a-key",0,UUID.randomUUID().toString(),"原因"),new RegradeCommand(UUID.randomUUID().toString(),0,UUID.randomUUID().toString()," ")))
            assertThatThrownBy(()->service.regrade(actor,assessment,attempt,answer,invalid)).isInstanceOf(IllegalArgumentException.class);
        for(var invalid:Arrays.asList(null,new ConfirmCommand(UUID.randomUUID().toString(),-1,UUID.randomUUID().toString(),"原因"),
            new ConfirmCommand("not-a-key",0,UUID.randomUUID().toString(),"原因"),new ConfirmCommand(UUID.randomUUID().toString(),0,UUID.randomUUID().toString()," ")))
            assertThatThrownBy(()->service.confirm(actor,assessment,attempt,answer,invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.plan(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("prepared_processing_result")).isZero();assertThat(count("prepared_score_request")).isZero();
    }
    @Test void scoreAndHeadCorruptionCannotMasqueradeAsEffectiveGradeAndTechnicalFailureKeepsReceipt() {
        var transcript=transcribe(claim(Kind.TRANSCRIBE));var scoring=claim(Kind.SCORE);var score=score(scoring,"7");var q=f.m.f.f.ends.q.f;
        for(String sql:List.of("UPDATE prepared_processing_head SET transcript_id=? WHERE answer_id=?","UPDATE prepared_processing_head SET score_id=? WHERE answer_id=?"))
            q.tx.executeWithoutResult(s->{jdbc.update(sql,sql.contains("transcript_id")?score.id():transcript.id(),transcript.answerId());assertThatThrownBy(()->view(transcript.answerId())).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        q.tx.executeWithoutResult(s->{jdbc.update("UPDATE prepared_processing_result SET source_result_id=? WHERE id=?",score.id(),score.id());assertThatThrownBy(()->view(transcript.answerId())).isInstanceOf(IllegalStateException.class).hasMessageContaining("拒绝递归引用");s.setRollbackOnly();});
        conflict(()->service.confirm(actor,assessment,attempt,transcript.answerId(),new ConfirmCommand(UUID.randomUUID().toString(),view(transcript.answerId()).revision(),transcript.id(),"错误来源")));
        var original=f.status();var next=claim(Kind.TRANSCRIBE);assertThat(f.m.jobs.fail(next,"PROVIDER_NOT_AVAILABLE",false)).isTrue();
        assertThat(f.status()).isEqualTo(original);assertThat(count("prepared_processing_result")).isEqualTo(2);assertThat(count("prepared_answer_media")).isEqualTo(3);
    }
    @Test void damagedManagementReceiptsCannotFalselyRecoverAnotherJobOrConfirmation() {
        var transcript=transcribe(claim(Kind.TRANSCRIBE));var initial=score(claim(Kind.SCORE),"7");var v=view(transcript.answerId());var q=f.m.f.f.ends.q.f;
        var request=new RegradeCommand(UUID.randomUUID().toString(),v.revision(),v.transcriptId(),"重新核验");var receipt=service.regrade(actor,assessment,attempt,transcript.answerId(),request);
        q.tx.executeWithoutResult(s->{jdbc.update("UPDATE prepared_result_command SET receipt_json=? WHERE assessment_id=? AND idempotency_key=?",encode(new PreparedProcessingService.RegradeReceipt(initial.jobId(),transcript.answerId(),999,transcript.id())),assessment,request.idempotencyKey());
            assertThatThrownBy(()->service.regrade(actor,assessment,attempt,transcript.answerId(),request)).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        var confirm=new ConfirmCommand(UUID.randomUUID().toString(),receipt.revision(),initial.id(),"确认原有效评分");service.confirm(actor,assessment,attempt,transcript.answerId(),confirm);
        q.tx.executeWithoutResult(s->{jdbc.update("UPDATE prepared_result_command SET receipt_json=? WHERE assessment_id=? AND idempotency_key=?",encode(new PreparedProcessingService.ConfirmReceipt(transcript.answerId(),999,initial.id())),assessment,confirm.idempotencyKey());
            assertThatThrownBy(()->service.confirm(actor,assessment,attempt,transcript.answerId(),confirm)).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
    }
    @Test void completedRegradeStillRequiresImmutableRequestRegistrationAndDependency() {
        var transcript=transcribe(claim(Kind.TRANSCRIBE));score(claim(Kind.SCORE),"7");var v=view(transcript.answerId());var q=f.m.f.f.ends.q.f;
        var request=service.regrade(actor,assessment,attempt,transcript.answerId(),new RegradeCommand(UUID.randomUUID().toString(),v.revision(),v.transcriptId(),"重新核验"));score(claim(Kind.SCORE),"8");
        for(String sql:List.of("UPDATE prepared_score_request SET reason='changed' WHERE job_id=?","DELETE FROM prepared_score_request WHERE job_id=?","DELETE FROM prepared_job_dependency WHERE job_id=?"))
            q.tx.executeWithoutResult(s->{jdbc.update(sql,request.jobId());assertThatThrownBy(()->view(transcript.answerId())).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
    }
    String damage(String json,String path,Object value){try{var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var tree=mapper.readTree(json);var fields=path.split("\\.");var parent=tree;
        for(int n=0;n<fields.length-1;n++)parent=parent.get(fields[n]);((com.fasterxml.jackson.databind.node.ObjectNode)parent).set(fields[fields.length-1],mapper.valueToTree(value));return PreparedJobInput.json(tree.toString(),262144);
        }catch(java.io.IOException error){throw new AssertionError(error);}}
    void corruptInput(String result,String path,Object value,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){var tx=f.m.f.f.ends.q.f.tx;String json=jdbc.queryForObject("SELECT input_json FROM prepared_processing_result WHERE id=?",String.class,result);
        tx.executeWithoutResult(s->{String changed=damage(json,path,value);jdbc.update("UPDATE prepared_processing_result SET input_json=?,input_sha256=? WHERE id=?",changed,PreparedJobInput.digest(changed),result);assertThatThrownBy(action).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});}
    @Test void everyFrozenInputAndResultReceiptBindingIsCheckedIndependently() {
        var transcript=transcribe(claim(Kind.TRANSCRIBE));var scored=score(claim(Kind.SCORE),"7");var tx=f.m.f.f.ends.q.f.tx;
        for(var field:Map.<String,Object>of("original.student",student+1,"question.body","被替换的题目","transcript",transcript,"regrade",new PreparedProcessingService.RegradeInput(1,decode(jdbc.queryForObject("SELECT input_json FROM prepared_job WHERE id=?",String.class,transcript.jobId()),system.assessment.defense.prepared.attempt.PreparedSubmission.Work.class),transcript.id(),2,actor,"错误绑定")).entrySet())
            corruptInput(transcript.id(),field.getKey(),field.getValue(),()->view(transcript.answerId()));
        String receipt=jdbc.queryForObject("SELECT output_json FROM prepared_job WHERE id=?",String.class,transcript.jobId());
        for(var field:Map.<String,Object>of("schemaVersion",2,"resultId",UUID.randomUUID().toString(),"answerId",UUID.randomUUID().toString(),"kind","SCORE","version",99,"contentSha256","d".repeat(64)).entrySet())
            tx.executeWithoutResult(s->{jdbc.update("UPDATE prepared_job SET output_json=? WHERE id=?",damage(receipt,field.getKey(),field.getValue()),transcript.jobId());assertThatThrownBy(()->view(transcript.answerId())).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        tx.executeWithoutResult(s->{jdbc.update("DELETE FROM prepared_processing_head WHERE answer_id=?",transcript.answerId());assertThatThrownBy(()->view(transcript.answerId())).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
        corruptInput(scored.id(),"expectedRevision",99,()->view(transcript.answerId()));corruptInput(scored.id(),"transcript.content","{}",()->view(transcript.answerId()));
        String input=jdbc.queryForObject("SELECT input_json FROM prepared_processing_result WHERE id=?",String.class,transcript.id());
        for(String field:List.of("provider","model"))tx.executeWithoutResult(s->{String changed=damage(input,"execution."+field,"changed");jdbc.update("UPDATE prepared_processing_result SET input_json=?,input_sha256=?,"+field+"='changed' WHERE id=?",changed,PreparedJobInput.digest(changed),transcript.id());assertThatThrownBy(()->view(transcript.answerId())).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
    }
    @Test void initialLateScoreCannotReplaceNewerRegradeAndMissingMetadataRollsBack() {
        var transcript=transcribe(claim(Kind.TRANSCRIBE));var initial=claim(Kind.SCORE);var v=view(transcript.answerId());
        service.regrade(actor,assessment,attempt,transcript.answerId(),new RegradeCommand(UUID.randomUUID().toString(),v.revision(),v.transcriptId(),"先核对新版配置"));var newer=claim(Kind.SCORE);
        assertThatThrownBy(()->service.completeScore(newer,encode(score(service.plan(newer),"8")),null)).isInstanceOf(IllegalArgumentException.class);assertThat(count("prepared_processing_result")).isEqualTo(1);
        var current=score(newer,"8");score(initial,"5");assertThat(view(transcript.answerId()).scoreId()).isEqualTo(current.id());
        conflict(()->service.regrade(actor,assessment,attempt,transcript.answerId(),new RegradeCommand(UUID.randomUUID().toString(),0,transcript.id(),"过期版本")));
        conflict(()->service.confirm(actor,assessment,attempt,transcript.answerId(),new ConfirmCommand(UUID.randomUUID().toString(),0,current.id(),"过期版本")));
        assertThatThrownBy(()->service.completeTranscript(newer,"{}",asr)).isInstanceOf(BusinessException.class); // completed lease, not new input
    }
    @Test void modelMetadataTaskTypesAndMissingBindingsAreRejectedWithoutChangingOriginalReceipt() {
        var lease=claim(Kind.TRANSCRIBE);var plan=service.plan(lease);var content=encode(transcript(plan));
        for(var execution:Arrays.asList(null,new PreparedProcessingService.Execution("changed",asr.model(),asr.configSha256()),new PreparedProcessingService.Execution(asr.provider(),"changed",asr.configSha256())))
            conflict(()->service.completeTranscript(lease,content,execution));
        assertThatThrownBy(()->service.completeScore(lease,"{}",scorer)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.plan(new Lease(null,1,"processing-fixture"))).isInstanceOf(IllegalArgumentException.class);
        var work=plan.original().binding().input();var wrong=f.m.jobs.enqueue(student,assessment,Kind.QUESTIONS,work.answerRevision(),UUID.randomUUID().toString(),"{}");
        assertThatThrownBy(()->service.plan(new Lease(wrong,1,"processing-fixture"))).isInstanceOf(IllegalArgumentException.class);
        for(int user:new int[]{0,-1})assertThatThrownBy(()->service.review(user,assessment,attempt,work.answer())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.review(actor,0,attempt,work.answer())).isInstanceOf(IllegalArgumentException.class);
        transcribe(lease);var scoring=claim(Kind.SCORE);assertThatThrownBy(()->service.completeTranscript(scoring,"{}",asr)).isInstanceOf(IllegalArgumentException.class);
        var tx=f.m.f.f.ends.q.f.tx;tx.executeWithoutResult(s->{jdbc.update("UPDATE prepared_processing_head SET transcript_id=NULL WHERE answer_id=?",work.answer());conflict(()->service.plan(scoring));s.setRollbackOnly();});
        assertThat(f.status().submitted()).isTrue();
    }
    @Test void wholeAttemptCompletionDoesNotTrustOtherQuestionsNonNullScoreFlags() {
        var first=transcribe(claim(Kind.TRANSCRIBE));score(claim(Kind.SCORE),"7");transcribe(claim(Kind.TRANSCRIBE));score(claim(Kind.SCORE),"7");
        transcribe(claim(Kind.TRANSCRIBE));var last=claim(Kind.SCORE);var tx=f.m.f.f.ends.q.f.tx;
        tx.executeWithoutResult(s->{jdbc.update("UPDATE prepared_processing_head SET score_id=transcript_id WHERE answer_id=?",first.answerId());
            assertThatThrownBy(()->score(last,"7")).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,attempt)).isNull();
        assertThat(count("prepared_processing_result")).isEqualTo(5);assertThat(f.status().submitted()).isTrue();
        score(last,"7");assertThat(jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,attempt)).isNotNull();
    }
}
