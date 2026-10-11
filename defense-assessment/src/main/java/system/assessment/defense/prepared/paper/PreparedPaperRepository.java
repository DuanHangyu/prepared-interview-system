package system.assessment.defense.prepared.paper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.paper.PreparedPaper.Draft;
import system.assessment.defense.prepared.paper.PreparedPaper.Question;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.HashSet;

@Repository
public class PreparedPaperRepository {
    private final JdbcTemplate jdbc;

    public PreparedPaperRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Draft load(int assessmentId) {
        List<Long> revisions = jdbc.query("SELECT revision FROM prepared_paper_draft WHERE assessment_id=?",
                (rs, row) -> rs.getLong(1), assessmentId);
        if (revisions.isEmpty()) return new Draft(assessmentId, 0, List.of());
        List<Question> questions = jdbc.query("""
                SELECT question_id, source, body, ordinal, max_score, answer_seconds,
                       rubric, approved, audio_state, audio_object_key
                FROM prepared_question_draft WHERE assessment_id=? ORDER BY ordinal
                """, (rs, row) -> new Question(rs.getString("question_id"), rs.getString("source"),
                rs.getString("body"), rs.getInt("ordinal"), rs.getInt("max_score"), rs.getInt("answer_seconds"),
                rs.getString("rubric"), rs.getBoolean("approved"), rs.getString("audio_state"),
                rs.getString("audio_object_key")), assessmentId);
        return new Draft(assessmentId, revisions.getFirst(), questions);
    }

    /** The parent lock serializes first-time draft creation as well as later revisions. */
    public Draft loadForUpdate(int assessmentId) {
        var ids = jdbc.query("SELECT id FROM assessment WHERE id=? AND deleted=0 FOR UPDATE",
                (rs, row) -> rs.getInt(1), assessmentId);
        if (ids.isEmpty()) throw new BusinessException(403, "无权限或考核不存在");
        jdbc.update("INSERT IGNORE INTO prepared_paper_draft(assessment_id,revision) VALUES (?,0)", assessmentId);
        return load(assessmentId);
    }

    public boolean replaceDraft(Draft next, long expectedRevision, int actorId) {
        int updated = jdbc.update("""
                UPDATE prepared_paper_draft SET revision=?,updated_by=?,updated_at=CURRENT_TIMESTAMP(3)
                WHERE assessment_id=? AND revision=?
                """, next.revision(), actorId, next.assessmentId(), expectedRevision);
        if (updated != 1) return false;
        jdbc.update("DELETE FROM prepared_question_draft WHERE assessment_id=?", next.assessmentId());
        for (Question q : next.questions()) {
            jdbc.update("""
                    INSERT INTO prepared_question_draft
                    (question_id,assessment_id,source,body,ordinal,max_score,answer_seconds,rubric,
                     approved,audio_state,audio_object_key)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, q.questionId(), next.assessmentId(), q.source(), q.body(), q.ordinal(), q.maxScore(),
                    q.answerSeconds(), q.rubric(), q.approved(), q.audioState(), q.audioObjectKey());
        }
        jdbc.update("""
                INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version)
                VALUES (?,?,'DRAFT_REVISED',?)
                """, next.assessmentId(), actorId, next.revision());
        return true;
    }

    /** Immutable review evidence survives later editing, reordering and draft deletion. */
    public void recordApproval(Draft draft, int actorId, List<String> questionIds) {
        jdbc.update("""
                INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version)
                VALUES (?,?,'QUESTIONS_APPROVED',?)
                """, draft.assessmentId(), actorId, draft.revision());
        var selected = new HashSet<>(questionIds);
        for (Question q : draft.questions()) {
            if (!selected.contains(q.questionId())) continue;
            jdbc.update("""
                    INSERT INTO prepared_question_review
                    (assessment_id,question_id,reviewer_id,reviewed_revision,content_sha256)
                    VALUES (?,?,?,?,?)
                    """, draft.assessmentId(), q.questionId(), actorId, draft.revision(), reviewDigest(q));
        }
    }

    private static String reviewDigest(Question q) {
        try {
            // Structured encoding avoids ambiguous concatenation and does not retain private text in audit.
            byte[] content = new ObjectMapper().writeValueAsBytes(List.of(q.body(), q.maxScore(), q.answerSeconds(), q.rubric()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法记录题目审核摘要");
        }
    }
}
