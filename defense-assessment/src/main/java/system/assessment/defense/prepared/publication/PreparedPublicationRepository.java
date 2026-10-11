package system.assessment.defense.prepared.publication;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.util.*;
import static system.assessment.defense.prepared.publication.PreparedPublication.*;

@Repository
public class PreparedPublicationRepository {
    private final JdbcTemplate jdbc;
    public PreparedPublicationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Asset(String id, int assessmentId, String questionId, String bodySha256, String configSha256,
                        String objectKey, String contentSha256, long byteSize, long durationMs, String mediaType) {
        public Audio descriptor() { return new Audio(id, bodySha256, configSha256, contentSha256, byteSize, durationMs, mediaType); }
    }
    public record Gate(String activeVersionId, boolean frozen) {}
    public record Receipt(String requestHash, String versionId) {}
    public Rules rules(int assessment) {
        var rules = jdbc.query("SELECT theme,total_score,pass_score,question_count,show_result FROM assessment WHERE id=? AND deleted=0",
                (rs, n) -> new Rules(rs.getString(1), (Integer)rs.getObject(2), (Integer)rs.getObject(3),
                        (Integer)rs.getObject(4), rs.getBoolean(5)), assessment);
        if (rules.isEmpty()) throw new BusinessException(403, "无权限或考核不存在");
        return rules.getFirst();
    }
    public Gate gate(int assessment) {
        return jdbc.query("SELECT active_version_id,first_started_at FROM prepared_paper_gate WHERE assessment_id=?",
                (rs,n)->new Gate(rs.getString(1),rs.getTimestamp(2)!=null),assessment).stream().findFirst().orElse(new Gate(null,false));
    }
    public Optional<Asset> verifiedAsset(int assessment, String question, String key) {
        if (key == null) return Optional.empty();
        return jdbc.query("""
                SELECT a.id,a.assessment_id,a.question_id,a.body_sha256,a.config_sha256,a.object_key,
                       a.content_sha256,a.byte_size,a.duration_ms,a.media_type
                FROM prepared_audio_asset a JOIN prepared_job j ON j.id=a.job_id
                WHERE a.assessment_id=? AND a.question_id=? AND a.object_key=? AND a.state='READY'
                  AND a.verified_at IS NOT NULL AND j.assessment_id=a.assessment_id
                  AND j.kind IN ('TTS','ASSEMBLE') AND j.state='SUCCEEDED'
                """,(rs,n)->new Asset(rs.getString(1),rs.getInt(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getString(6),rs.getString(7),rs.getLong(8),rs.getLong(9),rs.getString(10)),assessment,question,key).stream().findFirst();
    }
    public List<Summary> versions(int assessment) {
        return jdbc.query("SELECT id,version_no,draft_revision,content_sha256,published_by,published_at FROM prepared_paper_version WHERE assessment_id=? ORDER BY version_no DESC LIMIT 20",
                (rs,n)->new Summary(rs.getString(1),rs.getInt(2),rs.getLong(3),rs.getString(4),rs.getInt(5),rs.getTimestamp(6).toInstant().toString()),assessment);
    }
    public Optional<Summary> summary(int assessment, String id) {
        return jdbc.query("SELECT id,version_no,draft_revision,content_sha256,published_by,published_at FROM prepared_paper_version WHERE assessment_id=? AND id=?",
                (rs,n)->new Summary(rs.getString(1),rs.getInt(2),rs.getLong(3),rs.getString(4),rs.getInt(5),rs.getTimestamp(6).toInstant().toString()),assessment,id).stream().findFirst();
    }
    public String snapshot(int assessment, String id) {
        return jdbc.query("SELECT snapshot_json FROM prepared_paper_version WHERE assessment_id=? AND id=?",
                (rs,n)->rs.getString(1),assessment,id).stream().findFirst().orElseThrow(()->new BusinessException(403,"试卷版本不存在或无权限"));
    }
    public Optional<Receipt> receipt(int assessment, int actor, String key) {
        return jdbc.query("SELECT request_hash,paper_version_id FROM prepared_publication_receipt WHERE assessment_id=? AND actor_id=? AND idempotency_key=?",
                (rs,n)->new Receipt(rs.getString(1),rs.getString(2)),assessment,actor,key).stream().findFirst();
    }
    /** Caller holds the assessment row lock and the surrounding transaction. No update of old snapshots. */
    public String publish(int actor, long revision, Snapshot snapshot, String content, String hash) {
        int assessment = snapshot.assessmentId();
        int number = Objects.requireNonNull(jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM prepared_paper_version WHERE assessment_id=?",Integer.class,assessment));
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO prepared_paper_version(id,assessment_id,version_no,draft_revision,content_sha256,snapshot_json,published_by) VALUES (?,?,?,?,?,?,?)",
                id,assessment,number,revision,hash,content,actor);
        for (Question q:snapshot.questions()) jdbc.update("""
                INSERT INTO prepared_question_snapshot(paper_version_id,assessment_id,question_id,ordinal,body,max_score,answer_seconds,rubric,audio_asset_id)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,id,assessment,q.questionId(),q.ordinal(),q.body(),q.maxScore(),q.answerSeconds(),q.rubric(),q.audio().assetId());
        jdbc.update("INSERT IGNORE INTO prepared_paper_gate(assessment_id) VALUES (?)",assessment);
        if (jdbc.update("UPDATE prepared_paper_gate SET active_version_id=? WHERE assessment_id=? AND first_started_at IS NULL",id,assessment)!=1)
            throw new BusinessException(409,"考核已有学生开始，不能切换试卷");
        jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version) VALUES (?,?,'PAPER_PUBLISHED',?)",assessment,actor,number);
        return id;
    }
    public void recordReceipt(int actor, int assessment, Command command, String hash, String version) {
        jdbc.update("INSERT INTO prepared_publication_receipt(assessment_id,actor_id,idempotency_key,request_hash,paper_version_id) VALUES (?,?,?,?,?)",
                assessment,actor,command.idempotencyKey(),hash,version);
    }
}
