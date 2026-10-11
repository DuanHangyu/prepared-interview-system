package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Actual NEW SQL constraints/proofs; original media and grades are explicit fixtures. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAudioIoJournalLiveTest {
    final PreparedAudioIoProtectionLiveTest t=new PreparedAudioIoProtectionLiveTest();@TempDir Path folder;
    @BeforeEach void setup()throws Exception{t.folder=folder;t.setup();}
    @AfterEach void cleanup(){t.cleanup();}
    PreparedOriginalAudioIoJournal.Scope scope(){var e=t.e;var work=e.p.f.service.retentionSources(e.p.student,e.p.assessment,e.p.attempt).sources().getFirst().binding().input();return new PreparedOriginalAudioIoJournal.Scope(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,work.answer(),work.answerRevision()-1,work.manifestSha256());}
    @Test void missingTransactionInvalidScopeAndDetachedOriginalAreRejected(){
        var e=t.e;var journal=new PreparedOriginalAudioIoJournal(e.p.jdbc);var original=scope();
        assertThatThrownBy(()->new PreparedOriginalAudioIoJournal(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->journal.open(original,PreparedOriginalAudioIoJournal.Kind.PREVIEW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->journal.open(null,PreparedOriginalAudioIoJournal.Kind.PREVIEW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->journal.open(original,null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->journal.close(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->journal.inFlight(e.p.student,e.p.assessment,e.p.attempt,List.of())).isInstanceOf(IllegalStateException.class);
        for(int[] ids:new int[][]{{0,e.p.student,e.p.assessment},{e.p.actor,0,e.p.assessment},{e.p.actor,e.p.student,0}})
            assertThatThrownBy(()->new PreparedOriginalAudioIoJournal.Scope(ids[0],ids[1],ids[2],e.p.attempt,e.answer(),1,"a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        for(String hash:Arrays.asList(null,"bad"))assertThatThrownBy(()->new PreparedOriginalAudioIoJournal.Scope(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,e.answer(),1,hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedOriginalAudioIoJournal.Scope(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,e.answer(),0,"a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedOriginalAudioIoJournal.Scope(e.p.actor,e.p.student,e.p.assessment,"bad",e.answer(),1,"a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedOriginalAudioIoJournal.Scope(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,"bad",1,"a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        for(var wrong:List.of(new PreparedOriginalAudioIoJournal.Scope(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,e.answer(),99,original.manifestSha256()),
                new PreparedOriginalAudioIoJournal.Scope(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,e.answer(),1,"f".repeat(64)),
                new PreparedOriginalAudioIoJournal.Scope(e.p.actor,e.p.student,e.p.assessment,e.p.attempt,UUID.randomUUID().toString(),1,"a".repeat(64))))
            e.rollback(()->assertThatThrownBy(()->journal.open(wrong,PreparedOriginalAudioIoJournal.Kind.PREVIEW)).isInstanceOf(IllegalStateException.class));
        assertThat(t.count("ORIGINAL_PREVIEW_IO_OPENED")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"actor","manifest","revision","kind","missing","end-proof","duplicate-close","closed-actor","closed-manifest","closed-time"})
    void damagedCompletionCannotReleaseAnOriginalOperation(String fault){
        var e=t.e;var io=t.open();if(fault.startsWith("closed-")||fault.equals("duplicate-close"))e.p.service.endAudioIo(io);
        e.rollback(()->{
            switch(fault){
                case "actor"->e.p.jdbc.update("UPDATE prepared_audit SET actor_id=? WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",e.p.student,e.p.assessment);
                case "manifest"->e.p.jdbc.update("UPDATE prepared_audit SET resource_sha256=REPEAT('f',64) WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",e.p.assessment);
                case "revision"->e.p.jdbc.update("UPDATE prepared_audit SET resource_version=99 WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",e.p.assessment);
                case "kind"->e.p.jdbc.update("UPDATE prepared_audit SET action='ORIGINAL_ASSEMBLY_IO_OPENED' WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",e.p.assessment);
                case "missing"->e.p.jdbc.update("DELETE FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",e.p.assessment);
                case "end-proof"->e.p.jdbc.update("UPDATE prepared_answer_end SET manifest_sha256=REPEAT('f',64) WHERE assessment_id=? AND answer_id=?",e.p.assessment,e.answer());
                case "duplicate-close"->e.p.jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,resource_id,resource_sha256,original_student_id,original_attempt_id) SELECT assessment_id,actor_id,action,resource_version,resource_id,resource_sha256,original_student_id,original_attempt_id FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_CLOSED'",e.p.assessment);
                case "closed-actor"->e.p.jdbc.update("UPDATE prepared_audit SET actor_id=? WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_CLOSED'",e.p.student,e.p.assessment);
                case "closed-manifest"->e.p.jdbc.update("UPDATE prepared_audit SET resource_sha256=REPEAT('f',64) WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_CLOSED'",e.p.assessment);
                case "closed-time"->e.p.jdbc.update("UPDATE prepared_audit SET created_at=DATE_SUB(created_at,INTERVAL 1 DAY) WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_CLOSED'",e.p.assessment);
                default->throw new AssertionError();
            }
            assertThatThrownBy(()->e.p.service.endAudioIo(io)).isInstanceOf(IllegalStateException.class);
        });e.p.service.endAudioIo(io);assertThat(e.inspect().audioDeleteCandidate()).isTrue();
    }
    @Test void foreignAndPartialOriginalReferencesAreRejectedByRealConstraints(){
        var e=t.e;var io=t.open();String[] foreign=e.otherOriginal();
        try{
            var before=e.p.jdbc.queryForMap("SELECT * FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",e.p.assessment);
            for(String sql:List.of("UPDATE prepared_audit SET original_student_id=NULL WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",
                "UPDATE prepared_audit SET original_attempt_id=NULL WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",
                "UPDATE prepared_audit SET resource_id=NULL WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'")){
                assertConstraint(()->e.p.jdbc.update(sql,e.p.assessment),3819,"HY000");
                assertThat(e.p.jdbc.queryForMap("SELECT * FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",e.p.assessment)).isEqualTo(before);
            }
            assertConstraint(()->e.p.jdbc.update("UPDATE prepared_audit SET original_student_id=2147483647 WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",e.p.assessment),1452,"23000");
            assertConstraint(()->e.p.jdbc.update("UPDATE prepared_audit SET original_attempt_id=? WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",foreign[0],e.p.assessment),1452,"23000");
            assertConstraint(()->e.p.jdbc.update("UPDATE prepared_audit SET resource_id=? WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",foreign[1],e.p.assessment),1452,"23000");
            assertThat(e.p.jdbc.queryForMap("SELECT * FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",e.p.assessment)).isEqualTo(before);
        }finally{e.p.jdbc.update("DELETE FROM prepared_answer WHERE id=?",foreign[1]);e.p.jdbc.update("DELETE FROM prepared_attempt WHERE id=?",foreign[0]);}
        e.p.service.endAudioIo(io);assertThat(e.inspect().audioDeleteCandidate()).isTrue();
    }
    private static void assertConstraint(Runnable update,int code,String state){
        Throwable rejection=catchThrowable(update::run);
        assertThat(rejection).isInstanceOf(org.springframework.dao.DataAccessException.class);
        while(rejection.getCause()!=null)rejection=rejection.getCause();
        assertThat(rejection).isInstanceOf(java.sql.SQLException.class);
        var sql=(java.sql.SQLException)rejection;
        assertThat(sql.getErrorCode()).isEqualTo(code);assertThat(sql.getSQLState()).isEqualTo(state);
    }
    @Test void overBoundOrUnavailableCanonicalEvidenceKeepsOriginal(){
        var e=t.e;var io=t.open();e.rollback(()->assertThatThrownBy(()->new PreparedOriginalAudioIoJournal(e.p.jdbc).inFlight(e.p.student,e.p.assessment,e.p.attempt,List.of())).isInstanceOf(IllegalStateException.class));e.p.service.endAudioIo(io);
        var original=scope();e.rollback(()->{
            var batch=new ArrayList<Object[]>();for(int n=0;n<1001;n++)batch.add(new Object[]{original.assessment(),original.actor(),original.declarationRevision(),original.answer(),original.manifestSha256(),original.student(),original.attempt()});
            e.p.jdbc.batchUpdate("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,resource_id,resource_sha256,original_student_id,original_attempt_id) VALUES (?,?,'ORIGINAL_PREVIEW_IO_OPENED',?,?,?,?,?)",batch);
            assertThatThrownBy(e::inspect).isInstanceOf(IllegalStateException.class);
        });assertThat(e.inspect().audioDeleteCandidate()).isTrue();
    }
}
