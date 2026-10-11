package system.assessment.defense.prepared.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static system.assessment.defense.prepared.attempt.PreparedAnswerEnd.*;

@Repository
public class PreparedAnswerEndRepository {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    public PreparedAnswerEndRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Head(String answer,String hash,String manifest,String state,List<String> reasons,long bytes,long revision,Instant ended,Instant accepted) {}
    public record Receipt(String hash,String answer) {}
    public Optional<Receipt> receipt(int student,int assessment,String attempt,String key) {
        return jdbc.query("SELECT request_hash,answer_id FROM prepared_answer_end_receipt WHERE assessment_id=? AND student_id=? AND attempt_id=? AND idempotency_key=? FOR UPDATE",
                (rs,n)->new Receipt(rs.getString(1),rs.getString(2)),assessment,student,attempt,key).stream().findFirst();
    }
    public Optional<Head> head(int student,int assessment,String attempt,String answer) {
        return jdbc.query("SELECT * FROM prepared_answer_end WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? FOR UPDATE",
                (rs,n)->head(rs),assessment,student,attempt,answer).stream().findFirst();
    }
    public List<Head> heads(int student,int assessment,String attempt) {
        return jdbc.query("SELECT * FROM prepared_answer_end WHERE assessment_id=? AND student_id=? AND attempt_id=? FOR UPDATE",(rs,n)->head(rs),assessment,student,attempt);
    }
    public Head create(int student,int assessment,String attempt,String answer,Proof proof,Instant ended,Instant accepted) {
        String state=proof.reviewReasons().isEmpty()?"UPLOAD_PENDING":"REQUIRES_REVIEW";
        if(jdbc.update("UPDATE prepared_answer SET state=?,revision=revision+1 WHERE id=? AND assessment_id=? AND student_id=? AND attempt_id=? AND state='RECORDING'",state,answer,assessment,student,attempt)!=1)
            throw new IllegalStateException("原回答结束状态无法核验");
        Long revision=jdbc.queryForObject("SELECT revision FROM prepared_answer WHERE id=? FOR UPDATE",Long.class,answer);
        jdbc.update("""
                INSERT INTO prepared_answer_end(answer_id,assessment_id,student_id,attempt_id,manifest_sha256,manifest_json,declaration_state,review_reasons,byte_size,answer_revision,effective_ended_at,accepted_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,answer,assessment,student,attempt,proof.sha256(),proof.json(),state,reasonsJson(proof.reviewReasons()),proof.byteSize(),revision,Timestamp.from(ended),Timestamp.from(accepted));
        jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,original_student_id,original_attempt_id,resource_id) VALUES (?,?,'ANSWER_END_DECLARED',?,?,?,?)",assessment,student,revision,student,attempt,answer);
        return head(student,assessment,attempt,answer).orElseThrow();
    }
    public void receipt(int student,int assessment,String attempt,String answer,String key,String hash) {
        jdbc.update("INSERT INTO prepared_answer_end_receipt(assessment_id,student_id,attempt_id,answer_id,idempotency_key,request_hash) VALUES (?,?,?,?,?,?)",assessment,student,attempt,answer,key,hash);
    }
    public void finishAnswering(int student,int assessment,String attempt,Instant ended,boolean review) {
        jdbc.update("""
                UPDATE prepared_attempt SET answering_ended_at=?,supplemental_upload_deadline=?,state=?,revision=revision+1
                WHERE id=? AND assessment_id=? AND student_id=? AND answering_ended_at IS NULL AND state='IN_PROGRESS'
                """,Timestamp.from(ended),Timestamp.from(ended.plusSeconds(86400)),review?"REQUIRES_REVIEW":"UPLOAD_PENDING",attempt,assessment,student);
    }
    public void expirePending(int student,int assessment,String attempt,Instant now) {
        jdbc.update("UPDATE prepared_attempt SET state='REQUIRES_REVIEW',requires_supplement_review=1,revision=revision+1 WHERE id=? AND assessment_id=? AND student_id=? AND state='UPLOAD_PENDING' AND supplemental_upload_deadline<=?",attempt,assessment,student,Timestamp.from(now));
    }
    PreparedSupplementStore supplements(){return new PreparedSupplementStore(jdbc);}
    boolean supplementReviewOnly(int student,int assessment,String attempt){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT requires_supplement_review FROM prepared_attempt WHERE assessment_id=? AND student_id=? AND id=? FOR UPDATE",Boolean.class,assessment,student,attempt));}
    private static String reasonsJson(List<String> values) {try{return JSON.writeValueAsString(values);}catch(java.io.IOException error){throw new IllegalStateException("核验原因无法保存");}}
    private static Head head(ResultSet rs)throws SQLException {
        try{
            List<String> reasons=JSON.readValue(rs.getString("review_reasons"),JSON.getTypeFactory().constructCollectionType(List.class,String.class));
            return new Head(rs.getString("answer_id"),rs.getString("manifest_sha256"),rs.getString("manifest_json"),rs.getString("declaration_state"),List.copyOf(reasons),rs.getLong("byte_size"),rs.getLong("answer_revision"),
                    rs.getTimestamp("effective_ended_at").toInstant(),rs.getTimestamp("accepted_at").toInstant());
        }catch(java.io.IOException|RuntimeException error){throw new IllegalStateException("原结束记录无法核验");}
    }
}
