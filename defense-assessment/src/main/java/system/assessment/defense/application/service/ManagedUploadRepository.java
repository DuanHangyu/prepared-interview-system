package system.assessment.defense.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ManagedUploadRepository {

    private static final int STATE_PENDING = 0;
    private static final int STATE_CLEANING = 1;

    private final JdbcTemplate jdbcTemplate;

    public int register(int ownerId, String reference, long objectSize, LocalDateTime expiresAt) {
        return jdbcTemplate.update("""
                INSERT INTO managed_upload
                  (object_reference, owner_id, object_size, state, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, reference, ownerId, objectSize, STATE_PENDING, Timestamp.valueOf(expiresAt));
    }

    public int consumeForBinding(int ownerId, String reference, LocalDateTime now) {
        return jdbcTemplate.update("""
                DELETE FROM managed_upload
                WHERE owner_id = ?
                  AND object_reference = ?
                  AND state = ?
                  AND expires_at > ?
                """, ownerId, reference, STATE_PENDING, Timestamp.valueOf(now));
    }

    public int scheduleCleanup(int ownerId, String reference, LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE managed_upload
                SET expires_at = ?, update_time = ?
                WHERE owner_id = ? AND object_reference = ? AND state = ?
                """, Timestamp.valueOf(now), Timestamp.valueOf(now),
                ownerId, reference, STATE_PENDING);
    }

    public List<UploadTask> findCleanupCandidates(LocalDateTime now,
                                                   LocalDateTime staleCleaningBefore,
                                                   int limit) {
        return jdbcTemplate.query("""
                SELECT id, object_reference, attempts
                FROM managed_upload
                WHERE (state = ? AND expires_at <= ?)
                   OR (state = ? AND update_time <= ?)
                ORDER BY expires_at, id
                LIMIT ?
                """, (resultSet, rowNum) -> new UploadTask(
                resultSet.getLong("id"),
                resultSet.getString("object_reference"),
                resultSet.getInt("attempts")),
                STATE_PENDING, Timestamp.valueOf(now),
                STATE_CLEANING, Timestamp.valueOf(staleCleaningBefore), limit);
    }

    public int claimForCleanup(long id, LocalDateTime now, LocalDateTime staleCleaningBefore) {
        return jdbcTemplate.update("""
                UPDATE managed_upload
                SET state = ?, update_time = ?
                WHERE id = ?
                  AND ((state = ? AND expires_at <= ?)
                    OR (state = ? AND update_time <= ?))
                """, STATE_CLEANING, Timestamp.valueOf(now), id,
                STATE_PENDING, Timestamp.valueOf(now),
                STATE_CLEANING, Timestamp.valueOf(staleCleaningBefore));
    }

    public int retryLater(long id, LocalDateTime nextAttemptAt, String errorType) {
        return jdbcTemplate.update("""
                UPDATE managed_upload
                SET state = ?,
                    attempts = attempts + 1,
                    expires_at = ?,
                    last_error = ?
                WHERE id = ? AND state = ?
                """, STATE_PENDING, Timestamp.valueOf(nextAttemptAt), errorType,
                id, STATE_CLEANING);
    }

    public int deleteAfterCleanup(long id) {
        return jdbcTemplate.update(
                "DELETE FROM managed_upload WHERE id = ? AND state = ?", id, STATE_CLEANING);
    }

    public record UploadTask(long id, String objectReference, int attempts) {
    }
}
