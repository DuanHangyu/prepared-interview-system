package system.assessment.defense.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ManagedObjectCleanupTaskRepository {

    private static final String INSERT_SQL = """
            INSERT INTO managed_object_cleanup_task (object_reference)
            VALUES (?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public int enqueue(Collection<String> references) {
        List<Object[]> arguments = new ArrayList<>(references.size());
        for (String reference : references) {
            arguments.add(new Object[]{reference});
        }
        if (arguments.isEmpty()) {
            return 0;
        }
        int queued = 0;
        for (int updateCount : jdbcTemplate.batchUpdate(INSERT_SQL, arguments)) {
            queued += updateCount;
        }
        return queued;
    }

    public List<CleanupTask> findDue(int limit) {
        return jdbcTemplate.query("""
                SELECT id, object_reference, attempts
                FROM managed_object_cleanup_task
                WHERE next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY next_attempt_at, id
                LIMIT ?
                """, (resultSet, rowNum) -> new CleanupTask(
                resultSet.getLong("id"),
                resultSet.getString("object_reference"),
                resultSet.getInt("attempts")),
                limit);
    }

    public boolean isReferenced(String reference) {
        Integer referenced = jdbcTemplate.queryForObject("""
                SELECT IF(
                  EXISTS (
                    SELECT 1 FROM assessment
                    WHERE deleted = 0
                      AND LOCATE(?, COALESCE(assessment_files, '')) > 0
                  )
                  OR EXISTS (
                    SELECT 1 FROM student_assessment_record
                    WHERE LOCATE(?, COALESCE(defense_file, '')) > 0
                       OR defense_voice = ?
                  )
                  OR EXISTS (
                    SELECT 1 FROM student_assessment_question_answer
                    WHERE answer_voice = ? OR follow_answer_voice = ?
                  ), 1, 0)
                """, Integer.class, reference, reference, reference, reference, reference);
        return referenced != null && referenced == 1;
    }

    public int deleteById(long id) {
        return jdbcTemplate.update("DELETE FROM managed_object_cleanup_task WHERE id = ?", id);
    }

    public int markFailure(long id, long retryDelayMinutes, String errorType) {
        return jdbcTemplate.update("""
                UPDATE managed_object_cleanup_task
                SET attempts = attempts + 1,
                    next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? MINUTE),
                    last_error = ?
                WHERE id = ?
                """, retryDelayMinutes, errorType, id);
    }

    public record CleanupTask(long id, String objectReference, int attempts) {
    }
}
