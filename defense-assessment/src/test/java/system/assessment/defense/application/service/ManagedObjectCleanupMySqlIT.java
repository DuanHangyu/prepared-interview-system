package system.assessment.defense.application.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import system.assessment.defense.infrastructure.migration.OwnedMigrationMysql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.infrastructure.concurrency.MySqlAdvisoryLockService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedObjectCleanupMySqlIT {

    private static OwnedMigrationMysql ownedMysql;

    @BeforeAll
    static void openOwnedMysql() throws Exception {
        ownedMysql = OwnedMigrationMysql.openIfEnabled();
    }

    @AfterAll
    static void closeOwnedMysql() throws Exception {
        if (ownedMysql != null) ownedMysql.close();
    }

    @Test
    void queueTransactionsAndCleanupSchedulingUseTheDatabaseClock() throws SQLException {
        String serverUrl = ownedMysql == null ? System.getenv("MYSQL_INTEGRATION_SERVER_URL") : ownedMysql.serverUrl();
        Assumptions.assumeTrue(serverUrl != null && !serverUrl.isBlank(),
                "MYSQL_INTEGRATION_SERVER_URL is required for this integration test");
        String username = ownedMysql == null ? System.getenv().getOrDefault("MYSQL_INTEGRATION_USERNAME", "root") : ownedMysql.username();
        String password = ownedMysql == null ? System.getenv().getOrDefault("MYSQL_INTEGRATION_PASSWORD", "") : ownedMysql.password();
        String database = "ai_cleanup_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String schemaUrl = schemaUrl(serverUrl, database);

        try {
            execute(serverUrl, username, password,
                    "CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            Flyway.configure().dataSource(schemaUrl, username, password)
                    .locations("classpath:db/migration").load().migrate();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(schemaUrl, username, password);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            ManagedObjectCleanupTaskRepository repository =
                    new ManagedObjectCleanupTaskRepository(jdbc);
            TransactionTemplate transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));

            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                repository.enqueue(List.of("defense-assessment-new/system/rolled-back.wav"));
                throw new IllegalStateException("roll back business operation");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(taskCount(jdbc)).isZero();

            transaction.executeWithoutResult(status -> repository.enqueue(
                    List.of("defense-assessment-new/system/committed.wav")));
            assertThat(taskCount(jdbc)).isEqualTo(1);

            OssManager ossManager = mock(OssManager.class);
            ManagedObjectCleanupRetryService retryService = new ManagedObjectCleanupRetryService(
                    repository, ossManager, mock(MySqlAdvisoryLockService.class));
            assertThat(retryService.processDueTasks()).isEqualTo(1);
            assertThat(taskCount(jdbc)).isZero();

            String retryReference = "defense-assessment-new/system/retry.wav";
            repository.enqueue(List.of(retryReference));
            when(ossManager.deleteStoredObject(retryReference))
                    .thenThrow(new IllegalStateException("temporary object-store failure"));

            assertThat(retryService.processDueTasks()).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT attempts FROM managed_object_cleanup_task", Integer.class)).isEqualTo(1);
            LocalDateTime databaseNow = jdbc.queryForObject(
                    "SELECT CURRENT_TIMESTAMP", LocalDateTime.class);
            LocalDateTime nextAttemptAt = jdbc.queryForObject(
                    "SELECT next_attempt_at FROM managed_object_cleanup_task", LocalDateTime.class);
            assertThat(nextAttemptAt)
                    .isAfter(databaseNow.plusSeconds(30))
                    .isBefore(databaseNow.plusMinutes(2));
        } finally {
            execute(serverUrl, username, password, "DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    private int taskCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM managed_object_cleanup_task", Integer.class);
    }

    private String schemaUrl(String serverUrl, String database) {
        int queryStart = serverUrl.indexOf('?');
        String base = queryStart >= 0 ? serverUrl.substring(0, queryStart) : serverUrl;
        String query = queryStart >= 0 ? serverUrl.substring(queryStart) : "";
        return base + (base.endsWith("/") ? "" : "/") + database + query;
    }

    private void execute(String url, String username, String password, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
