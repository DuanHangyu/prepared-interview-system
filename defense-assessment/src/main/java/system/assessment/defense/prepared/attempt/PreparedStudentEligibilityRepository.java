package system.assessment.defense.prepared.attempt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Repository
public class PreparedStudentEligibilityRepository {
    private static final ZoneId ASSESSMENT_TIME_ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    public PreparedStudentEligibilityRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Context(String theme,boolean appointmentRequired,boolean hasLegacyRecord,Integer appointmentState,
                          Instant appointmentAt,String paperVersionId,String contentSha256,String snapshotJson) {}
    /** One consistent scoped read; never read another student's appointment, record or unpublished draft. */
    public Context context(int student,int assessment) {
        return readContext(student,assessment,"");
    }
    /** Formal start holds the assessment parent lock first; this is a current read of role/assignment/appointment. */
    public Context contextForUpdate(int student,int assessment) {
        return readContext(student,assessment," FOR UPDATE");
    }
    /** Frozen parent and exclusive student assignment are already held. READ COMMITTED avoids absent-record gap cycles. */
    public Context contextForShare(int student,int assessment) {
        return readContext(student,assessment," FOR SHARE");
    }
    private Context readContext(int student,int assessment,String lockingClause) {
        return jdbc.query("""
                SELECT a.theme,a.need_appoint,r.id AS record_id,p.state,p.time_period,
                       v.id AS paper_version_id,v.content_sha256,v.snapshot_json
                FROM assessment a
                JOIN assessment_student_relation rel ON rel.assessment_id=a.id AND rel.student_id=?
                JOIN `user` u ON u.id=rel.student_id AND u.type=0
                LEFT JOIN student_assessment_record r ON r.assessment_id=a.id AND r.student_id=rel.student_id
                LEFT JOIN student_assessment_appointment p ON p.assessment_id=a.id AND p.student_id=rel.student_id
                LEFT JOIN prepared_paper_gate g ON g.assessment_id=a.id
                LEFT JOIN prepared_paper_version v ON v.assessment_id=a.id AND v.id=g.active_version_id
                WHERE a.id=? AND a.deleted=0
                """+lockingClause,(rs,row)->{
            LocalDateTime slot=rs.getObject("time_period",LocalDateTime.class);
            return new Context(rs.getString("theme"),rs.getBoolean("need_appoint"),rs.getObject("record_id")!=null,
                    (Integer)rs.getObject("state"),slot==null?null:slot.atZone(ASSESSMENT_TIME_ZONE).toInstant(),
                    rs.getString("paper_version_id"),rs.getString("content_sha256"),rs.getString("snapshot_json"));
        },student,assessment).stream().findFirst().orElseThrow(()->new BusinessException(403,"无权限或考核不存在"));
    }
}
