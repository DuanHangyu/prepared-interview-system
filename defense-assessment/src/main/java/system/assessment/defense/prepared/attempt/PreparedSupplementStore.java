package system.assessment.defense.prepared.attempt;

import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.processing.PreparedProcessingCodec;
import static system.assessment.defense.prepared.attempt.PreparedSupplement.*;

/** Internal append-only approval proofs. Callers hold the original assessment/current-answer lock. */
final class PreparedSupplementStore {
    record Frozen(int schemaVersion,Receipt receipt,long declarationRevision,Command command){}
    record Row(Receipt receipt,long declarationRevision,String input,String inputSha,String requestSha){}
    private final JdbcTemplate jdbc;
    PreparedSupplementStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    Optional<Row> latest(int student,int assessment,String attempt,String answer){return jdbc.query("SELECT * FROM prepared_supplement_grant WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? ORDER BY approval_revision DESC LIMIT 1 FOR UPDATE",(rs,n)->row(rs),assessment,student,attempt,answer).stream().findFirst();}
    Optional<Row> command(int actor,int assessment,String key){return jdbc.query("SELECT * FROM prepared_supplement_grant WHERE assessment_id=? AND actor_id=? AND idempotency_key=? FOR UPDATE",(rs,n)->row(rs),assessment,actor,key).stream().findFirst();}
    static String requestHash(int actor,int student,int assessment,String attempt,String answer,Command c){return PreparedJobInput.digest("prepared-supplement-v1:"+actor+":"+student+":"+assessment+":"+attempt+":"+answer+":"+PreparedProcessingCodec.encode(c));}
    Receipt verify(Row row,PreparedAnswerUploadService.Bound bound){
        try{
            var frozen=PreparedProcessingCodec.decode(row.input(),Frozen.class);var r=row.receipt();var c=frozen.command();
            var end=bound.end().orElseThrow();var attempt=bound.attempt();var answer=bound.answer();
            if(frozen.schemaVersion()!=1||!r.equals(frozen.receipt())||row.declarationRevision()!=frozen.declarationRevision()
                    ||!PreparedJobInput.digest(row.input()).equals(row.inputSha())||!PreparedProcessingCodec.encode(frozen).equals(row.input())
                    ||!requestHash(r.actorId(),r.studentId(),r.assessmentId(),r.attemptId(),r.answerId(),c).equals(row.requestSha())
                    ||!r.idempotencyKey().equals(c.idempotencyKey())||r.approvalRevision()!=c.expectedRevision()+1||r.attemptRevision()!=c.expectedAttemptRevision()+1
                    ||!r.reason().equals(c.reason())||!r.uploadDeadline().equals(c.uploadDeadline())||!r.manifestSha256().equals(c.manifestSha256())
                    ||!r.answerId().equals(answer.id())||!r.attemptId().equals(attempt.id())||r.assessmentId()!=attempt.assessmentId()
                    ||!r.paperVersionId().equals(attempt.paperVersionId())||!r.manifestSha256().equals(end.hash())||row.declarationRevision()!=end.revision()
                    ||!r.answerDeadline().equals(answer.deadline().toString())||!Objects.equals(r.answeringEndedAt(),attempt.answeringEndedAt())
                    ||!Objects.equals(r.automaticDeadline(),attempt.supplementalUploadDeadline())||!end.reasons().isEmpty()
                    ||attempt.revision()<r.attemptRevision()||r.actorId()<1||r.studentId()<1
                    ||Instant.parse(r.approvedAt()).isBefore(Instant.parse(r.automaticDeadline()))
                    ||!deadline(r.uploadDeadline()).isAfter(Instant.parse(r.approvedAt())))throw new IllegalStateException();
            PreparedJobInput.uuid(r.id());return r;
        }catch(RuntimeException invalid){throw new IllegalStateException("原补传审批证明无法核验，保留原录音并联系教师");}
    }
    Receipt create(Receipt receipt,long declarationRevision,Command command){
        String input=PreparedProcessingCodec.encode(new Frozen(1,receipt,declarationRevision,command));
        jdbc.update("""
            INSERT INTO prepared_supplement_grant(id,assessment_id,student_id,attempt_id,answer_id,paper_version_id,actor_id,
                approval_revision,attempt_revision,manifest_sha256,declaration_revision,answer_deadline,answering_ended_at,
                automatic_deadline,approved_at,upload_deadline,reason,idempotency_key,request_sha256,input_json,input_sha256)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,receipt.id(),receipt.assessmentId(),receipt.studentId(),receipt.attemptId(),receipt.answerId(),receipt.paperVersionId(),receipt.actorId(),receipt.approvalRevision(),receipt.attemptRevision(),receipt.manifestSha256(),declarationRevision,
            time(receipt.answerDeadline()),time(receipt.answeringEndedAt()),time(receipt.automaticDeadline()),time(receipt.approvedAt()),time(receipt.uploadDeadline()),receipt.reason(),receipt.idempotencyKey(),
            requestHash(receipt.actorId(),receipt.studentId(),receipt.assessmentId(),receipt.attemptId(),receipt.answerId(),command),input,PreparedJobInput.digest(input));
        return receipt;
    }
    private static Row row(ResultSet rs)throws SQLException{
        var receipt=new Receipt(rs.getString("id"),rs.getInt("actor_id"),rs.getInt("student_id"),rs.getInt("assessment_id"),rs.getString("attempt_id"),rs.getString("answer_id"),rs.getString("paper_version_id"),rs.getLong("approval_revision"),rs.getLong("attempt_revision"),rs.getString("manifest_sha256"),instant(rs,"answer_deadline"),instant(rs,"answering_ended_at"),instant(rs,"automatic_deadline"),instant(rs,"approved_at"),instant(rs,"upload_deadline"),rs.getString("reason"),rs.getString("idempotency_key"));
        return new Row(receipt,rs.getLong("declaration_revision"),rs.getString("input_json"),rs.getString("input_sha256"),rs.getString("request_sha256"));
    }
    private static String instant(ResultSet rs,String field)throws SQLException{return rs.getTimestamp(field).toInstant().toString();}
    private static Timestamp time(String value){return Timestamp.from(Instant.parse(value));}
}
