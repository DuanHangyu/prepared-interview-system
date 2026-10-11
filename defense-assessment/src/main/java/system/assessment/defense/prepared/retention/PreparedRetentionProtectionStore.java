package system.assessment.defense.prepared.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.processing.PreparedProcessingCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import static system.assessment.defense.prepared.retention.PreparedRetentionProtection.*;

/** Caller holds the existing assessment parent lock in one transaction. Receipts
 * retain original scope and hashes, not duplicate answer or full-request content. */
final class PreparedRetentionProtectionStore {
    private final JdbcTemplate jdbc;
    PreparedRetentionProtectionStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    record Guard(long revision,boolean held,String audio,String results,String last,String updated){}
    private record Request(int actor,int student,int assessment,String attempt,Command command){}
    record Stored(Receipt receipt,String requestSha,String receiptSha){}
    static String requestHash(int actor,int student,int assessment,String attempt,Command command){
        return PreparedJobInput.digest(PreparedProcessingCodec.encode(new Request(actor,student,assessment,attempt,command)));
    }
    Optional<Guard> guard(int student,int assessment,String attempt){
        var guard=jdbc.query("SELECT * FROM prepared_retention_guard WHERE assessment_id=? AND student_id=? AND attempt_id=? FOR UPDATE",
            (rs,n)->new Guard(rs.getLong("revision"),rs.getBoolean("held"),rs.getString("audio_state"),rs.getString("results_state"),rs.getString("last_command_id"),rs.getTimestamp("updated_at").toInstant().toString()),assessment,student,attempt).stream().findFirst();
        if(guard.isEmpty())requireNoHistory(student,assessment,attempt);
        new PreparedAudioRetirementPreparationStore(jdbc).requireConsistent(student,assessment,attempt,guard);
        return guard;
    }
    Optional<Stored> command(int actor,int assessment,String key){
        return jdbc.query("SELECT * FROM prepared_retention_command WHERE assessment_id=? AND actor_id=? AND idempotency_key=? FOR UPDATE",(rs,n)->row(rs),assessment,actor,key).stream().findFirst();
    }
    Receipt verify(Stored stored){
        var value=stored.receipt();
        try {
            PreparedJobInput.uuid(value.id());PreparedJobInput.uuid(value.attemptId());
            var command=new Command(value.idempotencyKey(),value.expectedRevision(),value.action(),value.reason());
            if(value.actorId()<1||value.studentId()<1||value.assessmentId()<1||value.revision()!=value.expectedRevision()+1
                ||value.held()!=value.action().equals("PROTECT")||!"RETAINED".equals(value.resultsState())
                ||!("RETAINED".equals(value.audioState())||"RETIRED".equals(value.audioState()))
                ||!requestHash(value.actorId(),value.studentId(),value.assessmentId(),value.attemptId(),command).equals(stored.requestSha())
                ||!PreparedJobInput.digest(PreparedProcessingCodec.encode(value)).equals(stored.receiptSha()))throw invalid();
            return value;
        }catch(IllegalArgumentException failure){throw invalid();}
    }
    Receipt latest(int student,int assessment,String attempt,Guard guard){
        if(guard.last()==null){if(guard.held())throw invalid();requireNoHistory(student,assessment,attempt);return null;}
        var stored=jdbc.query("SELECT * FROM prepared_retention_command WHERE assessment_id=? AND student_id=? AND attempt_id=? AND id=? FOR UPDATE",(rs,n)->row(rs),assessment,student,attempt,guard.last()).stream().findFirst().orElseThrow(PreparedRetentionProtectionStore::invalid);
        var receipt=verify(stored);
        var latest=jdbc.queryForList("SELECT id FROM prepared_retention_command WHERE assessment_id=? AND student_id=? AND attempt_id=? ORDER BY revision DESC LIMIT 1 FOR UPDATE",String.class,assessment,student,attempt);
        // A valid old RELEASE receipt cannot hide a newer immutable PROTECT.
        // Lifecycle revisions may advance separately, but the command pointer
        // must still name the latest command in this same original scope.
        if(latest.size()!=1||!receipt.id().equals(latest.getFirst())||receipt.revision()>guard.revision()||receipt.held()!=guard.held())throw invalid();
        return receipt;
    }
    private void requireNoHistory(int student,int assessment,String attempt){
        // Missing lazy state means "never manually managed" ONLY when no original
        // commands exist. A lost/detached pointer must not silently remove a hold.
        if(!jdbc.queryForList("SELECT id FROM prepared_retention_command WHERE assessment_id=? AND student_id=? AND attempt_id=? LIMIT 1 FOR UPDATE",String.class,assessment,student,attempt).isEmpty())throw invalid();
    }
    void save(Receipt receipt,Command command,boolean existing){
        jdbc.update("""
            INSERT INTO prepared_retention_command(id,assessment_id,student_id,attempt_id,actor_id,action,expected_revision,
                revision,held,executed_at,reason,idempotency_key,audio_state,results_state,request_sha256,receipt_sha256)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,receipt.id(),receipt.assessmentId(),receipt.studentId(),receipt.attemptId(),receipt.actorId(),receipt.action(),receipt.expectedRevision(),receipt.revision(),receipt.held(),Timestamp.from(java.time.Instant.parse(receipt.executedAt())),receipt.reason(),receipt.idempotencyKey(),receipt.audioState(),receipt.resultsState(),requestHash(receipt.actorId(),receipt.studentId(),receipt.assessmentId(),receipt.attemptId(),command),PreparedJobInput.digest(PreparedProcessingCodec.encode(receipt)));
        var time=Timestamp.from(java.time.Instant.parse(receipt.executedAt()));
        if(existing){
            if(jdbc.update("""
                UPDATE prepared_retention_guard SET revision=?,held=?,last_command_id=?,updated_at=?
                WHERE assessment_id=? AND student_id=? AND attempt_id=? AND revision=?
                    AND audio_state<>'RETIRING' AND results_state='RETAINED'
                """,receipt.revision(),receipt.held(),receipt.id(),time,receipt.assessmentId(),receipt.studentId(),receipt.attemptId(),receipt.expectedRevision())!=1)throw invalid();
        }else jdbc.update("""
            INSERT INTO prepared_retention_guard(attempt_id,assessment_id,student_id,revision,held,last_command_id,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,receipt.attemptId(),receipt.assessmentId(),receipt.studentId(),receipt.revision(),receipt.held(),receipt.id(),time,time);
    }
    private static Stored row(ResultSet rs)throws SQLException{
        return new Stored(new Receipt(rs.getString("id"),rs.getInt("actor_id"),rs.getInt("student_id"),rs.getInt("assessment_id"),rs.getString("attempt_id"),rs.getString("action"),rs.getLong("expected_revision"),rs.getLong("revision"),rs.getBoolean("held"),rs.getTimestamp("executed_at").toInstant().toString(),rs.getString("reason"),rs.getString("idempotency_key"),rs.getString("audio_state"),rs.getString("results_state")),rs.getString("request_sha256"),rs.getString("receipt_sha256"));
    }
    private static IllegalStateException invalid(){return new IllegalStateException("原留存保护状态或回执绑定无法核验");}
}
