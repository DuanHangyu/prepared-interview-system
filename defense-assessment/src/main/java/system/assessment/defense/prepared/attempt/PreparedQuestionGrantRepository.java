package system.assessment.defense.prepared.attempt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import system.assessment.defense.prepared.publication.PreparedPublication;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Internal persistence; callers hold the publication parent lock and current authorization in a transaction. */
@Repository
public class PreparedQuestionGrantRepository {
    private final JdbcTemplate jdbc;
    public PreparedQuestionGrantRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Answer(String id,String version,String question,int ordinal,String client,int seconds,String state,Instant started,Instant deadline) {}
    public record Receipt(String hash,String answer) {}
    public Optional<Receipt> receipt(int student,int assessment,String attempt,String key) {
        return jdbc.query("SELECT request_hash,answer_id FROM prepared_question_start_receipt WHERE assessment_id=? AND student_id=? AND attempt_id=? AND idempotency_key=? FOR UPDATE",
                (rs,n)->new Receipt(rs.getString(1),rs.getString(2)),assessment,student,attempt,key).stream().findFirst();
    }
    public List<Answer> answers(int student,int assessment,String attempt,boolean lock) {
        return jdbc.query("SELECT * FROM prepared_answer WHERE assessment_id=? AND student_id=? AND attempt_id=? ORDER BY ordinal"+(lock?" FOR UPDATE":""),
                (rs,n)->answer(rs),assessment,student,attempt);
    }
    public void verifyQuestion(int assessment,String version,PreparedPublication.Question question,boolean lock) {
        verifyQuestion(assessment,version,question,lock?" FOR UPDATE":"");
    }
    /** Frozen-question authorization only; retains mutation exclusion without serializing students. */
    public void verifyQuestionForShare(int assessment,String version,PreparedPublication.Question question) {
        verifyQuestion(assessment,version,question," FOR SHARE");
    }
    private void verifyQuestion(int assessment,String version,PreparedPublication.Question question,String lockingClause) {
        var bindings=jdbc.query("SELECT question_id,ordinal,body,answer_seconds,max_score,rubric,audio_asset_id FROM prepared_question_snapshot WHERE assessment_id=? AND paper_version_id=? AND question_id=?"+lockingClause,
                (rs,n)->question.questionId().equals(rs.getString(1)) && question.ordinal()==rs.getInt(2) && question.body().equals(rs.getString(3))
                        && question.answerSeconds()==rs.getInt(4) && question.maxScore()==rs.getInt(5) && question.rubric().equals(rs.getString(6)) && question.audio().assetId().equals(rs.getString(7)),
                assessment,version,question.questionId());
        if(bindings.size()!=1||!bindings.getFirst())throw new IllegalStateException("题目快照绑定验证失败");
    }
    public Answer create(int student,int assessment,String attempt,String version,PreparedPublication.Question question,String client,Instant now) {
        String id=UUID.randomUUID().toString();Instant deadline=now.plusSeconds(question.answerSeconds());
        jdbc.update("""
                INSERT INTO prepared_answer(id,assessment_id,student_id,attempt_id,paper_version_id,question_id,ordinal,capture_client_id,answer_seconds,started_at,answer_deadline)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,id,assessment,student,attempt,version,question.questionId(),question.ordinal(),client,question.answerSeconds(),Timestamp.from(now),Timestamp.from(deadline));
        jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,original_student_id,original_attempt_id,resource_id) VALUES (?,?,'QUESTION_STARTED',?,?,?,?)",assessment,student,question.ordinal(),student,attempt,id);
        return new Answer(id,version,question.questionId(),question.ordinal(),client,question.answerSeconds(),"RECORDING",now,deadline);
    }
    public void recordReceipt(int student,int assessment,String attempt,String key,String hash,String answer) {
        jdbc.update("INSERT INTO prepared_question_start_receipt(assessment_id,student_id,attempt_id,idempotency_key,request_hash,answer_id) VALUES (?,?,?,?,?,?)",assessment,student,attempt,key,hash,answer);
    }
    private static Answer answer(ResultSet rs)throws SQLException {
        return new Answer(rs.getString("id"),rs.getString("paper_version_id"),rs.getString("question_id"),rs.getInt("ordinal"),
                rs.getString("capture_client_id"),rs.getInt("answer_seconds"),rs.getString("state"),rs.getTimestamp("started_at").toInstant(),rs.getTimestamp("answer_deadline").toInstant());
    }
}
