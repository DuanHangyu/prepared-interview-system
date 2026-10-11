package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.flywaydb.core.Flyway;
import system.assessment.defense.prepared.generation.*;
import system.assessment.defense.prepared.jobs.PreparedJobLedger;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.*;
import system.assessment.defense.prepared.paper.*;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real dedicated database and transactions, explicitly synthetic suggestions; no cloud integration claim. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedGenerationLiveTest {
    JdbcTemplate jdbc; TransactionTemplate tx; PreparedJobLedger ledger; PreparedPaperService papers;
    PreparedGenerationService service; ResourceAccessControlService access; int actor, assessment;
    final String content="{\"questions\":[{\"body\":\"合成测试题\",\"rubric\":\"测试依据\",\"maxScore\":10,\"answerSeconds\":120}]}";
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(PreparedLiveDatabase.jdbcUrl(),
                Objects.requireNonNull(System.getenv("PREPARED_DB_USERNAME")),Objects.requireNonNull(System.getenv("PREPARED_DB_PASSWORD")));
        jdbc=new JdbcTemplate(ds); assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("prepared_interview");
        Flyway.configure().dataSource(ds).cleanDisabled(true).load().migrate();
        var manager=new JdbcTransactionManager(ds); tx=new TransactionTemplate(manager); ledger=new PreparedJobLedger(jdbc,manager);
        String account="prepared-generation-"+UUID.randomUUID();
        jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('合成建议联调',?,'not-a-login-credential',1)",account);
        actor=jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);
        jdbc.update("INSERT INTO assessment(theme,create_id,deleted) VALUES ('合成建议独立联调',?,0)",actor);
        assessment=jdbc.queryForObject("SELECT id FROM assessment WHERE create_id=?",Integer.class,actor);
        access=mock(ResourceAccessControlService.class);
        var repo=new PreparedPaperRepository(jdbc); papers=new PreparedPaperService(access,repo);
        service=new PreparedGenerationService(access,repo,papers,new PreparedGenerationRepository(jdbc),ledger,
                new PreparedGenerationPolicy(new MockEnvironment()),new PreparedGenerationCodec());
    }
    @AfterEach void cleanOwnedFixtures() {
        jdbc.update("DELETE FROM prepared_generation_adopted WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_generation_adoption WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_job WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_question_review WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_question_draft WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_audit WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_paper_draft WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM assessment WHERE id=?",assessment); jdbc.update("DELETE FROM `user` WHERE id=?",actor);
    }
    Job synthetic(boolean complete) {
        var command=new PreparedGeneration.Command(0L,UUID.randomUUID().toString(),"合成测试",1,10,120);
        var p=new PreparedGenerationPolicyTest();
        Job job=ledger.enqueue(actor,assessment,Kind.QUESTIONS,0,command.idempotencyKey(),
                PreparedGeneration.encode(new PreparedGeneration.Input(command,new PreparedGenerationPolicy(p.configured(),p.clock).snapshot())));
        if(complete) {var lease=ledger.claim(Kind.QUESTIONS,"fixture-generation",300).orElseThrow();assertThat(lease.job().id()).isEqualTo(job.id());assertThat(ledger.succeed(lease,content)).isTrue();}
        return job;
    }
    @Test void readsSuggestionsSeparatelyAdoptsSelectedThenAllowsEditingAndExplicitReview() {
        Job job=synthetic(true); var view=service.get(actor,assessment,job.id()); assertThat(view.suggestions()).hasSize(1);
        assertThat(papers.getDraft(actor,assessment).questions()).isEmpty();
        String q=view.suggestions().getFirst().questionId();
        var adopted=tx.execute(s->service.adopt(actor,assessment,job.id(),new PreparedGeneration.AdoptCommand(0L,UUID.randomUUID().toString(),List.of(q))));
        assertThat(adopted.draft().questions().getFirst().source()).isEqualTo("AI"); assertThat(adopted.draft().questions().getFirst().approved()).isFalse();
        var edited=tx.execute(s->papers.save(actor,assessment,1,List.of(new PreparedPaper.QuestionInput(q,"教师修订",10,120,"教师标准"))));
        tx.execute(s->papers.approve(actor,assessment,edited.revision(),List.of(q)));
        assertThat(papers.getDraft(actor,assessment).questions().getFirst().approved()).isTrue();
        assertThat(service.get(actor,assessment,job.id()).adoptedQuestionIds()).containsExactly(q);
    }
    @Test void delayedSuggestionsAndStaleAdoptionCannotOverwriteTeacherDraft() {
        Job job=synthetic(true); String q=service.get(actor,assessment,job.id()).suggestions().getFirst().questionId();
        String manual=UUID.randomUUID().toString();tx.execute(s->papers.save(actor,assessment,0,List.of(new PreparedPaper.QuestionInput(manual,"教师已改",10,120,"标准"))));
        assertThatThrownBy(()->tx.execute(s->service.adopt(actor,assessment,job.id(),new PreparedGeneration.AdoptCommand(0L,UUID.randomUUID().toString(),List.of(q)))))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        var next=tx.execute(s->service.adopt(actor,assessment,job.id(),new PreparedGeneration.AdoptCommand(1L,UUID.randomUUID().toString(),List.of(q))));
        assertThat(next.draft().questions()).hasSize(2);assertThat(next.draft().questions().getFirst().body()).isEqualTo("教师已改");
    }
    @Test void lostResponseRetryReturnsReceiptWithoutAppendingAgainAndDifferentKeyCannotDuplicate() {
        Job job=synthetic(true);String q=service.get(actor,assessment,job.id()).suggestions().getFirst().questionId();
        var cmd=new PreparedGeneration.AdoptCommand(0L,UUID.randomUUID().toString(),List.of(q));
        tx.execute(s->service.adopt(actor,assessment,job.id(),cmd));
        assertThat(tx.execute(s->service.adopt(actor,assessment,job.id(),cmd)).adoptedRevision()).isEqualTo(1);
        assertThat(papers.getDraft(actor,assessment).questions()).hasSize(1);
        assertThatThrownBy(()->tx.execute(s->service.adopt(actor,assessment,job.id(),new PreparedGeneration.AdoptCommand(1L,cmd.idempotencyKey(),List.of(q)))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->tx.execute(s->service.adopt(actor,assessment,job.id(),new PreparedGeneration.AdoptCommand(1L,UUID.randomUUID().toString(),List.of(q)))))
                .isInstanceOf(BusinessException.class);
    }
    @Test void incompleteForeignOrUnauthorizedSuggestionsAreNeverAdopted() {
        Job job=synthetic(false);
        assertThatThrownBy(()->tx.execute(s->service.adopt(actor,assessment,job.id(),new PreparedGeneration.AdoptCommand(0L,UUID.randomUUID().toString(),List.of(UUID.randomUUID().toString())))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.get(actor,assessment+10000,job.id())).isInstanceOf(BusinessException.class);
        doThrow(new BusinessException(403,"拒绝")).when(access).requireAssessmentManager(actor,assessment);
        assertThatThrownBy(()->service.list(actor,assessment)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.get(actor,assessment,job.id())).isInstanceOf(BusinessException.class);
        assertThat(papers.getDraft(actor+1,assessment).questions()).isEmpty();
    }
    @Test void unavailableCloudDoesNotCreateJobsOrPretendGenerationSucceeded() {
        assertThat(service.list(actor,assessment).availability().enabled()).isFalse();
        var cmd=new PreparedGeneration.Command(0L,UUID.randomUUID().toString(),"要求",1,10,120);
        assertThatThrownBy(()->tx.execute(s->service.generate(actor,assessment,cmd))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=?",Integer.class,assessment)).isZero();
    }

    @Test void generationCommandIsPersistentIdempotentAndOnlyOneActiveTaskIsAllowed() {
        var p=new PreparedGenerationPolicyTest(); var policy=new PreparedGenerationPolicy(p.configured(),p.clock);
        var repo=new PreparedPaperRepository(jdbc);
        service=new PreparedGenerationService(access,repo,papers,new PreparedGenerationRepository(jdbc),ledger,policy,new PreparedGenerationCodec());
        var cmd=new PreparedGeneration.Command(0L,UUID.randomUUID().toString(),"仅入队自动测试，不发云请求",1,10,120);
        var first=tx.execute(s->service.generate(actor,assessment,cmd));
        assertThat(tx.execute(s->service.generate(actor,assessment,cmd)).id()).isEqualTo(first.id());
        assertThat(service.list(actor,assessment).jobs()).hasSize(1);
        assertThatThrownBy(()->tx.execute(s->service.generate(actor,assessment,new PreparedGeneration.Command(0L,UUID.randomUUID().toString(),"另一个请求",1,10,120))))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThatThrownBy(()->tx.execute(s->service.generate(actor,assessment,new PreparedGeneration.Command(0L,cmd.idempotencyKey(),"同键不同内容",1,10,120))))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        // A retry must still find its accepted command after configuration is disabled and the teacher edits.
        tx.execute(s->papers.save(actor,assessment,0,List.of()));
        service=new PreparedGenerationService(access,repo,papers,new PreparedGenerationRepository(jdbc),ledger,
                new PreparedGenerationPolicy(new MockEnvironment()),new PreparedGenerationCodec());
        assertThat(tx.execute(s->service.generate(actor,assessment,cmd)).id()).isEqualTo(first.id());
    }

    @Test void invalidSelectionAndDraftLimitsRollbackAdoptionReceipt() {
        Job job=synthetic(true);var selected=service.get(actor,assessment,job.id()).suggestions().getFirst().questionId();
        assertThatThrownBy(()->tx.execute(s->service.adopt(actor,assessment,job.id(),new PreparedGeneration.AdoptCommand(0L,UUID.randomUUID().toString(),List.of(UUID.randomUUID().toString())))))
                .isInstanceOf(IllegalArgumentException.class);
        var list=new ArrayList<PreparedPaper.QuestionInput>();for(int i=0;i<50;i++)list.add(new PreparedPaper.QuestionInput(UUID.randomUUID().toString(),"已有题"+i,10,120,"标准"));
        tx.execute(s->papers.save(actor,assessment,0,list));
        assertThatThrownBy(()->tx.execute(s->service.adopt(actor,assessment,job.id(),new PreparedGeneration.AdoptCommand(1L,UUID.randomUUID().toString(),List.of(selected)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_generation_adoption WHERE assessment_id=?",Integer.class,assessment)).isZero();
        assertThat(papers.getDraft(actor,assessment).questions()).hasSize(50);
    }
}
