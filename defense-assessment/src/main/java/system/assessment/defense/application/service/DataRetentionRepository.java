package system.assessment.defense.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class DataRetentionRepository {

    private final JdbcTemplate jdbcTemplate;

    public int deleteTokenAuditsBefore(LocalDateTime cutoff, int limit) {
        return jdbcTemplate.update("""
                DELETE FROM token_consumer
                WHERE create_time < ?
                ORDER BY id
                LIMIT ?
                """, Timestamp.valueOf(cutoff), limit);
    }

    public List<InterviewMediaCandidate> findExpiredInterviewMedia(LocalDateTime cutoff, int limit) {
        return jdbcTemplate.query("""
                SELECT r.id, r.assessment_id, r.student_id, r.defense_file, r.defense_voice
                FROM student_assessment_record r
                WHERE r.state = 1
                  AND COALESCE(r.end_defense_time, r.update_time, r.create_time) < ?
                  AND (
                    NULLIF(r.defense_file, '') IS NOT NULL
                    OR NULLIF(r.defense_voice, '') IS NOT NULL
                    OR EXISTS (
                      SELECT 1 FROM student_assessment_question_answer qa
                      WHERE qa.assessment_id = r.assessment_id
                        AND qa.student_id = r.student_id
                        AND (NULLIF(qa.answer_voice, '') IS NOT NULL
                          OR NULLIF(qa.follow_answer_voice, '') IS NOT NULL)
                    )
                  )
                ORDER BY r.id
                LIMIT ?
                """, (resultSet, rowNum) -> new InterviewMediaCandidate(
                resultSet.getInt("id"),
                resultSet.getInt("assessment_id"),
                resultSet.getInt("student_id"),
                resultSet.getString("defense_file"),
                resultSet.getString("defense_voice")
        ), Timestamp.valueOf(cutoff), limit);
    }

    public List<String> findAnswerVoiceReferences(int assessmentId, int studentId) {
        return jdbcTemplate.query("""
                SELECT answer_voice FROM student_assessment_question_answer
                WHERE assessment_id = ? AND student_id = ? AND NULLIF(answer_voice, '') IS NOT NULL
                UNION ALL
                SELECT follow_answer_voice FROM student_assessment_question_answer
                WHERE assessment_id = ? AND student_id = ? AND NULLIF(follow_answer_voice, '') IS NOT NULL
                """, (resultSet, rowNum) -> resultSet.getString(1),
                assessmentId, studentId, assessmentId, studentId);
    }

    @Transactional
    public void clearInterviewMedia(int recordId, int assessmentId, int studentId) {
        jdbcTemplate.update("""
                UPDATE student_assessment_question_answer
                SET answer_voice = NULL, follow_answer_voice = NULL
                WHERE assessment_id = ? AND student_id = ?
                """, assessmentId, studentId);
        jdbcTemplate.update("""
                UPDATE student_assessment_record
                SET defense_file = NULL, defense_voice = NULL
                WHERE id = ?
                """, recordId);
    }

    public List<DeletedAssessmentMaterialCandidate> findExpiredDeletedMaterials(LocalDateTime cutoff, int limit) {
        return jdbcTemplate.query("""
                SELECT id, assessment_files
                FROM assessment
                WHERE deleted = 1
                  AND deleted_at IS NOT NULL
                  AND deleted_at < ?
                  AND NULLIF(assessment_files, '') IS NOT NULL
                ORDER BY id
                LIMIT ?
                """, (resultSet, rowNum) -> new DeletedAssessmentMaterialCandidate(
                resultSet.getInt("id"), resultSet.getString("assessment_files")
        ), Timestamp.valueOf(cutoff), limit);
    }

    public void clearDeletedAssessmentMaterial(int assessmentId) {
        jdbcTemplate.update("""
                UPDATE assessment SET assessment_files = NULL WHERE id = ? AND deleted = 1
                """, assessmentId);
    }

    public List<Integer> findExpiredBusinessRecords(LocalDateTime cutoff, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT id FROM student_assessment_record
                WHERE state = 1
                  AND COALESCE(end_defense_time, update_time, create_time) < ?
                ORDER BY id
                LIMIT ?
                """, Integer.class, Timestamp.valueOf(cutoff), limit);
    }

    @Transactional
    public void deleteBusinessRecord(int recordId) {
        jdbcTemplate.update("""
                DELETE qa FROM student_assessment_question_answer qa
                INNER JOIN student_assessment_record r
                  ON qa.assessment_id = r.assessment_id AND qa.student_id = r.student_id
                WHERE r.id = ?
                """, recordId);
        jdbcTemplate.update("DELETE FROM student_assessment_record WHERE id = ?", recordId);
    }

    public record InterviewMediaCandidate(
            int recordId,
            int assessmentId,
            int studentId,
            String defenseFile,
            String defenseVoice) {
    }

    public record DeletedAssessmentMaterialCandidate(int assessmentId, String assessmentFiles) {
    }
}
