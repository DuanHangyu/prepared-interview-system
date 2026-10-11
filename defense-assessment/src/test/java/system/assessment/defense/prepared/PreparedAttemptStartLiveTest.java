package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual MySQL start transaction foundation; inherited fixture audio is synthetic, NOT real TTS/OSS or M05 acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAttemptStartLiveTest {
    private final PreparedPublicationLiveTest f=new PreparedPublicationLiveTest();
    private final AtomicReference<Instant> now=new AtomicReference<>();
    private PreparedAttemptStartService service;
    private int student;
    @BeforeEach void setup() {
        f.setup();now.set(Instant.parse("2026-10-04T02:00:00Z"));
        String account="prepared-attempt-"+UUID.randomUUID();
        f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('开考事务联调',?,'not-a-login-credential',0)",account);
        student=f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);
        f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",f.assessment,student);
        service=new PreparedAttemptStartService(f.access,new PreparedStudentEligibilityRepository(f.jdbc),new PreparedAttemptStartRepository(f.jdbc),new Clock(){
            public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}
        });
    }
    @AfterEach void cleanupOnlyThisFixture() {
        // Original-scope audit FKs must be removed before this test's owned attempts.
        f.jdbc.update("DELETE FROM prepared_audit WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM prepared_attempt_start_receipt WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM prepared_attempt_anchor WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM prepared_attempt WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM student_assessment_appointment WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM student_assessment_record WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?",f.assessment);
        f.cleanupOnlyOwnedRows();f.jdbc.update("DELETE FROM `user` WHERE id=?",student);
    }
    String publish(){f.prepare();return f.publish(f.command(2)).version().id();}
    PreparedAttemptStart.Summary start(String key){return f.tx.execute(s->service.start(student,f.assessment,new PreparedAttemptStart.Command(key)));}
    String key(){return UUID.randomUUID().toString();}
    int count(String table){return f.jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE assessment_id=?",Integer.class,f.assessment);}

    @Test void firstStartCreatesOneAnchorBindsPublicationAndFreezesItButDoesNotGrantAnyQuestionOrOverallDeadline() throws Exception {
        String version=publish(),key=key();var first=start(key);
        assertThat(first.paperVersionId()).isEqualTo(version);assertThat(first.state()).isEqualTo("IN_PROGRESS");
        assertThat(first.attemptNo()).isEqualTo(1);assertThat(first.startedAt()).isEqualTo(now.get().toString());
        assertThat(first.answeringEndedAt()).isNull();assertThat(first.supplementalUploadDeadline()).isNull();
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(first);
        assertThat(json).doesNotContain("rubric","body","objectKey","questionId","answerDeadline","attemptDeadline","expiresAt");
        assertThat(count("prepared_attempt")).isEqualTo(1);assertThat(count("student_assessment_record")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT first_started_at IS NOT NULL FROM prepared_paper_gate WHERE assessment_id=?",Boolean.class,f.assessment)).isTrue();
        assertThat(f.service.status(f.actor,f.assessment).frozen()).isTrue();
        assertThat(service.current(student,f.assessment)).contains(first);
        assertThatThrownBy(()->f.publish(f.command(2))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
    }
    @Test void oneHundredSameCommandRetriesAndDifferentKeysReuseOriginalTimeRecordAndAttempt() {
        publish();var original=start(key());now.set(now.get().plus(Duration.ofDays(3)));
        String another=key();for(int i=0;i<100;i++)assertThat(start(another)).isEqualTo(original);
        assertThat(start(key())).isEqualTo(original);assertThat(service.current(student,f.assessment)).contains(original);
        assertThat(count("prepared_attempt")).isEqualTo(1);assertThat(count("student_assessment_record")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='ATTEMPT_STARTED'",Integer.class,f.assessment)).isEqualTo(1);
    }
    @Test void simultaneousDifferentCommandsCannotCreateTwoAttemptsAndUseTheSameParentLockAsPublication() throws Exception {
        publish();var barrier=new CountDownLatch(1);var results=new ArrayList<Future<PreparedAttemptStart.Summary>>();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<12;i++)results.add(executor.submit(()->{barrier.await();return start(key());}));barrier.countDown();
            var original=results.getFirst().get(15,TimeUnit.SECONDS);
            for(var result:results)assertThat(result.get(15,TimeUnit.SECONDS)).isEqualTo(original);
        }
        assertThat(count("prepared_attempt")).isEqualTo(1);assertThat(count("prepared_attempt_anchor")).isEqualTo(1);
        assertThat(count("prepared_attempt_start_receipt")).isEqualTo(12);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void earlierAuthorizationReadSnapshotCannotHideTheStartThatCommittedWhileWaitingForTheParentLock(boolean sameKey) throws Exception {
        publish();var ready=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new java.util.concurrent.atomic.AtomicBoolean();
        String originalKey=key(),waitingKey=sameKey?originalKey:key();
        doAnswer(invocation->{if(once.compareAndSet(false,true)) {
            assertThat(count("prepared_attempt")).isZero();ready.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();
        }return null;}).when(f.access).requireAssignedStudent(student,f.assessment);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting=executor.submit(()->start(waitingKey));assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();
            var original=start(originalKey);release.countDown();assertThat(waiting.get(10,TimeUnit.SECONDS)).isEqualTo(original);
        }finally{release.countDown();}
        assertThat(count("prepared_attempt")).isEqualTo(1);assertThat(count("prepared_attempt_start_receipt")).isEqualTo(sameKey?1:2);
    }
    @Test void noPublicationAndAnyLegacyRecordNeverGetSilentlyReusedOrOverwritten() {
        assertThat(service.current(student,f.assessment)).isEmpty();
        assertThatThrownBy(()->start(key())).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        publish();f.jdbc.update("INSERT INTO student_assessment_record(assessment_id,student_id,state,defense_content,score) VALUES (?,?,3,'legacy evidence',7)",f.assessment,student);
        assertThatThrownBy(()->start(key())).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(count("prepared_attempt")).isZero();assertThat(count("prepared_attempt_anchor")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT defense_content FROM student_assessment_record WHERE assessment_id=?",String.class,f.assessment)).isEqualTo("legacy evidence");
        assertThat(f.jdbc.queryForObject("SELECT first_started_at IS NULL FROM prepared_paper_gate WHERE assessment_id=?",Boolean.class,f.assessment)).isTrue();
    }
    @Test void initialAppointmentWindowIsCheckedButOriginalRecoveryAfterThatWindowDoesNotRestartOrPunish() {
        publish();f.jdbc.update("UPDATE assessment SET need_appoint=1 WHERE id=?",f.assessment);
        assertThatThrownBy(()->start(key())).isInstanceOf(BusinessException.class);
        f.jdbc.update("INSERT INTO student_assessment_appointment(assessment_id,student_id,time_period,state) VALUES (?,?,?,0)",f.assessment,student,LocalDateTime.ofInstant(now.get().plusSeconds(1),ZoneId.of("Asia/Shanghai")));
        assertThatThrownBy(()->start(key())).isInstanceOf(BusinessException.class);
        now.set(now.get().plusSeconds(1));var original=start(key());
        assertThat(f.jdbc.queryForObject("SELECT state FROM student_assessment_appointment WHERE assessment_id=?",Integer.class,f.assessment)).isEqualTo(1);
        now.set(now.get().plus(Duration.ofDays(10)));assertThat(start(key())).isEqualTo(original);
        assertThat(f.jdbc.queryForObject("SELECT state FROM student_assessment_appointment WHERE assessment_id=?",Integer.class,f.assessment)).isEqualTo(1);
    }
    @Test void expiredAndConflictingAppointmentsAreRejectedWithoutChangingPunishmentOrAttempt() {
        publish();f.jdbc.update("UPDATE assessment SET need_appoint=1 WHERE id=?",f.assessment);
        f.jdbc.update("INSERT INTO student_assessment_appointment(assessment_id,student_id,time_period,state,punish_state) VALUES (?,?,?,0,-1)",f.assessment,student,LocalDateTime.ofInstant(now.get().minusSeconds(601),ZoneId.of("Asia/Shanghai")));
        assertThatThrownBy(()->start(key())).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        f.jdbc.update("UPDATE student_assessment_appointment SET time_period=?,state=2 WHERE assessment_id=?",LocalDateTime.ofInstant(now.get(),ZoneId.of("Asia/Shanghai")),f.assessment);
        assertThatThrownBy(()->start(key())).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(count("prepared_attempt")).isZero();assertThat(count("student_assessment_record")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT punish_state FROM student_assessment_appointment WHERE assessment_id=?",Integer.class,f.assessment)).isEqualTo(-1);
    }
    @Test void currentAssignmentRoleArchiveAndAccessServiceAreAlwaysCheckedEvenForAnExistingReceipt() {
        publish();String key=key();var original=start(key);
        doThrow(new BusinessException(403,"无权限")).when(f.access).requireAssignedStudent(student,f.assessment);
        assertThatThrownBy(()->start(key)).isInstanceOf(BusinessException.class);reset(f.access);
        f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",student);
        assertThatThrownBy(()->start(key)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",student);
        f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?",f.assessment);
        assertThatThrownBy(()->service.current(student,f.assessment)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",f.assessment,student);
        f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",f.assessment);
        assertThatThrownBy(()->start(key)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThat(count("prepared_attempt")).isEqualTo(1);assertThat(original.attemptNo()).isEqualTo(1);
    }
    @Test void invalidCommandCorruptPublicationAndTransactionRollbackLeaveNoStartSideEffects() {
        for(String key:Arrays.asList(null,"not-uuid",UUID.randomUUID().toString().toUpperCase()))
            assertThatThrownBy(()->start(key)).isInstanceOf(IllegalArgumentException.class);
        String version=publish();f.jdbc.update("UPDATE prepared_paper_version SET content_sha256=? WHERE id=?","c".repeat(64),version);
        assertThatThrownBy(()->start(key())).isInstanceOf(IllegalStateException.class);assertThat(count("prepared_attempt")).isZero();
        String content=f.jdbc.queryForObject("SELECT snapshot_json FROM prepared_paper_version WHERE id=?",String.class,version);
        f.jdbc.update("UPDATE prepared_paper_version SET content_sha256=? WHERE id=?",system.assessment.defense.prepared.jobs.PreparedJobInput.digest(system.assessment.defense.prepared.publication.PreparedPublication.encode(system.assessment.defense.prepared.publication.PreparedPublication.decode(content))),version);
        assertThatThrownBy(()->f.tx.execute(s->{service.start(student,f.assessment,new PreparedAttemptStart.Command(key()));throw new IllegalStateException("explicit transaction failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(count("prepared_attempt")).isZero();assertThat(count("student_assessment_record")).isZero();assertThat(count("prepared_attempt_start_receipt")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT first_started_at IS NULL FROM prepared_paper_gate WHERE assessment_id=?",Boolean.class,f.assessment)).isTrue();
    }
    @Test void invalidScopeReceiptConflictCorruptBoundVersionAndForeignScopeAllFailClosedWithoutNewAttempts() {
        String version=publish(),key=key();var original=start(key);
        for(int id:new int[]{0,-1}) {
            assertThatThrownBy(()->service.current(id,f.assessment)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->service.start(student,id,new PreparedAttemptStart.Command(key))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->service.start(student,f.assessment,null)).isInstanceOf(IllegalArgumentException.class);
        var repository=new PreparedAttemptStartRepository(f.jdbc);
        assertThatThrownBy(()->f.tx.execute(s->repository.get(student+100000,f.assessment,original.id()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->repository.boundPaper(f.assessment+100000,version)).isInstanceOf(IllegalStateException.class);
        f.jdbc.update("UPDATE prepared_attempt_start_receipt SET request_hash=? WHERE assessment_id=? AND idempotency_key=?","d".repeat(64),f.assessment,key);
        assertThatThrownBy(()->start(key)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        f.jdbc.update("UPDATE prepared_paper_version SET content_sha256=? WHERE id=?","c".repeat(64),version);
        assertThatThrownBy(()->service.current(student,f.assessment)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->start(key())).isInstanceOf(IllegalStateException.class);assertThat(count("prepared_attempt")).isEqualTo(1);
        assertThatThrownBy(()->f.jdbc.update("UPDATE prepared_attempt SET student_id=? WHERE id=?",f.actor,original.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->f.jdbc.update("UPDATE prepared_attempt_anchor SET student_id=? WHERE assessment_id=?",f.actor,f.assessment)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->f.jdbc.update("UPDATE prepared_attempt SET supplemental_upload_deadline=CURRENT_TIMESTAMP(3) WHERE id=?",original.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).rootCause().isInstanceOf(java.sql.SQLException.class)
                .extracting(error->((java.sql.SQLException)error).getErrorCode()).isEqualTo(3819);
    }
}
