package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.processing.PreparedRecordBridge;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real isolated MySQL classification/permissions. No model, cloud or final QA claim. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedRecordBridgeLiveTest {
    final PreparedLegacyRecordGuardLiveTest f=new PreparedLegacyRecordGuardLiveTest();
    PreparedRecordBridge bridge;final List<Integer> users=new ArrayList<>();
    @BeforeEach void setup(){f.setup();bridge=new PreparedRecordBridge(f.g.f.jdbc,f.guard);}
    @AfterEach void cleanup(){for(int id:users){f.g.f.jdbc.update("DELETE FROM assessment_invite WHERE invitee_teacher_id=?",id);f.g.f.jdbc.update("DELETE FROM `user` WHERE id=?",id);}f.cleanup();}
    int user(int role){String account="prepared-record-bridge-"+UUID.randomUUID();f.g.f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('记录权限验证',?,'disabled-test',?)",account,role);int id=f.g.f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);users.add(id);return id;}
    @Test void preparedMetadataAndRealLegacyZeroStayDistinctWithoutMutatingAttempts(){
        var rows=bridge.managerRecords(f.g.f.actor,List.of(f.g.attempt.recordId(),f.legacyRecord,f.legacyRecord));
        assertThat(rows).hasSize(2);var prepared=rows.get(f.g.attempt.recordId());assertThat(prepared.prepared()).isTrue();assertThat(prepared.assessmentId()).isEqualTo(f.g.f.assessment);assertThat(prepared.state()).isEqualTo("IN_PROGRESS");
        assertThat(rows.get(f.legacyRecord).prepared()).isFalse();assertThat(rows.get(f.legacyRecord).state()).isNull();
        f.g.f.tx.executeWithoutResult(s->{f.g.f.jdbc.update("UPDATE prepared_attempt SET state='REQUIRES_REVIEW' WHERE id=?",f.g.attempt.id());assertThat(bridge.managerRecords(f.g.f.actor,List.of(f.g.attempt.recordId())).get(f.g.attempt.recordId()).state()).isEqualTo("REQUIRES_REVIEW");s.setRollbackOnly();});
        assertThat(f.g.starts.current(f.g.student,f.g.f.assessment).orElseThrow().state()).isEqualTo("IN_PROGRESS");
        bridge.requireLegacyExport(f.g.f.actor,List.of(f.legacyRecord));
        assertThatThrownBy(()->bridge.requireLegacyExport(f.g.f.actor,List.of(f.legacyRecord,f.g.attempt.recordId()))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
    }
    @Test void currentManagerRoleInvitationAndArchivedScopeAreRecheckedBeforeModeDisclosure(){
        int invited=user(1),admin=user(2);var ids=List.of(f.g.attempt.recordId());
        for(int actor:new int[]{f.g.student,invited,Integer.MAX_VALUE})denied(()->bridge.managerRecords(actor,ids));
        f.g.f.jdbc.update("INSERT INTO assessment_invite(assessment_id,invitee_teacher_id,inviter_teacher_id) VALUES (?,?,?)",f.g.f.assessment,invited,f.g.f.actor);
        assertThat(bridge.managerRecords(invited,ids)).hasSize(1);assertThat(bridge.managerRecords(admin,ids)).hasSize(1);
        f.g.f.jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=? AND invitee_teacher_id=?",f.g.f.assessment,invited);denied(()->bridge.managerRecords(invited,ids));
        f.g.f.tx.executeWithoutResult(s->{f.g.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.g.f.actor);denied(()->bridge.managerRecords(f.g.f.actor,ids));s.setRollbackOnly();});
        f.g.f.tx.executeWithoutResult(s->{f.g.f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",f.g.f.assessment);denied(()->bridge.managerRecords(admin,ids));s.setRollbackOnly();});
        denied(()->bridge.managerRecords(f.g.f.actor,List.of(Integer.MAX_VALUE)));
    }
    @Test void boundedIdentityAndCorruptedPreparedAnchorCannotFallBackToLegacy(){
        assertThat(bridge.managerRecords(f.g.f.actor,List.of())).isEmpty();bridge.requireManagerIdentity(f.g.f.actor);denied(()->bridge.requireManagerIdentity(f.g.student));
        for(var ids:Arrays.asList(null,List.of(0),List.of(-1),Arrays.asList((Integer)null),Collections.nCopies(1001,f.legacyRecord)))assertThatThrownBy(()->bridge.managerRecords(f.g.f.actor,ids)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->bridge.managerRecords(0,List.of(f.legacyRecord))).isInstanceOf(IllegalArgumentException.class);
        f.g.f.tx.executeWithoutResult(s->{f.g.f.jdbc.update("DELETE FROM prepared_attempt_anchor WHERE assessment_id=?",f.g.f.assessment);assertThatThrownBy(()->bridge.managerRecords(f.g.f.actor,List.of(f.g.attempt.recordId()))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);s.setRollbackOnly();});
    }
    @Test void legacyStudentReportGuardPreservesTrueLegacyButRejectsPreparedBeforeDelegate(){
        assertThat(f.guard.studentDetail(f.g.student,f.legacyAssessment,()->"true-legacy")).isEqualTo("true-legacy");
        assertThatThrownBy(()->f.guard.studentDetail(f.g.student,f.g.f.assessment,()->{throw new AssertionError("prepared legacy report must not run");})).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        denied(()->f.guard.studentDetail(f.g.f.actor,f.legacyAssessment,()->true));denied(()->f.guard.studentDetail(f.g.student,Integer.MAX_VALUE,()->true));
        f.g.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?",f.legacyAssessment);denied(()->f.guard.studentDetail(f.g.student,f.legacyAssessment,()->true));
        assertThatThrownBy(()->f.guard.studentDetail(0,f.legacyAssessment,()->true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyExportRechecksCurrentRoleAfterWaitingForParentLock()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var locked=pool.submit(()->f.g.f.tx.execute(s->{f.g.f.jdbc.queryForList("SELECT id FROM assessment WHERE id=? FOR UPDATE",f.legacyAssessment);entered.countDown();try{assertThat(release.await(8,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}return true;}));
            assertThat(entered.await(8,TimeUnit.SECONDS)).isTrue();var waiting=pool.submit(()->{bridge.requireLegacyExport(f.g.f.actor,List.of(f.legacyRecord));return true;});
            f.g.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.g.f.actor);release.countDown();assertThat(locked.get(8,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->waiting.get(8,TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
        }finally{release.countDown();f.g.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.g.f.actor);}
    }
    @Test void boundedBatchReportGuardCannotRebindOrDelegatePreparedRecords(){
        assertThat(f.guard.records(f.g.f.actor,Map.of(),()->"empty-legacy")).isEqualTo("empty-legacy");denied(()->f.guard.records(f.g.student,Map.of(),()->true));
        assertThat(f.guard.records(f.g.f.actor,Map.of(f.legacyRecord,f.legacyAssessment),()->"legacy-only")).isEqualTo("legacy-only");
        denied(()->f.guard.records(f.g.f.actor,Map.of(f.legacyRecord,f.g.f.assessment),()->true));
        assertThatThrownBy(()->f.guard.records(f.g.f.actor,Map.of(f.legacyRecord,f.legacyAssessment,f.g.attempt.recordId(),f.g.f.assessment),()->{throw new AssertionError("No prepared export delegate");})).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThatThrownBy(()->f.guard.records(0,Map.of(f.legacyRecord,f.legacyAssessment),()->true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.guard.records(f.g.f.actor,Map.of(f.legacyRecord,0),()->true)).isInstanceOf(IllegalArgumentException.class);
    }
    static void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}
}
