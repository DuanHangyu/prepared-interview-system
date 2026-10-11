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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ManagedUploadMySqlIT {

    private static OwnedMigrationMysql ownedMysql;

    @BeforeAll
    static void openOwnedMysql() throws Exception {
        ownedMysql = OwnedMigrationMysql.openIfEnabled();
    }

    @AfterAll
    static void closeOwnedMysql() throws Exception {
        if (ownedMysql != null) ownedMysql.close();
    }

    private static final String KEY =
            "defense-assessment-new/users/7/2026/10/03/0123456789abcdef0123456789abcdef.pdf";

    @Test
    void bindingConsumptionIsTransactionalAndExpiredUploadsAreDeleted() throws SQLException {
        String serverUrl = ownedMysql == null ? System.getenv("MYSQL_INTEGRATION_SERVER_URL") : ownedMysql.serverUrl();
        Assumptions.assumeTrue(serverUrl != null && !serverUrl.isBlank(),
                "MYSQL_INTEGRATION_SERVER_URL is required for this integration test");
        String username = ownedMysql == null ? System.getenv().getOrDefault("MYSQL_INTEGRATION_USERNAME", "root") : ownedMysql.username();
        String password = ownedMysql == null ? System.getenv().getOrDefault("MYSQL_INTEGRATION_PASSWORD", "") : ownedMysql.password();
        String database = "ai_upload_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String schemaUrl = schemaUrl(serverUrl, database);

        try {
            execute(serverUrl, username, password,
                    "CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            Flyway.configure().dataSource(schemaUrl, username, password)
                    .locations("classpath:db/migration").load().migrate();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(schemaUrl, username, password);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            ManagedUploadRepository repository = new ManagedUploadRepository(jdbc);
            TransactionTemplate transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));
            LocalDateTime now = LocalDateTime.of(2026, 10, 3, 9, 0);

            repository.register(7, KEY, 1024, now.plusHours(24));
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                assertThat(repository.consumeForBinding(7, KEY, now)).isEqualTo(1);
                throw new IllegalStateException("roll back business binding");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(uploadCount(jdbc)).isEqualTo(1);

            transaction.executeWithoutResult(status ->
                    assertThat(repository.consumeForBinding(7, KEY, now)).isEqualTo(1));
            assertThat(uploadCount(jdbc)).isZero();

            repository.register(7, KEY, 1024, now.minusMinutes(1));
            ManagedUploadService service = new ManagedUploadService(
                    repository, mock(OssManager.class), mock(FileSecurityService.class),
                    mock(MySqlAdvisoryLockService.class));
            assertThat(service.processExpiredUploads(now)).isEqualTo(1);
            assertThat(uploadCount(jdbc)).isZero();
        } finally {
            execute(serverUrl, username, password, "DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    private int uploadCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM managed_upload", Integer.class);
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
