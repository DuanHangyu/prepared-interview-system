package system.assessment.defense.application.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import system.assessment.defense.infrastructure.migration.OwnedMigrationMysql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.infrastructure.concurrency.MySqlAdvisoryLockService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataRetentionMySqlIT {

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
    void expiredRowsAndOwnedObjectReferencesAreCleanedAgainstRealMySql() throws SQLException {
        String serverUrl = ownedMysql == null ? System.getenv("MYSQL_INTEGRATION_SERVER_URL") : ownedMysql.serverUrl();
        Assumptions.assumeTrue(serverUrl != null && !serverUrl.isBlank(),
                "MYSQL_INTEGRATION_SERVER_URL is required for this integration test");
        String username = ownedMysql == null ? System.getenv().getOrDefault("MYSQL_INTEGRATION_USERNAME", "root") : ownedMysql.username();
        String password = ownedMysql == null ? System.getenv().getOrDefault("MYSQL_INTEGRATION_PASSWORD", "") : ownedMysql.password();
        String database = "ai_retention_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String schemaUrl = schemaUrl(serverUrl, database);

        try {
            execute(serverUrl, username, password,
                    "CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            Flyway.configure().dataSource(schemaUrl, username, password)
                    .locations("classpath:db/migration").load().migrate();

            DriverManagerDataSource dataSource = new DriverManagerDataSource(schemaUrl, username, password);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            seedExpiredData(jdbc);

            DataRetentionProperties properties = new DataRetentionProperties();
            properties.setPurgeBusinessRecords(true);
            properties.setBatchSize(10);
            properties.setMaxBatchesPerRun(2);
            OssManager ossManager = mock(OssManager.class);
            when(ossManager.deleteStoredObject(anyString())).thenReturn(true);
            DataRetentionService service = new DataRetentionService(
                    properties,
                    new DataRetentionRepository(jdbc),
                    new ManagedObjectCleanupService(ossManager),
                    mock(MySqlAdvisoryLockService.class));

            DataRetentionService.CleanupResult result = service.cleanupAt(
                    LocalDateTime.of(2026, 10, 2, 12, 0));

            assertThat(result).isEqualTo(new DataRetentionService.CleanupResult(1, 1, 1, 1, 0));
            assertThat(count(jdbc, "token_consumer")).isZero();
            assertThat(count(jdbc, "student_assessment_record")).isZero();
            assertThat(count(jdbc, "student_assessment_question_answer")).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT assessment_files IS NULL FROM assessment WHERE id = 5", Boolean.class)).isTrue();
            verify(ossManager).deleteStoredObject("defense-assessment-new/users/33/file.pdf");
            verify(ossManager).deleteStoredObject("defense-assessment-new/system/defense.wav");
            verify(ossManager).deleteStoredObject("defense-assessment-new/system/answer.wav");
            verify(ossManager).deleteStoredObject("defense-assessment-new/system/follow.wav");
            verify(ossManager).deleteStoredObject("defense-assessment-new/users/7/material.pdf");
        } finally {
            execute(serverUrl, username, password, "DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    private void seedExpiredData(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO `user` (id, name, account, password, type)
                VALUES
                  (7, 'Teacher', 'retention-teacher', 'hash', 1),
                  (33, 'Student', 'retention-student', 'hash', 0)
                """);
        jdbc.update("""
                INSERT INTO assessment
                  (id, assessment_files, create_id, deleted, deleted_at, create_time, update_time)
                VALUES
                  (5, '[{"fileUrl":"defense-assessment-new/users/7/material.pdf"}]', 7, 1,
                   '2024-01-01 00:00:00', '2024-01-01 00:00:00', '2024-01-01 00:00:00')
                """);
        jdbc.update("""
                INSERT INTO student_assessment_record
                  (id, assessment_id, student_id, defense_file, defense_voice, state,
                   end_defense_time, create_time, update_time)
                VALUES
                  (11, 5, 33, '{"fileUrl":"defense-assessment-new/users/33/file.pdf"}',
                   'defense-assessment-new/system/defense.wav', 1,
                   '2024-01-01 00:00:00', '2024-01-01 00:00:00', '2024-01-01 00:00:00')
                """);
        jdbc.update("""
                INSERT INTO student_assessment_question_answer
                  (assessment_id, student_id, answer_voice, follow_answer_voice, create_time)
                VALUES
                  (5, 33, 'defense-assessment-new/system/answer.wav',
                   'defense-assessment-new/system/follow.wav', '2024-01-01 00:00:00')
                """);
        jdbc.update("""
                INSERT INTO token_consumer (method, param, result, create_time)
                VALUES ('test', 'requestChars=1', 'outputChars=1', '2024-01-01 00:00:00')
                """);
    }

    private int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
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
