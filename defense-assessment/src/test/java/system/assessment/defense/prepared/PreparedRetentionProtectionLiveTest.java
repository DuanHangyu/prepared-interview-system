package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.PreparedAttemptStartRepository;
import system.assessment.defense.prepared.retention.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual MySQL protection, original bindings and transactions. Synthetic paper assets
 * are inherited fixtures; no cleanup worker, deletion, cloud or media capture is tested. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedRetentionProtectionLiveTest {
    final PreparedQuestionGrantLiveTest f=new PreparedQuestionGrantLiveTest();
    PreparedRetentionProtectionService service;
    final List<Integer> users=new ArrayList<>();
    @BeforeEach void setup(){
        f.setup();service=new PreparedRetentionProtectionService(f.f.access,new PreparedAttemptStartRepository(f.f.jdbc),f.f.jdbc,f.f.tx.getTransactionManager(),
            new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return f.now.get();}});
    }
    @AfterEach void cleanup(){
        f.f.jdbc.update("DELETE FROM prepared_retention_guard WHERE assessment_id=?",f.f.assessment);
        f.f.jdbc.update("DELETE FROM prepared_retention_command WHERE assessment_id=?",f.f.assessment);
        f.f.jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=?",f.f.assessment);
        f.cleanupOnlyThisFixture();for(int user:users)f.f.jdbc.update("DELETE FROM `user` WHERE id=?",user);
    }
    PreparedRetentionProtection.View status(){return service.status(f.f.actor,f.student,f.f.assessment,f.attempt.id());}
    PreparedRetentionProtection.Receipt execute(PreparedRetentionProtection.Command command){return service.execute(f.f.actor,f.student,f.f.assessment,f.attempt.id(),command);}
    PreparedRetentionProtection.Command command(long revision,String action){return new PreparedRetentionProtection.Command(UUID.randomUUID().toString(),revision,action,"人工核验原尝试；不延长答题或补传时间");}
    void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}
    void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);}
    int user(int type){String account="prepared-retention-"+UUID.randomUUID();f.f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('留存保护验证',?,'not-a-login-credential',?)",account,type);int id=f.f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);users.add(id);return id;}

    @Test void originalProtectionAndReleaseNeverChangeRecordingDeadlineAttemptOrResultAnchor(){
        var question=f.grant(1);var original=f.f.jdbc.queryForMap("SELECT * FROM prepared_attempt WHERE id=?",f.attempt.id());
        assertThat(status().revision()).isZero();assertThat(status().held()).isFalse();assertThat(f.count("prepared_retention_guard")).isZero();
        var protectedReceipt=execute(command(0,"PROTECT"));assertThat(protectedReceipt.held()).isTrue();assertThat(status().revision()).isEqualTo(1);assertThat(status().latest()).isEqualTo(protectedReceipt);
        var released=execute(command(1,"RELEASE"));assertThat(released.held()).isFalse();assertThat(status().latest()).isEqualTo(released);assertThat(status().revision()).isEqualTo(2);
        assertThat(f.f.jdbc.queryForMap("SELECT * FROM prepared_attempt WHERE id=?",f.attempt.id())).isEqualTo(original);
        assertThat(f.grants.current(f.student,f.f.assessment,f.attempt.id(),f.client).orElseThrow().answerDeadline()).isEqualTo(question.answerDeadline());
        assertThat(f.count("prepared_retention_command")).isEqualTo(2);assertThat(f.count("prepared_retention_guard")).isEqualTo(1);
        assertThat(status().audioState()).isEqualTo("RETAINED");assertThat(status().resultsState()).isEqualTo("RETAINED");
    }
    @Test void oneHundredOriginalRetriesAfterReleaseReturnOriginalReceiptNotLatestState(){
        var command=command(0,"PROTECT");var first=execute(command);execute(command(1,"RELEASE"));
        for(int n=0;n<100;n++)assertThat(execute(command)).isEqualTo(first);
        assertThat(status().held()).isFalse();assertThat(f.count("prepared_retention_command")).isEqualTo(2);
        conflict(()->execute(new PreparedRetentionProtection.Command(command.idempotencyKey(),0,"PROTECT","不能同键更换原因")));
        conflict(()->execute(new PreparedRetentionProtection.Command(command.idempotencyKey(),1,"RELEASE",command.reason())));
    }
    @Test void staleVersionAndNewNoOpKeysCannotOverwriteProtection(){
        conflict(()->execute(command(0,"RELEASE")));execute(command(0,"PROTECT"));
        conflict(()->execute(command(0,"RELEASE")));conflict(()->execute(command(1,"PROTECT")));
        execute(command(1,"RELEASE"));conflict(()->execute(command(2,"RELEASE")));assertThat(f.count("prepared_retention_command")).isEqualTo(2);
    }
    @Test void eightConcurrentCommandsOnlyOneWinsAndSameOriginalKeyOnlyHasOneReceipt()throws Exception{
        var outcomes=new ArrayList<Future<Object>>();var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            for(int n=0;n<8;n++){var command=command(0,"PROTECT");outcomes.add(pool.submit(()->{gate.await();try{return execute(command);}catch(BusinessException failure){return failure.getCode();}}));}
            gate.countDown();var values=new ArrayList<Object>();for(var outcome:outcomes)values.add(outcome.get(15,TimeUnit.SECONDS));
            assertThat(values.stream().filter(v->v instanceof PreparedRetentionProtection.Receipt).count()).isEqualTo(1);
            assertThat(values.stream().filter(v->Integer.valueOf(409).equals(v)).count()).isEqualTo(7);
        }
        var original=command(1,"RELEASE");var same=new ArrayList<Future<PreparedRetentionProtection.Receipt>>();var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            for(int n=0;n<8;n++)same.add(pool.submit(()->{start.await();return execute(original);}));start.countDown();
            var first=same.getFirst().get(15,TimeUnit.SECONDS);for(var value:same)assertThat(value.get(15,TimeUnit.SECONDS)).isEqualTo(first);
        }assertThat(f.count("prepared_retention_command")).isEqualTo(2);
    }
    @Test void freshRoleInviteArchiveAndScopeAreRequiredForReadMutationAndReplay(){
        int stranger=user(1),admin=user(2);var command=command(0,"PROTECT");var first=execute(command);
        denied(()->service.status(f.student,f.student,f.f.assessment,f.attempt.id()));denied(()->service.execute(stranger,f.student,f.f.assessment,f.attempt.id(),command));
        denied(()->service.status(f.f.actor,stranger,f.f.assessment,f.attempt.id()));denied(()->service.status(f.f.actor,f.student,f.f.assessment,UUID.randomUUID().toString()));
        f.f.jdbc.update("INSERT INTO assessment_invite(assessment_id,invitee_teacher_id,inviter_teacher_id) VALUES (?,?,?)",f.f.assessment,stranger,f.f.actor);
        assertThat(service.status(stranger,f.student,f.f.assessment,f.attempt.id()).held()).isTrue();
        f.f.jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=? AND invitee_teacher_id=?",f.f.assessment,stranger);
        denied(()->service.status(stranger,f.student,f.f.assessment,f.attempt.id()));
        assertThat(service.status(admin,f.student,f.f.assessment,f.attempt.id()).latest()).isEqualTo(first);
        f.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.f.actor);
        try{denied(this::status);denied(()->execute(command));}finally{f.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.f.actor);}
        f.f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",f.f.assessment);
        try{denied(this::status);denied(()->execute(command));}finally{f.f.jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",f.f.assessment);}
        assertThat(execute(command)).isEqualTo(first);
    }
    @Test void holdIsManagementOfOriginalDataNotPermissionToRestartRevokedStudent(){
        f.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",f.f.assessment,f.student);
        var receipt=execute(command(0,"PROTECT"));assertThat(receipt.held()).isTrue();
        denied(()->f.grant(1));assertThat(status().held()).isTrue();
    }
    @Test void transactionFailureRollsBackGuardAndCommandTogether(){
        assertThatThrownBy(()->f.f.tx.executeWithoutResult(s->{execute(command(0,"PROTECT"));throw new IllegalStateException("owned rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(f.count("prepared_retention_guard")).isZero();assertThat(f.count("prepared_retention_command")).isZero();assertThat(status().revision()).isZero();
    }
    @Test void retiringFixtureCannotBeProtectedAndRetiredAudioIsNeverFalselyRestored(){
        execute(command(0,"PROTECT"));execute(command(1,"RELEASE"));
        // Explicit lifecycle fixtures, NOT an implemented cleanup or actual object deletion.
        for(String column:List.of("audio_state","results_state")){
            f.f.jdbc.update("UPDATE prepared_retention_guard SET "+column+"='RETIRING',revision=revision+1 WHERE attempt_id=?",f.attempt.id());
            assertThat(status().mayChange()).isFalse();conflict(()->execute(command(status().revision(),"PROTECT")));
            f.f.jdbc.update("UPDATE prepared_retention_guard SET "+column+"='RETAINED' WHERE attempt_id=?",f.attempt.id());
        }
        f.f.jdbc.update("UPDATE prepared_retention_guard SET audio_state='RETIRED' WHERE attempt_id=?",f.attempt.id());
        var receipt=execute(command(status().revision(),"PROTECT"));assertThat(receipt.audioState()).isEqualTo("RETIRED");assertThat(status().audioState()).isEqualTo("RETIRED");
        assertThatThrownBy(()->f.f.jdbc.update("UPDATE prepared_retention_guard SET audio_state='RETIRING' WHERE attempt_id=?",f.attempt.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void corruptCommandHashOrGuardPointerFailsClosed(){
        var command=command(0,"PROTECT");var receipt=execute(command);var jdbc=f.f.jdbc;
        for(String column:List.of("request_sha256","receipt_sha256")){
            String original=jdbc.queryForObject("SELECT "+column+" FROM prepared_retention_command WHERE id=?",String.class,receipt.id());
            jdbc.update("UPDATE prepared_retention_command SET "+column+"=? WHERE id=?","a".repeat(64),receipt.id());
            try{assertThatThrownBy(this::status).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->execute(command)).isInstanceOf(IllegalStateException.class);}
            finally{jdbc.update("UPDATE prepared_retention_command SET "+column+"=? WHERE id=?",original,receipt.id());}
        }
        jdbc.update("UPDATE prepared_retention_guard SET held=0 WHERE attempt_id=?",f.attempt.id());
        assertThatThrownBy(this::status).isInstanceOf(IllegalStateException.class);assertThat(f.count("prepared_retention_command")).isEqualTo(1);
    }
    @Test void invalidInputsAndCrossScopeForeignKeysCannotCreateProtection(){
        assertThatThrownBy(()->service.status(0,f.student,f.f.assessment,f.attempt.id())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.status(f.f.actor,f.student,0,f.attempt.id())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.status(f.f.actor,f.student,f.f.assessment,"wrong")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->execute(null)).isInstanceOf(IllegalArgumentException.class);
        var receipt=execute(command(0,"PROTECT"));
        assertThatThrownBy(()->f.f.jdbc.update("UPDATE prepared_retention_guard SET student_id=? WHERE attempt_id=?",f.f.actor,f.attempt.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->f.f.jdbc.update("UPDATE prepared_retention_command SET student_id=? WHERE id=?",f.f.actor,receipt.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->f.f.jdbc.update("UPDATE prepared_retention_guard SET revision=-1 WHERE attempt_id=?",f.attempt.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(f.count("prepared_retention_command")).isEqualTo(1);
    }
    @Test void missingOrDetachedGuardCannotLookUnprotectedOrReturnSuccessfulOldProtection(){
        var command=command(0,"PROTECT");execute(command);
        for(String fault:List.of("DELETE FROM prepared_retention_guard WHERE attempt_id=?","UPDATE prepared_retention_guard SET held=0,last_command_id=NULL WHERE attempt_id=?")){
            f.f.tx.executeWithoutResult(s->{
                f.f.jdbc.update(fault,f.attempt.id());
                assertThatThrownBy(this::status).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(()->execute(command)).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();
            });
        }assertThat(status().held()).isTrue();assertThat(f.count("prepared_retention_command")).isEqualTo(1);
    }
    @Test void historicalOriginalMayBeProtectedButOriginalCommandCannotBeReboundToAnotherAttempt(){
        var command=command(0,"PROTECT");var original=execute(command);String second=UUID.randomUUID().toString();
        // A historical-pointer fixture, NOT a user retake authorization endpoint.
        f.f.jdbc.update("INSERT INTO prepared_attempt(id,record_id,assessment_id,student_id,attempt_no,paper_version_id,started_at) VALUES (?,?,?,?,2,?,?)",second,f.attempt.recordId(),f.f.assessment,f.student,f.attempt.paperVersionId(),java.sql.Timestamp.from(f.now.get()));
        f.f.jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",second,f.f.assessment,f.student);
        assertThat(status().latest()).isEqualTo(original);conflict(()->service.execute(f.f.actor,f.student,f.f.assessment,second,command));
        var other=service.execute(f.f.actor,f.student,f.f.assessment,second,command(0,"PROTECT"));assertThat(other.attemptId()).isEqualTo(second);
        assertThat(execute(command)).isEqualTo(original);assertThat(f.count("prepared_retention_guard")).isEqualTo(2);
    }
    @Test void regressingClockAndExhaustedRevisionNeverChangeOriginalProtection(){
        f.now.set(Instant.parse(f.attempt.startedAt()).minusMillis(1));
        assertThatThrownBy(()->execute(command(0,"PROTECT"))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        f.now.set(Instant.parse(f.attempt.startedAt()));var command=command(0,"PROTECT");execute(command);f.now.set(f.now.get().minusMillis(1));
        assertThat(execute(command).held()).isTrue();assertThatThrownBy(()->execute(command(1,"RELEASE"))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        f.f.jdbc.update("UPDATE prepared_retention_guard SET revision=9007199254740991 WHERE attempt_id=?",f.attempt.id());
        assertThat(status().mayChange()).isFalse();conflict(()->execute(command(9007199254740990L,"RELEASE")));assertThat(f.count("prepared_retention_command")).isEqualTo(1);
    }
}
