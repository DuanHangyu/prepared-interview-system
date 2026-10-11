package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Owned native database transactions, synthetic frozen paper. No cloud/audio/capacity acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedStartContentionLiveTest {
    private final PreparedPublicationLiveTest f=new PreparedPublicationLiveTest();
    private final List<Integer> ownedStudents=new ArrayList<>();
    private PreparedAttemptStartService starts;
    private String originalKey;
    private PreparedAttemptStart.Summary original;

    @BeforeEach void setup() {
        f.setup(); f.prepare(); f.publish(f.command(2));
        // This matches the actual start-service transaction contract, never a whole-database setting.
        f.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var now=Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"),ZoneOffset.UTC);
        starts=new PreparedAttemptStartService(f.access,new PreparedStudentEligibilityRepository(f.jdbc),
                new PreparedAttemptStartRepository(f.jdbc),now);
        for(int index=0;index<2;index++) {
            String account="prepared-start-lock-"+UUID.randomUUID();
            f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('开考互斥联调',?,'not-a-login-credential',0)",account);
            int student=f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);
            ownedStudents.add(student);
            f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",f.assessment,student);
        }
        originalKey=key();
    }

    @AfterEach void cleanup() {
        if(f.jdbc==null||f.assessment<=0)return;
        for(String table:List.of("prepared_audit","prepared_attempt_start_receipt","prepared_attempt_anchor","prepared_attempt",
                "student_assessment_record","assessment_student_relation"))
            f.jdbc.update("DELETE FROM "+table+" WHERE assessment_id=?",f.assessment);
        f.cleanupOnlyOwnedRows();
        for(int student:ownedStudents) f.jdbc.update("DELETE FROM `user` WHERE id=?",student);
    }

    @RepeatedTest(3)
    void heldOriginalStartDoesNotSerializeAnIndependentStudentOnTheSameFrozenPaper() throws Exception {
        original=start(ownedStudents.getFirst(),originalKey);
        withHeldOriginal(() -> start(ownedStudents.getLast(),key()),false,result -> {
            var other=(PreparedAttemptStart.Summary)result;
            assertThat(other.id()).isNotEqualTo(original.id());
            assertThat(other.paperVersionId()).isEqualTo(original.paperVersionId());
            assertThat(count("prepared_attempt")).isEqualTo(2);
            assertThat(count("prepared_attempt_anchor")).isEqualTo(2);
        });
    }

    @RepeatedTest(3)
    void sameStudentStillSerializesAndReusesOriginalAttemptTimeAndRecord() throws Exception {
        original=start(ownedStudents.getFirst(),originalKey);
        withHeldOriginal(() -> start(ownedStudents.getFirst(),key()),true,result -> {
            assertThat(result).isEqualTo(original);
            assertThat(count("prepared_attempt")).isEqualTo(1);
            assertThat(count("student_assessment_record")).isEqualTo(1);
            assertThat(count("prepared_attempt_start_receipt")).isEqualTo(2);
        });
    }

    @RepeatedTest(3)
    void publicationWaitsForOriginalStartAndCannotChangeTheFrozenPaper() throws Exception {
        original=start(ownedStudents.getFirst(),originalKey);
        withHeldOriginal(() -> {
            try { f.publish(f.command(2)); throw new AssertionError("frozen publication accepted"); }
            catch(BusinessException rejected) { assertThat(rejected.getCode()).isEqualTo(409); return original; }
        },true,result -> {
            assertThat(result).isEqualTo(original);
            assertThat(count("prepared_paper_version")).isEqualTo(1);
            assertThat(start(ownedStudents.getFirst(),originalKey)).isEqualTo(original);
        });
    }

    @RepeatedTest(3)
    void roleRevocationWaitsThenAnOriginalReceiptCannotBypassCurrentAuthorization() throws Exception {
        original=start(ownedStudents.getFirst(),originalKey);
        int student=ownedStudents.getFirst();
        try {
            withHeldOriginal(() -> f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",student),true,result -> {
                assertThat(result).isEqualTo(1);
                assertThatThrownBy(() -> start(student,originalKey)).isInstanceOf(BusinessException.class)
                        .extracting("code").isEqualTo(403);
                assertThat(count("prepared_attempt")).isEqualTo(1);
            });
        } finally { f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",student); }
    }

    @Test void firstStartRollbackRetainsNoRecordReceiptOrFreeze() {
        assertThatThrownBy(() -> f.tx.execute(status -> {
            starts.start(ownedStudents.getFirst(),f.assessment,new PreparedAttemptStart.Command(originalKey));
            throw new IllegalStateException("owned injected rollback");
        })).isInstanceOf(IllegalStateException.class);
        for(String table:List.of("prepared_attempt","prepared_attempt_anchor","prepared_attempt_start_receipt","student_assessment_record"))
            assertThat(count(table)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT first_started_at IS NULL FROM prepared_paper_gate WHERE assessment_id=?",Boolean.class,f.assessment)).isTrue();
    }

    @Test void publicStartTransactionExplicitlyAvoidsMissingRecordGapLockCycles() throws Exception {
        var annotation=PreparedAttemptStartService.class.getMethod("start",int.class,int.class,PreparedAttemptStart.Command.class)
                .getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    @Test void damagedFreezeAfterInitialHintFailsClosedAndRollsBackOnlyTheOwnedFault() {
        original=start(ownedStudents.getFirst(),originalKey);
        var originalFreeze=f.jdbc.queryForObject("SELECT first_started_at FROM prepared_paper_gate WHERE assessment_id=?",java.sql.Timestamp.class,f.assessment);
        var observed=spy(f.jdbc);
        doAnswer(invocation -> {
            Object hint=invocation.callRealMethod();
            assertThat(hint).isEqualTo(true);
            // Explicitly injected corruption of this test's own gate, in the same transaction.
            // The failed command must roll it back, not upgrade the shared parent or create an attempt.
            f.jdbc.update("UPDATE prepared_paper_gate SET first_started_at=NULL WHERE assessment_id=?",f.assessment);
            return hint;
        }).when(observed).queryForObject(
                "SELECT EXISTS(SELECT 1 FROM prepared_paper_gate WHERE assessment_id=? AND first_started_at IS NOT NULL)",Boolean.class,f.assessment);
        var guarded=new PreparedAttemptStartService(f.access,new PreparedStudentEligibilityRepository(f.jdbc),
                new PreparedAttemptStartRepository(observed),Clock.systemUTC());
        assertThatThrownBy(() -> f.tx.execute(status -> guarded.start(ownedStudents.getLast(),f.assessment,
                new PreparedAttemptStart.Command(key())))).isInstanceOf(IllegalStateException.class);
        assertThat(f.jdbc.queryForObject("SELECT first_started_at FROM prepared_paper_gate WHERE assessment_id=?",java.sql.Timestamp.class,f.assessment)).isEqualTo(originalFreeze);
        assertThat(count("prepared_attempt")).isEqualTo(1);
        assertThat(count("prepared_attempt_start_receipt")).isEqualTo(1);
        assertThat(start(ownedStudents.getFirst(),originalKey)).isEqualTo(original);
    }

    private void withHeldOriginal(Callable<?> contender,boolean mustWait,java.util.function.Consumer<Object> verify) throws Exception {
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var contenderReady=new CountDownLatch(1);
        try(var tasks=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=tasks.submit(() -> f.tx.execute(status -> {
                var result=starts.start(ownedStudents.getFirst(),f.assessment,new PreparedAttemptStart.Command(originalKey));
                assertThat(result).isEqualTo(original); held.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("owned transaction release timed out"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("owned transaction interrupted"); }
                return result;
            }));
            Future<?> second=null;Object result=null;
            try {
                assertThat(held.await(3,TimeUnit.SECONDS)).isTrue();
                second=tasks.submit(() -> { contenderReady.countDown(); return contender.call(); });
                assertThat(contenderReady.await(3,TimeUnit.SECONDS)).isTrue();
                if(mustWait) {
                    Future<?> waiting=second;
                    assertThatThrownBy(() -> waiting.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } else {
                    // The first transaction is deliberately still held; sequentializing every student fails here.
                    result=second.get(2,TimeUnit.SECONDS);
                }
            } finally {
                release.countDown(); first.get(10,TimeUnit.SECONDS);
                if(second!=null) {
                    Object completed=second.get(10,TimeUnit.SECONDS);
                    if(result==null)result=completed;
                }
            }
            verify.accept(result);
        }
    }

    private PreparedAttemptStart.Summary start(int student,String key) {
        return f.tx.execute(status -> starts.start(student,f.assessment,new PreparedAttemptStart.Command(key)));
    }
    private int count(String table) { return f.jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE assessment_id=?",Integer.class,f.assessment); }
    private static String key() { return UUID.randomUUID().toString(); }
}
