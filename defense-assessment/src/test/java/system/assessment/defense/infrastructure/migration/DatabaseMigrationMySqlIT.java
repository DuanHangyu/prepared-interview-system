package system.assessment.defense.infrastructure.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMigrationMySqlIT {

    private static OwnedMigrationMysql ownedMysql;

    @BeforeAll
    static void openOwnedMysql() throws Exception {
        ownedMysql = OwnedMigrationMysql.openIfEnabled();
    }

    @AfterAll
    static void closeOwnedMysql() throws Exception {
        if (ownedMysql != null) ownedMysql.close();
    }

    // This new project's additive migration contract includes V12--V37. Keep the
    // expectation explicit; discovering the current version would hide omissions.
    private static final int LATEST_VERSION = 37;
    private static final String SERVER_URL_ENV = "MYSQL_INTEGRATION_SERVER_URL";
    private static final String USER_ENV = "MYSQL_INTEGRATION_USERNAME";
    private static final String PASSWORD_ENV = "MYSQL_INTEGRATION_PASSWORD";

    @Test
    void freshDatabaseMigratesFromEmptySchemaToLatestVersion() throws SQLException {
        IntegrationDatabase database = createDatabase("fresh");
        try {
            var result = flyway(database).migrate();

            assertThat(result.migrationsExecuted).isEqualTo(LATEST_VERSION);
            assertThat(currentVersion(database)).isEqualTo(Integer.toString(LATEST_VERSION));
            assertThat(rowCount(database, "managed_object_cleanup_task")).isZero();
            assertThat(rowCount(database, "managed_upload")).isZero();
            assertBusinessUniqueIndexes(database);
            assertReferentialConstraints(database);
            assertUserRoleConstraint(database);
        } finally {
            database.drop();
        }
    }

    @Test
    void populatedLegacyDatabaseIsBaselinedAndUpgradedWithoutLosingRows() throws SQLException {
        IntegrationDatabase database = createDatabase("upgrade");
        try {
            installLegacySchema(database);
            execute(database, "INSERT INTO `user` (`id`, `name`, `account`, `password`, `type`) " +
                    "VALUES (20, 'Student', 'student-20', 'hash', 0), " +
                    "(30, 'Teacher', 'teacher-30', 'hash', 1)");
            execute(database, "UPDATE `user` SET `school_class` = 'Class 1' WHERE `id` = 20");
            execute(database, "INSERT INTO `assessment` (`id`, `theme`, `create_id`, `deleted`) " +
                    "VALUES (10, 'Legacy assessment', 30, 0)");
            execute(database, "INSERT INTO `assessment_student_relation` " +
                    "(`assessment_id`, `student_id`, `relation_type`) VALUES (10, 20, 1)");
            execute(database, "INSERT INTO `student_assessment_record` " +
                    "(`assessment_id`, `student_id`, `state`, `check_score`) VALUES (10, 20, 0, 0)");

            var result = flyway(database).migrate();

            assertThat(result.migrationsExecuted).isEqualTo(LATEST_VERSION - 1);
            assertThat(currentVersion(database)).isEqualTo(Integer.toString(LATEST_VERSION));
            assertThat(rowCount(database, "managed_object_cleanup_task")).isZero();
            assertThat(rowCount(database, "managed_upload")).isZero();
            assertThat(rowCount(database, "student_assessment_record")).isEqualTo(1);
            assertThat(rowCountWhere(database, "student_assessment_record", "`check_score` IS NULL"))
                    .isEqualTo(1);
            assertThat(rowCount(database, "assessment_student_relation")).isEqualTo(1);
            assertThat(rowCount(database, "assessment_school_class")).isEqualTo(1);
            assertBusinessUniqueIndexes(database);
            assertReferentialConstraints(database);
            assertUserRoleConstraint(database);
        } finally {
            database.drop();
        }
    }

    @Test
    void duplicateLegacyRowsBlockUpgradeBeforeAnyConstraintIsPartiallyApplied() throws SQLException {
        IntegrationDatabase database = createDatabase("duplicates");
        try {
            installLegacySchema(database);
            execute(database, "INSERT INTO `student_assessment_record` " +
                    "(`assessment_id`, `student_id`, `state`) VALUES (10, 20, 0), (10, 20, 1)");

            assertThatThrownBy(() -> flyway(database).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("duplicate student assessment records");

            assertThat(rowCount(database, "student_assessment_record")).isEqualTo(2);
            assertThat(uniqueIndexCount(database, "uk_record_assessment_student")).isZero();
            assertThat(uniqueIndexCount(database, "uk_appointment_assessment_student")).isZero();
            assertThat(uniqueIndexCount(database, "uk_relation_assessment_student")).isZero();
            assertThat(uniqueIndexCount(database, "uk_invite_assessment_teacher")).isZero();
        } finally {
            database.drop();
        }
    }

    @Test
    void orphanedLegacyRowsBlockForeignKeysBeforeAnyConstraintIsPartiallyApplied() throws SQLException {
        IntegrationDatabase database = createDatabase("orphans");
        try {
            installLegacySchema(database);
            execute(database, "INSERT INTO `student_assessment_record` " +
                    "(`assessment_id`, `student_id`, `state`) VALUES (10, 20, 0)");

            assertThatThrownBy(() -> flyway(database).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("student_assessment_record has a missing reference");

            assertThat(rowCount(database, "student_assessment_record")).isEqualTo(1);
            assertThat(foreignKeyCount(database)).isZero();
        } finally {
            database.drop();
        }
    }

    @Test
    void invalidLegacyUserRoleBlocksUpgradeBeforeRoleConstraintIsApplied() throws SQLException {
        IntegrationDatabase database = createDatabase("invalid_role");
        try {
            installLegacySchema(database);
            execute(database, "INSERT INTO `user` (`id`, `name`, `account`, `password`, `type`) " +
                    "VALUES (20, 'Corrupt user', 'corrupt-user', 'hash', 9)");

            assertThatThrownBy(() -> flyway(database).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("user table contains invalid role values");

            assertThat(rowCount(database, "user")).isEqualTo(1);
            assertThat(checkConstraintCount(database, "chk_user_role")).isZero();
        } finally {
            database.drop();
        }
    }

    @Test
    void duplicateLegacyAppointmentPeriodsBlockUniqueUpgradeWithoutDeletingRows() throws SQLException {
        IntegrationDatabase database = createDatabase("duplicate_periods");
        try {
            installLegacySchema(database);
            execute(database, "INSERT INTO `user` (`id`, `name`, `account`, `password`, `type`) " +
                    "VALUES (30, 'Teacher', 'teacher-30', 'hash', 1)");
            execute(database, "INSERT INTO `assessment` (`id`, `theme`, `create_id`, `deleted`) " +
                    "VALUES (10, 'Legacy assessment', 30, 0)");
            execute(database, "INSERT INTO `assessment_appointment_setting` " +
                    "(`assessment_id`, `time_period`, `participant_limit`, `location`) VALUES " +
                    "(10, '2026-10-11 09:00:00', 10, 'A101'), " +
                    "(10, '2026-10-11 09:00:00', 20, 'A102')");

            assertThatThrownBy(() -> flyway(database).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("duplicate assessment appointment periods");

            assertThat(rowCount(database, "assessment_appointment_setting")).isEqualTo(2);
            assertThat(uniqueIndexCount(database, "uk_appointment_setting_assessment_time")).isZero();
        } finally {
            database.drop();
        }
    }

    private IntegrationDatabase createDatabase(String scenario) throws SQLException {
        String serverUrl = ownedMysql == null ? System.getenv(SERVER_URL_ENV) : ownedMysql.serverUrl();
        Assumptions.assumeTrue(serverUrl != null && !serverUrl.isBlank(),
                () -> SERVER_URL_ENV + " is required for the real MySQL migration test");
        String name = "ai_migration_it_" + scenario + "_" +
                UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        IntegrationDatabase database = new IntegrationDatabase(
                serverUrl,
                ownedMysql == null ? System.getenv().getOrDefault(USER_ENV, "root") : ownedMysql.username(),
                ownedMysql == null ? System.getenv().getOrDefault(PASSWORD_ENV, "") : ownedMysql.password(),
                name
        );
        try (Connection connection = DriverManager.getConnection(serverUrl, database.username(), database.password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + name +
                    "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        return database;
    }

    private Flyway flyway(IntegrationDatabase database) {
        return Flyway.configure()
                .dataSource(database.schemaUrl(), database.username(), database.password())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .cleanDisabled(true)
                .load();
    }

    private void installLegacySchema(IntegrationDatabase database) throws SQLException {
        try (Connection connection = database.connect()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1__baseline_schema.sql"));
        }
    }

    private void assertBusinessUniqueIndexes(IntegrationDatabase database) throws SQLException {
        assertThat(uniqueIndexCount(database, "uk_record_assessment_student")).isEqualTo(2);
        assertThat(uniqueIndexCount(database, "uk_appointment_assessment_student")).isEqualTo(2);
        assertThat(uniqueIndexCount(database, "uk_relation_assessment_student")).isEqualTo(2);
        assertThat(uniqueIndexCount(database, "uk_invite_assessment_teacher")).isEqualTo(2);
        assertThat(uniqueIndexCount(database, "uk_appointment_setting_assessment_time")).isEqualTo(2);
        assertThat(uniqueIndexCount(database, "uk_prepared_retirement_preparation_key")).isEqualTo(4);
        assertThat(uniqueIndexCount(database, "uk_prepared_retirement_preparation_revision")).isEqualTo(4);
        assertThat(uniqueIndexCount(database, "uk_prepared_retirement_preparation_execution_scope")).isEqualTo(5);
        assertThat(uniqueIndexCount(database, "uk_prepared_retirement_execution_scope")).isEqualTo(3);
        assertThat(uniqueIndexCount(database, "uk_prepared_retirement_execution_key")).isEqualTo(4);
        assertThat(uniqueIndexCount(database, "uk_prepared_retirement_execution_preparation")).isEqualTo(1);
    }

    private void assertReferentialConstraints(IntegrationDatabase database) throws SQLException {
        assertThat(foreignKeyCount(database)).isEqualTo(109);
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT column_name,referenced_table_name,referenced_column_name FROM information_schema.key_column_usage " +
                             "WHERE constraint_schema=? AND constraint_name='fk_prepared_retirement_preparation_attempt' ORDER BY ordinal_position")) {
            statement.setString(1,database.name());var bindings=new ArrayList<List<String>>();
            try(ResultSet rows=statement.executeQuery()){while(rows.next())bindings.add(List.of(rows.getString(1),rows.getString(2),rows.getString(3)));}
            assertThat(bindings).containsExactly(List.of("assessment_id","prepared_attempt","assessment_id"),
                    List.of("student_id","prepared_attempt","student_id"),List.of("attempt_id","prepared_attempt","id"));
        }
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT column_name,referenced_table_name,referenced_column_name FROM information_schema.key_column_usage " +
                             "WHERE constraint_schema=? AND constraint_name='fk_prepared_retirement_execution_preparation' ORDER BY ordinal_position")) {
            statement.setString(1,database.name());var bindings=new ArrayList<List<String>>();
            try(ResultSet rows=statement.executeQuery()){while(rows.next())bindings.add(List.of(rows.getString(1),rows.getString(2),rows.getString(3)));}
            assertThat(bindings).containsExactly(List.of("assessment_id","prepared_audio_retirement_preparation","assessment_id"),
                    List.of("student_id","prepared_audio_retirement_preparation","student_id"),List.of("attempt_id","prepared_audio_retirement_preparation","attempt_id"),
                    List.of("preparation_id","prepared_audio_retirement_preparation","id"),List.of("expected_revision","prepared_audio_retirement_preparation","revision"));
        }
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.referential_constraints " +
                             "WHERE constraint_schema = ? AND table_name NOT LIKE 'prepared%'");) {
            statement.setString(1, database.name());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).as("all 14 legacy foreign keys remain intact").isEqualTo(14);
            }
        }
        assertThatThrownBy(() -> execute(database,
                "INSERT INTO prepared_paper_draft(assessment_id, revision) VALUES (2147483647, 0)"))
                .as("a new draft cannot reference a missing assessment")
                .isInstanceOf(SQLException.class);
        assertThat(rowCount(database, "prepared_paper_draft")).isZero();
    }

    private void assertUserRoleConstraint(IntegrationDatabase database) throws SQLException {
        assertThat(checkConstraintCount(database, "chk_user_role")).isEqualTo(1);
        assertThatThrownBy(() -> execute(database,
                "INSERT INTO `user` (`name`, `account`, `password`, `type`) " +
                        "VALUES ('Invalid role', 'invalid-role', 'hash', 9)"))
                .isInstanceOf(SQLException.class);
    }

    private int foreignKeyCount(IntegrationDatabase database) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.referential_constraints " +
                "WHERE constraint_schema = ?";
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private int uniqueIndexCount(IntegrationDatabase database, String indexName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.statistics " +
                "WHERE table_schema = ? AND index_name = ? AND non_unique = 0";
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database.name());
            statement.setString(2, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private int checkConstraintCount(IntegrationDatabase database, String constraintName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.table_constraints " +
                "WHERE constraint_schema = ? AND table_name = 'user' " +
                "AND constraint_name = ? AND constraint_type = 'CHECK'";
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database.name());
            statement.setString(2, constraintName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private String currentVersion(IntegrationDatabase database) throws SQLException {
        String sql = "SELECT `version` FROM `flyway_schema_history` " +
                "WHERE `success` = 1 ORDER BY `installed_rank` DESC LIMIT 1";
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private int rowCount(IntegrationDatabase database, String table) throws SQLException {
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int rowCountWhere(IntegrationDatabase database, String table, String condition) throws SQLException {
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM `" + table + "` WHERE " + condition)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void execute(IntegrationDatabase database, String sql) throws SQLException {
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record IntegrationDatabase(String serverUrl, String username, String password, String name) {

        String schemaUrl() {
            int queryStart = serverUrl.indexOf('?');
            String base = queryStart >= 0 ? serverUrl.substring(0, queryStart) : serverUrl;
            String query = queryStart >= 0 ? serverUrl.substring(queryStart) : "";
            String separator = base.endsWith("/") ? "" : "/";
            return base + separator + name + query;
        }

        Connection connect() throws SQLException {
            return DriverManager.getConnection(schemaUrl(), username, password);
        }

        void drop() throws SQLException {
            try (Connection connection = DriverManager.getConnection(serverUrl, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }
    }
}
