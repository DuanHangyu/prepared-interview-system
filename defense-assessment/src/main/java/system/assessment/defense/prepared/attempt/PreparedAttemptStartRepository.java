package system.assessment.defense.prepared.attempt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static system.assessment.defense.prepared.attempt.PreparedAttemptStart.*;

@Repository
public class PreparedAttemptStartRepository {
    private final JdbcTemplate jdbc;
    public PreparedAttemptStartRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Receipt(String hash,String attemptId) {}
    public record Paper(String content,String sha256) {}
    /** Same parent row as publication; no implicit creation of drafts and no overwriting history. */
    public void lockAssessment(int assessment) {
        if(jdbc.query("SELECT id FROM assessment WHERE id=? AND deleted=0 FOR UPDATE",(rs,n)->rs.getInt(1),assessment).isEmpty())
            throw new BusinessException(403,"无权限或考核不存在");
    }
    /** A committed first-start freeze is monotonic. Independent students may share that immutable parent.
     * Fresh first-starts retain the publication's exclusive parent/rollback protocol. */
    public boolean lockForStudentStart(int student,int assessment) {
        boolean frozen=Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM prepared_paper_gate WHERE assessment_id=? AND first_started_at IS NOT NULL)",Boolean.class,assessment));
        if(frozen) {
            if(jdbc.query("SELECT id FROM assessment WHERE id=? AND deleted=0 FOR SHARE",(rs,n)->rs.getInt(1),assessment).isEmpty())
                throw new BusinessException(403,"无权限或考核不存在");
            // Do not upgrade a shared lock on uncertain state: fail closed rather than form upgrade cycles.
            if(jdbc.query("SELECT assessment_id FROM prepared_paper_gate WHERE assessment_id=? AND first_started_at IS NOT NULL FOR SHARE",
                    (rs,n)->rs.getInt(1),assessment).size()!=1)throw new IllegalStateException("原开考冻结状态待核验");
        } else lockAssessment(assessment);
        // A student remains exclusive even with different idempotency keys. Also protects role/assignment revocation.
        if(jdbc.query("""
                SELECT rel.student_id FROM assessment_student_relation rel
                JOIN `user` u ON u.id=rel.student_id AND u.type=0
                WHERE rel.assessment_id=? AND rel.student_id=? FOR UPDATE
                """,(rs,n)->rs.getInt(1),assessment,student).size()!=1)
            throw new BusinessException(403,"无权限或考核不存在");
        return frozen;
    }
    public Optional<Receipt> receipt(int student,int assessment,String key) {
        return jdbc.query("SELECT request_hash,attempt_id FROM prepared_attempt_start_receipt WHERE assessment_id=? AND student_id=? AND idempotency_key=? FOR UPDATE",
                (rs,n)->new Receipt(rs.getString(1),rs.getString(2)),assessment,student,key).stream().findFirst();
    }
    public Optional<Summary> current(int student,int assessment) {
        return current(student,assessment,false);
    }
    public Optional<Summary> currentForUpdate(int student,int assessment) {
        return current(student,assessment,true);
    }
    private Optional<Summary> current(int student,int assessment,boolean lock) {
        return jdbc.query("""
                SELECT t.* FROM prepared_attempt_anchor a
                JOIN prepared_attempt t ON t.id=a.active_attempt_id AND t.record_id=a.record_id
                  AND t.assessment_id=a.assessment_id AND t.student_id=a.student_id
                WHERE a.assessment_id=? AND a.student_id=?
                """+(lock?" FOR UPDATE":""),(rs,n)->summary(rs),assessment,student).stream().findFirst();
    }
    public Summary get(int student,int assessment,String id) {
        return jdbc.query("SELECT * FROM prepared_attempt WHERE assessment_id=? AND student_id=? AND id=? FOR UPDATE",
                (rs,n)->summary(rs),assessment,student,id).stream().findFirst().orElseThrow(()->new IllegalStateException("原开考记录绑定无效"));
    }
    /** Same immutable record, whether current or historical. Internal read guard only. */
    public boolean managerReadAllowed(int actor,int student,int assessment,String id){
        return jdbc.queryForList("""
            SELECT t.id FROM prepared_attempt t
            JOIN prepared_attempt_anchor h ON h.assessment_id=t.assessment_id AND h.student_id=t.student_id AND h.record_id=t.record_id
            JOIN student_assessment_record r ON r.id=t.record_id AND r.assessment_id=t.assessment_id AND r.student_id=t.student_id
            JOIN assessment a ON a.id=t.assessment_id JOIN `user` u ON u.id=?
            WHERE t.assessment_id=? AND t.student_id=? AND t.id=? AND a.deleted=0
            AND (u.type=2 OR (u.type=1 AND (a.create_id=u.id OR EXISTS(
                SELECT 1 FROM assessment_invite i WHERE i.assessment_id=a.id AND i.invitee_teacher_id=u.id)))) FOR UPDATE
            """,String.class,actor,assessment,student,id).size()==1;
    }
    public Paper boundPaper(int assessment,String version) {
        return boundPaper(assessment,version,false);
    }
    public Paper boundPaperForUpdate(int assessment,String version) {
        return boundPaper(assessment,version,true);
    }
    public Paper boundPaperForShare(int assessment,String version) {
        return jdbc.query("SELECT snapshot_json,content_sha256 FROM prepared_paper_version WHERE assessment_id=? AND id=? FOR SHARE",
                (rs,n)->new Paper(rs.getString(1),rs.getString(2)),assessment,version).stream().findFirst()
                .orElseThrow(()->new IllegalStateException("原试卷版本绑定无效"));
    }
    private Paper boundPaper(int assessment,String version,boolean lock) {
        return jdbc.query("SELECT snapshot_json,content_sha256 FROM prepared_paper_version WHERE assessment_id=? AND id=?"+(lock?" FOR UPDATE":""),
                (rs,n)->new Paper(rs.getString(1),rs.getString(2)),assessment,version).stream().findFirst().orElseThrow(()->new IllegalStateException("原试卷版本绑定无效"));
    }
    /** Caller must hold parent lock/current authorization and execute the whole start in one transaction. */
    public Summary create(int student,int assessment,String version,Instant now) {
        return create(student,assessment,version,now,false);
    }
    /** alreadyFrozen comes only from lockForStudentStart, never from a client or an unlocked cache. */
    public Summary create(int student,int assessment,String version,Instant now,boolean alreadyFrozen) {
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var statement=connection.prepareStatement("INSERT INTO student_assessment_record(assessment_id,student_id,state,re_analysis,create_time,update_time) VALUES (?,?,0,0,?,?)",Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1,assessment);statement.setInt(2,student);statement.setTimestamp(3,Timestamp.from(now));statement.setTimestamp(4,Timestamp.from(now));return statement;
        },keys);
        int record=Objects.requireNonNull(keys.getKey()).intValue();String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO prepared_attempt(id,record_id,assessment_id,student_id,attempt_no,paper_version_id,started_at) VALUES (?,?,?,?,1,?,?)",
                id,record,assessment,student,version,Timestamp.from(now));
        jdbc.update("INSERT INTO prepared_attempt_anchor(assessment_id,student_id,record_id,active_attempt_id) VALUES (?,?,?,?)",assessment,student,record,id);
        if(!alreadyFrozen&&jdbc.update("UPDATE prepared_paper_gate SET first_started_at=COALESCE(first_started_at,?) WHERE assessment_id=? AND active_version_id=?",Timestamp.from(now),assessment,version)!=1)
            throw new IllegalStateException("开考试卷锁定失败");
        jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,original_student_id,original_attempt_id) VALUES (?,?,'ATTEMPT_STARTED',1,?,?)",assessment,student,student,id);
        return get(student,assessment,id);
    }
    public void recordReceipt(int student,int assessment,String key,String hash,String attempt) {
        jdbc.update("INSERT INTO prepared_attempt_start_receipt(assessment_id,student_id,idempotency_key,request_hash,attempt_id) VALUES (?,?,?,?,?)",assessment,student,key,hash,attempt);
    }
    public void startAppointment(int student,int assessment) {
        if(jdbc.update("UPDATE student_assessment_appointment SET state=1 WHERE assessment_id=? AND student_id=? AND (state=0 OR state IS NULL)",assessment,student)!=1)
            throw new BusinessException(409,"预约状态已变化，请核验原尝试");
    }
    private static Summary summary(ResultSet rs) throws SQLException {
        return new Summary(rs.getString("id"),rs.getInt("record_id"),rs.getInt("assessment_id"),rs.getInt("attempt_no"),
                rs.getString("paper_version_id"),rs.getString("state"),rs.getLong("revision"),time(rs,"started_at"),time(rs,"answering_ended_at"),time(rs,"supplemental_upload_deadline"));
    }
    private static String time(ResultSet rs,String field) throws SQLException {var value=rs.getTimestamp(field);return value==null?null:value.toInstant().toString();}
}
