package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.*;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

/** Real owned MySQL lock boundaries. Synthetic paper/audio are not cloud or capacity acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedQuestionContentionLiveTest {
    private final PreparedQuestionGrantLiveTest f=new PreparedQuestionGrantLiveTest();
    private int otherStudent;
    private PreparedAttemptStart.Summary otherAttempt;
    private final String otherClient=key(),originalKey=key();
    private PreparedQuestionGrant.Grant original;

    @BeforeEach void setup() {
        f.setup();
        f.f.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        String account="prepared-question-lock-"+key();
        f.f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('开题互斥联调',?,'not-a-login-credential',0)",account);
        otherStudent=f.f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);
        f.f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",f.f.assessment,otherStudent);
        otherAttempt=f.f.tx.execute(status -> f.starts.start(otherStudent,f.f.assessment,new PreparedAttemptStart.Command(key())));
        original=f.grant(originalKey,f.client,1);
    }

    @AfterEach void cleanupOnlyOwnedFixture() {
        if(f.f.jdbc==null||f.f.assessment<=0)return;
        f.cleanupOnlyThisFixture();
        if(otherStudent>0)f.f.jdbc.update("DELETE FROM `user` WHERE id=?",otherStudent);
    }

    @RepeatedTest(3)
    void heldQuestionDoesNotSerializeAnIndependentStudentOnTheSameFrozenQuestion() throws Exception {
        withHeldOriginal(this::otherGrant,false,result -> {
            var grant=(PreparedQuestionGrant.Grant)result;
            assertThat(grant.answerId()).isNotEqualTo(original.answerId());
            assertThat(grant.questionId()).isEqualTo(original.questionId());
            assertThat(grant.paperVersionId()).isEqualTo(original.paperVersionId());
            assertThat(grant.startedAt()).isEqualTo(original.startedAt());
            assertThat(grant.answerDeadline()).isEqualTo(original.answerDeadline());
            assertThat(grant.mayRecord()).isTrue();
            assertThat(f.count("prepared_answer")).isEqualTo(2);
            assertThat(f.count("prepared_question_start_receipt")).isEqualTo(2);
            assertThat(f.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='QUESTION_STARTED'",Integer.class,f.f.assessment)).isEqualTo(2);
        });
    }

    @RepeatedTest(3)
    void sameStudentWithANewKeyWaitsAndKeepsOriginalAnswerAndDeadline() throws Exception {
        withHeldOriginal(() -> f.grant(key(),f.client,1),true,result -> {
            assertThat(result).isEqualTo(original);
            assertThat(f.count("prepared_answer")).isEqualTo(1);
            assertThat(f.count("prepared_question_start_receipt")).isEqualTo(2);
        });
    }

    @RepeatedTest(3)
    void publicationWaitsAndCannotReplaceTheStartedPaper() throws Exception {
        withHeldOriginal(() -> {
            assertThatThrownBy(() -> f.f.publish(f.f.command(2))).isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(409);
            return original;
        },true,result -> {
            assertThat(result).isEqualTo(original);
            assertThat(f.f.versions()).isEqualTo(1);
            assertThat(f.grant(originalKey,f.client,1)).isEqualTo(original);
        });
    }

    @RepeatedTest(3)
    void currentRoleRevocationWaitsAndAnOriginalReceiptDoesNotBypassIt() throws Exception {
        try {
            withHeldOriginal(() -> f.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.student),true,result -> {
                assertThat(result).isEqualTo(1);
                assertThatThrownBy(() -> f.grant(originalKey,f.client,1)).isInstanceOf(BusinessException.class)
                        .extracting("code").isEqualTo(403);
                assertThat(otherGrant().mayRecord()).isTrue();
            });
        } finally { f.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.student); }
    }

    @RepeatedTest(3)
    void currentAssignmentRevocationWaitsAndCannotReuseOriginalReceipt() throws Exception {
        withHeldOriginal(() -> f.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",f.f.assessment,f.student),true,result -> {
            assertThat(result).isEqualTo(1);
            assertThatThrownBy(() -> f.grant(originalKey,f.client,1)).isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(403);
            assertThat(otherGrant().mayRecord()).isTrue();
        });
    }

    @RepeatedTest(3)
    void archiveWaitsThenBothStudentsAreDeniedWithoutAnotherAnswer() throws Exception {
        try {
            withHeldOriginal(() -> f.f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",f.f.assessment),true,result -> {
                assertThat(result).isEqualTo(1);
                assertThatThrownBy(this::otherGrant).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
                assertThatThrownBy(() -> f.grant(originalKey,f.client,1)).isInstanceOf(BusinessException.class)
                        .extracting("code").isEqualTo(403);
                assertThat(f.count("prepared_answer")).isEqualTo(1);
            });
        } finally { f.f.jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",f.f.assessment); }
    }

    @RepeatedTest(3)
    void relationalQuestionMutationWaitsAndCorruptionIsNeverGranted() throws Exception {
        try {
            withHeldOriginal(() -> f.f.jdbc.update("UPDATE prepared_question_snapshot SET body='owned damaged body' WHERE paper_version_id=? AND question_id=?",original.paperVersionId(),original.questionId()),true,result -> {
                assertThat(result).isEqualTo(1);
                assertThatThrownBy(this::otherGrant).isInstanceOf(IllegalStateException.class).hasMessage("题目快照绑定验证失败");
                assertThat(f.count("prepared_answer")).isEqualTo(1);
                assertThat(f.count("prepared_question_start_receipt")).isEqualTo(1);
            });
        } finally { f.f.jdbc.update("UPDATE prepared_question_snapshot SET body=? WHERE paper_version_id=? AND question_id=?",original.body(),original.paperVersionId(),original.questionId()); }
    }

    @RepeatedTest(3)
    void frozenPaperMutationWaitsAndAnInvalidDigestIsNeverGranted() throws Exception {
        String sha=f.f.jdbc.queryForObject("SELECT content_sha256 FROM prepared_paper_version WHERE id=?",String.class,original.paperVersionId());
        try {
            withHeldOriginal(() -> f.f.jdbc.update("UPDATE prepared_paper_version SET content_sha256=? WHERE id=?","c".repeat(64),original.paperVersionId()),true,result -> {
                assertThat(result).isEqualTo(1);
                assertThatThrownBy(this::otherGrant).isInstanceOf(IllegalStateException.class);
                assertThat(f.count("prepared_answer")).isEqualTo(1);
            });
        } finally { f.f.jdbc.update("UPDATE prepared_paper_version SET content_sha256=? WHERE id=?",sha,original.paperVersionId()); }
    }

    @Test void absentFreezeFailsClosedAndRollsBackTheOwnedFault() {
        var freeze=f.f.jdbc.queryForObject("SELECT first_started_at FROM prepared_paper_gate WHERE assessment_id=?",java.sql.Timestamp.class,f.f.assessment);
        assertThatThrownBy(() -> f.f.tx.execute(status -> {
            f.f.jdbc.update("UPDATE prepared_paper_gate SET first_started_at=NULL WHERE assessment_id=?",f.f.assessment);
            return f.grants.start(otherStudent,f.f.assessment,otherAttempt.id(),new PreparedQuestionGrant.Command(key(),otherClient,1));
        })).isInstanceOf(IllegalStateException.class);
        assertThat(f.f.jdbc.queryForObject("SELECT first_started_at FROM prepared_paper_gate WHERE assessment_id=?",java.sql.Timestamp.class,f.f.assessment)).isEqualTo(freeze);
        assertThat(f.count("prepared_answer")).isEqualTo(1);
        assertThat(otherGrant().mayRecord()).isTrue();
    }

    @Test void publicQuestionStartUsesReadCommittedWithoutChangingTheGlobalIsolation() throws Exception {
        var annotation=PreparedQuestionGrantService.class.getMethod("start",int.class,int.class,String.class,PreparedQuestionGrant.Command.class)
                .getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    private PreparedQuestionGrant.Grant otherGrant() {
        return f.f.tx.execute(status -> f.grants.start(otherStudent,f.f.assessment,otherAttempt.id(),new PreparedQuestionGrant.Command(key(),otherClient,1)));
    }

    private void withHeldOriginal(Callable<?> contender,boolean mustWait,Consumer<Object> verify) throws Exception {
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var ready=new CountDownLatch(1);
        try(var tasks=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=tasks.submit(() -> f.f.tx.execute(status -> {
                var replay=f.grants.start(f.student,f.f.assessment,f.attempt.id(),new PreparedQuestionGrant.Command(originalKey,f.client,1));
                assertThat(replay).isEqualTo(original);held.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("owned question transaction release timed out"); }
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                return replay;
            }));
            Future<?> second=null;Object result=null;
            try {
                assertThat(held.await(3,TimeUnit.SECONDS)).isTrue();
                second=tasks.submit(() -> {ready.countDown();return contender.call();});
                assertThat(ready.await(3,TimeUnit.SECONDS)).isTrue();
                if(mustWait) {
                    Future<?> waiting=second;
                    assertThatThrownBy(() -> waiting.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } else result=second.get(2,TimeUnit.SECONDS);
            } finally {
                release.countDown();first.get(10,TimeUnit.SECONDS);
                if(second!=null){Object completed=second.get(10,TimeUnit.SECONDS);if(result==null)result=completed;}
            }
            verify.accept(result);
        }
    }

    private static String key(){return UUID.randomUUID().toString();}
}
