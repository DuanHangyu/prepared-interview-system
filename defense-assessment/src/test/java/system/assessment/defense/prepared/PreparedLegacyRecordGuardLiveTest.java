package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.support.JdbcTransactionManager;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.config.PreparedLegacyRecordGuard;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

/** Actual independent MySQL transactions; no model, storage or student-flow acceptance claim. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedLegacyRecordGuardLiveTest {
    final PreparedQuestionGrantLiveTest g=new PreparedQuestionGrantLiveTest();
    PreparedLegacyRecordGuard guard;
    int legacyAssessment,legacyRecord;
    @BeforeEach void setup() {
        g.setup();guard=new PreparedLegacyRecordGuard(g.f.jdbc,new JdbcTransactionManager(g.f.jdbc.getDataSource()));
        String theme="legacy-guard-"+UUID.randomUUID();
        g.f.jdbc.update("INSERT INTO assessment(theme,create_id,deleted) VALUES (?,?,0)",theme,g.f.actor);
        legacyAssessment=g.f.jdbc.queryForObject("SELECT id FROM assessment WHERE theme=?",Integer.class,theme);
        g.f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",legacyAssessment,g.student);
        g.f.jdbc.update("INSERT INTO student_assessment_record(assessment_id,student_id,state,score,re_analysis,defense_voice) VALUES (?,?,0,-1,0,'')",legacyAssessment,g.student);
        legacyRecord=g.f.jdbc.queryForObject("SELECT id FROM student_assessment_record WHERE assessment_id=?",Integer.class,legacyAssessment);
    }
    @AfterEach void cleanup() {
        g.f.jdbc.update("DELETE FROM student_assessment_record WHERE assessment_id=?",legacyAssessment);
        g.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?",legacyAssessment);
        g.f.jdbc.update("DELETE FROM assessment WHERE id=?",legacyAssessment);g.cleanupOnlyThisFixture();
    }
    @Test void preparedRecordRejectsOldScoreOrRetakeBeforeAnyMutation() {
        var called=new AtomicBoolean();
        assertThatThrownBy(()->guard.record(g.f.actor,g.attempt.recordId(),()->{called.set(true);return true;}))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(called).isFalse();assertThat(g.starts.current(g.student,g.f.assessment).orElseThrow().state()).isEqualTo("IN_PROGRESS");
    }
    @Test void trueLegacyRecordsKeepExistingTeacherWritesWithinTheSameTransaction() {
        assertThat(guard.record(g.f.actor,legacyRecord,()->g.f.jdbc.update("UPDATE student_assessment_record SET check_score=7 WHERE id=?",legacyRecord))).isEqualTo(1);
        assertThat(g.f.jdbc.queryForObject("SELECT check_score FROM student_assessment_record WHERE id=?",Integer.class,legacyRecord)).isEqualTo(7);
        assertThatThrownBy(()->guard.record(g.f.actor,legacyRecord,()->{g.f.jdbc.update("UPDATE student_assessment_record SET state=3 WHERE id=?",legacyRecord);throw new IllegalStateException("fixture abort");})).isInstanceOf(IllegalStateException.class);
        assertThat(g.f.jdbc.queryForObject("SELECT state FROM student_assessment_record WHERE id=?",Integer.class,legacyRecord)).isZero();
    }
    @Test void unauthorizedOrArchivedRecordsDoNotLeakPreparedModeOrInvokeDelegates() {
        var called=new AtomicBoolean();
        assertThatThrownBy(()->guard.record(g.student,g.attempt.recordId(),()->{called.set(true);return true;})).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThatThrownBy(()->guard.record(g.f.actor,Integer.MAX_VALUE,()->{called.set(true);return true;})).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        g.f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",legacyAssessment);
        assertThatThrownBy(()->guard.record(g.f.actor,legacyRecord,()->{called.set(true);return true;})).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);assertThat(called).isFalse();
    }
    @Test void oldMaterialBindingIsRefusedForPreparedPapersButPreservedForAssignedLegacyStudents() {
        assertThatThrownBy(()->guard.material(g.student,g.f.assessment,()->true)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(guard.material(g.student,legacyAssessment,()->"legacy-binding")).isEqualTo("legacy-binding");
        assertThatThrownBy(()->guard.material(g.f.actor,legacyAssessment,()->true)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        g.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?",legacyAssessment);
        assertThatThrownBy(()->guard.material(g.student,legacyAssessment,()->true)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
    }
    @Test void currentTeacherRoleIsRecheckedAfterWaitingForTheAssessmentLock() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var locked=pool.submit(()->g.f.tx.execute(status->{g.f.jdbc.queryForList("SELECT id FROM assessment WHERE id=? FOR UPDATE",legacyAssessment);entered.countDown();try{assertThat(release.await(8,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}return true;}));
            assertThat(entered.await(8,TimeUnit.SECONDS)).isTrue();
            var waiting=pool.submit(()->guard.record(g.f.actor,legacyRecord,()->true));
            g.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",g.f.actor);release.countDown();assertThat(locked.get(8,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->waiting.get(8,TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
        }finally{release.countDown();g.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",g.f.actor);}
    }
}
