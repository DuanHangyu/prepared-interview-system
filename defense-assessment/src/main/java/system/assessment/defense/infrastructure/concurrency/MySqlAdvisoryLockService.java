package system.assessment.defense.infrastructure.concurrency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;

@Component
@Slf4j
public class MySqlAdvisoryLockService {

    private static final String LOCK_PREFIX = "ai-interview:";
    private static final Pattern SAFE_LOCK_NAME = Pattern.compile("[a-z0-9:_-]{1,48}");

    private final DataSource dataSource;

    public MySqlAdvisoryLockService(
            @Qualifier("advisoryLockDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Attempts to acquire a connection-scoped MySQL lock without waiting.
     * The same JDBC connection remains open for the complete task so a process
     * crash or connection loss automatically releases the lock.
     */
    public boolean tryWithLock(String lockName, Runnable task) {
        String qualifiedName = qualify(lockName);
        try (Connection connection = dataSource.getConnection()) {
            if (!acquire(connection, qualifiedName)) {
                return false;
            }
            try {
                task.run();
                return true;
            } finally {
                release(connection, qualifiedName);
            }
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException(
                    "Unable to use MySQL advisory lock " + qualifiedName, exception);
        }
    }

    private boolean acquire(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT GET_LOCK(SHA2(CONCAT(DATABASE(), ':', ?), 256), 0)")) {
            statement.setString(1, lockName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1 && !resultSet.wasNull();
            }
        }
    }

    private void release(Connection connection, String lockName) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT RELEASE_LOCK(SHA2(CONCAT(DATABASE(), ':', ?), 256))")) {
            statement.setString(1, lockName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1 || resultSet.wasNull()) {
                    log.warn("MySQL advisory lock was not owned when released, lockName:{}", lockName);
                }
            }
        } catch (SQLException exception) {
            log.error("Failed to release MySQL advisory lock, lockName:{}", lockName, exception);
        }
    }

    private String qualify(String lockName) {
        if (lockName == null || !SAFE_LOCK_NAME.matcher(lockName).matches()) {
            throw new IllegalArgumentException("Invalid advisory lock name");
        }
        return LOCK_PREFIX + lockName;
    }
}
