package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.paper.*;
import system.assessment.defense.prepared.publication.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL/transactions; audio proof rows are explicit synthetic fixtures, NOT TTS/OSS integration. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedPublicationLiveTest {
    JdbcTemplate jdbc; TransactionTemplate tx; PreparedPaperService papers; PreparedPublicationService service;
    ResourceAccessControlService access; PreparedPublicationRepository publications; PreparedJobLedger jobs;
    int actor, assessment; final String questionId=UUID.randomUUID().toString();
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(PreparedLiveDatabase.jdbcUrl(),
                Objects.requireNonNull(System.getenv("PREPARED_DB_USERNAME")),Objects.requireNonNull(System.getenv("PREPARED_DB_PASSWORD")));
        jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("prepared_interview");
        Flyway.configure().dataSource(ds).cleanDisabled(true).load().migrate();
        var tm=new JdbcTransactionManager(ds);tx=new TransactionTemplate(tm);jobs=new PreparedJobLedger(jdbc,tm);
        String account="prepared-publication-"+UUID.randomUUID();
        jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('发布联调',?,'not-a-login-credential',1)",account);
        actor=jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);
        jdbc.update("INSERT INTO assessment(theme,create_id,deleted,total_score,pass_score,question_count,show_result) VALUES ('发布联调',?,0,10,6,1,0)",actor);
        assessment=jdbc.queryForObject("SELECT id FROM assessment WHERE create_id=?",Integer.class,actor);
        access=mock(ResourceAccessControlService.class);var repo=new PreparedPaperRepository(jdbc);
        papers=new PreparedPaperService(access,repo);publications=new PreparedPublicationRepository(jdbc);
        service=new PreparedPublicationService(access,repo,publications,"prepared-local");
    }
    @AfterEach void cleanupOnlyOwnedRows() {
        jdbc.update("DELETE FROM prepared_publication_receipt WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_paper_gate WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_question_snapshot WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_paper_version WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_audio_asset WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_job WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_question_review WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_question_draft WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_audit WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_paper_draft WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM assessment WHERE id=?",assessment);jdbc.update("DELETE FROM `user` WHERE id=?",actor);
    }
    PreparedPaper.QuestionInput input(String body){return new PreparedPaper.QuestionInput(questionId,body,10,120,"教师标准");}
    void prepare() {
        tx.execute(s->papers.save(actor,assessment,0,List.of(input("原题正文"))));
        tx.execute(s->papers.approve(actor,assessment,1,List.of(questionId)));
        syntheticAudio("原题正文");
    }
    String syntheticAudio(String body) {
        String asset=UUID.randomUUID().toString();
        String key="prepared/prepared-local/assessments/"+assessment+"/questions/"+questionId+"/audio/"+asset+".wav";
        var job=jobs.enqueue(actor,assessment,PreparedJobLedger.Kind.TTS,2,UUID.randomUUID().toString(),"{\"syntheticFixture\":true}");
        jdbc.update("UPDATE prepared_job SET state='SUCCEEDED',output_json='{}' WHERE id=?",job.id());
        jdbc.update("""
                INSERT INTO prepared_audio_asset(id,assessment_id,question_id,body_sha256,config_sha256,job_id,state,object_key,content_sha256,byte_size,duration_ms,media_type,verified_at)
                VALUES (?,?,?,?,?,?,'READY',?,?,48044,1000,'audio/wav',CURRENT_TIMESTAMP(3))
                """,asset,assessment,questionId,PreparedJobInput.digest(body),"a".repeat(64),job.id(),key,"b".repeat(64));
        jdbc.update("UPDATE prepared_question_draft SET audio_state='READY',audio_object_key=? WHERE assessment_id=? AND question_id=?",key,assessment,questionId);
        return asset;
    }
    PreparedPublication.Command command(long rev){var hash=service.status(actor,assessment).preparedContentSha256();return new PreparedPublication.Command(rev,UUID.randomUUID().toString(),hash==null?"a".repeat(64):hash);}
    PreparedPublication.View publish(PreparedPublication.Command cmd){return tx.execute(s->service.publish(actor,assessment,cmd));}
    int versions(){return jdbc.queryForObject("SELECT COUNT(*) FROM prepared_paper_version WHERE assessment_id=?",Integer.class,assessment);}
    @Test void publishedSnapshotIsVerifiedAndSurvivesDraftEditDeleteAndAudioRegeneration() {
        prepare();var status=service.status(actor,assessment);assertThat(status.publishable()).isTrue();
        var first=publish(command(2));assertThat(first.version().versionNo()).isEqualTo(1);
        assertThat(first.snapshot().questions().getFirst().body()).isEqualTo("原题正文");
        tx.execute(s->papers.save(actor,assessment,2,List.of(input("修改正文"))));
        var after=service.status(actor,assessment);assertThat(after.publishable()).isFalse();
        assertThat(after.issues()).extracting("code").contains("NOT_APPROVED","AUDIO_NOT_READY");
        tx.execute(s->papers.approve(actor,assessment,3,List.of(questionId)));syntheticAudio("修改正文");
        var next=publish(command(4));assertThat(next.version().versionNo()).isEqualTo(2);
        assertThat(next.snapshot().questions().getFirst().body()).isEqualTo("修改正文");
        assertThat(service.get(actor,assessment,first.version().id())).isEqualTo(first);
        tx.execute(s->papers.save(actor,assessment,4,List.of()));
        assertThat(service.get(actor,assessment,first.version().id())).isEqualTo(first);
        assertThat(service.status(actor,assessment).activeVersion().id()).isEqualTo(next.version().id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_question_snapshot WHERE assessment_id=?",Integer.class,assessment)).isEqualTo(2);
    }
    @Test void responseLossRetriesAlwaysReturnOriginalReceiptEvenAfterStartAndRevisionChange() {
        prepare();var cmd=command(2);var first=publish(cmd);
        for(int n=0;n<100;n++)assertThat(publish(cmd)).isEqualTo(first);
        assertThat(versions()).isEqualTo(1);
        tx.execute(s->papers.save(actor,assessment,2,List.of()));
        jdbc.update("UPDATE prepared_paper_gate SET first_started_at=CURRENT_TIMESTAMP(3) WHERE assessment_id=?",assessment);
        assertThat(publish(cmd)).isEqualTo(first);
        assertThatThrownBy(()->publish(new PreparedPublication.Command(3L,cmd.idempotencyKey(),cmd.expectedContentSha256()))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThatThrownBy(()->publish(command(3))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(service.status(actor,assessment).frozen()).isTrue();
    }
    @Test void newKeyWithSameContentReusesVersionButChangedRulesCreateNewSnapshot() {
        prepare();var first=publish(command(2));assertThat(publish(command(2))).isEqualTo(first);
        jdbc.update("UPDATE assessment SET pass_score=7,show_result=1 WHERE id=?",assessment);
        var second=publish(command(2));assertThat(second.version().versionNo()).isEqualTo(2);
        assertThat(second.snapshot().rules().passScore()).isEqualTo(7);assertThat(second.snapshot().rules().showResult()).isTrue();
        assertThat(service.get(actor,assessment,first.version().id()).snapshot().rules().passScore()).isEqualTo(6);
    }

    @Test void settingsOrAudioChangeAfterTeacherCheckCannotPublishAnUnconfirmedSnapshot() {
        prepare();var original=command(2);
        jdbc.update("UPDATE assessment SET pass_score=7 WHERE id=?",assessment);
        assertThatThrownBy(()->publish(original)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(versions()).isZero();
        var latest=command(2);assertThat(publish(latest).snapshot().rules().passScore()).isEqualTo(7);
        String asset=jdbc.queryForObject("SELECT id FROM prepared_audio_asset WHERE assessment_id=?",String.class,assessment);
        jdbc.update("UPDATE prepared_audio_asset SET config_sha256=? WHERE id=?","c".repeat(64),asset);
        assertThatThrownBy(()->publish(new PreparedPublication.Command(2L,UUID.randomUUID().toString(),latest.expectedContentSha256()))).isInstanceOf(BusinessException.class);
        assertThat(versions()).isEqualTo(1);
    }
    @Test void unfinishedWrongKindAndForeignJobNeverCountAsVerifiedAudio() {
        prepare();String job=jdbc.queryForObject("SELECT job_id FROM prepared_audio_asset WHERE assessment_id=?",String.class,assessment);
        jdbc.update("UPDATE prepared_job SET state='RUNNING' WHERE id=?",job);
        assertThat(service.status(actor,assessment).issues()).extracting("code").contains("AUDIO_NOT_VERIFIED");
        jdbc.update("UPDATE prepared_job SET state='SUCCEEDED',kind='QUESTIONS' WHERE id=?",job);
        assertThat(service.status(actor,assessment).publishable()).isFalse();
        jdbc.update("UPDATE prepared_job SET kind='ASSEMBLE' WHERE id=?",job);
        assertThat(service.status(actor,assessment).publishable()).isTrue();
        jdbc.update("UPDATE prepared_audio_asset SET state='FAILED' WHERE assessment_id=?",assessment);
        assertThatThrownBy(()->publish(command(2))).isInstanceOf(BusinessException.class);
        assertThat(versions()).isZero();
    }
    @Test void incorrectTextProofAndArbitraryPathCannotBePublished() {
        prepare();jdbc.update("UPDATE prepared_audio_asset SET body_sha256=? WHERE assessment_id=?","c".repeat(64),assessment);
        assertThat(service.status(actor,assessment).issues()).extracting("code").contains("AUDIO_MISMATCH");
        assertThatThrownBy(()->publish(command(2))).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE prepared_audio_asset SET body_sha256=?,object_key='https://public.invalid/file.wav' WHERE assessment_id=?",PreparedJobInput.digest("原题正文"),assessment);
        jdbc.update("UPDATE prepared_question_draft SET audio_object_key='https://public.invalid/file.wav' WHERE assessment_id=?",assessment);
        assertThat(service.status(actor,assessment).issues()).extracting("code").contains("AUDIO_MISMATCH");
        assertThat(versions()).isZero();
    }
    @Test void emptyInvalidTotalsAndStaleRevisionLeaveNoPublicationSideEffects() {
        assertThatThrownBy(()->publish(command(0))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_paper_draft WHERE assessment_id=?",Integer.class,assessment)).isZero();
        prepare();assertThatThrownBy(()->publish(command(1))).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE assessment SET total_score=99,pass_score=100,question_count=3 WHERE id=?",assessment);
        assertThat(service.status(actor,assessment).issues()).extracting("code").contains("TOTAL_SCORE_MISMATCH","INVALID_PASS_SCORE","QUESTION_COUNT_MISMATCH");
        assertThatThrownBy(()->publish(command(2))).isInstanceOf(BusinessException.class);
        assertThat(versions()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_publication_receipt WHERE assessment_id=?",Integer.class,assessment)).isZero();
        assertThat(service.status(actor,assessment).activeVersion()).isNull();
    }
    @Test void simultaneousCommandsSerializeToOneVersionAndOneReceiptPerCommand() throws Exception {
        prepare();var cmd=command(2);var ready=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->{ready.await();return publish(cmd);});
            var b=executor.submit(()->{ready.await();return publish(cmd);});ready.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        assertThat(versions()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_publication_receipt WHERE assessment_id=?",Integer.class,assessment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='PAPER_PUBLISHED'",Integer.class,assessment)).isEqualTo(1);
    }
    @Test void permissionRevocationAndCrossAssessmentVersionLookupAreDeniedBeforeDataMutation() {
        prepare();var first=publish(command(2));assertThatThrownBy(()->service.get(actor,assessment+100000,first.version().id())).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        doThrow(new BusinessException(403,"拒绝")).when(access).requireAssessmentManager(actor,assessment);
        assertThatThrownBy(()->service.status(actor,assessment)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.get(actor,assessment,first.version().id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->publish(command(2))).isInstanceOf(BusinessException.class);
        assertThat(versions()).isEqualTo(1);
    }
    @Test void corruptedSnapshotCannotBeServedAndCrossAssessmentSnapshotForeignKeysAreEnforced() {
        prepare();var first=publish(command(2));
        jdbc.update("UPDATE prepared_paper_version SET snapshot_json=JSON_SET(snapshot_json,'$.rules.passScore',8) WHERE id=?",first.version().id());
        assertThatThrownBy(()->service.get(actor,assessment,first.version().id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("完整性");
        assertThatThrownBy(()->jdbc.update("UPDATE prepared_question_snapshot SET assessment_id=? WHERE paper_version_id=?",assessment+100000,first.version().id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
