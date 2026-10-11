package system.assessment.defense.infrastructure.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseMigrationContractTest {

    private static final List<String> BASELINE_TABLES = List.of(
            "user",
            "assessment",
            "assessment_appointment_setting",
            "assessment_invite",
            "assessment_student_relation",
            "digital_human",
            "lock_key",
            "project_type",
            "student_assessment_appointment",
            "student_assessment_question_answer",
            "student_assessment_record",
            "token_consumer"
    );

    @Test
    void baselineCreatesEveryApplicationTableWithoutDestructiveDrops() throws IOException {
        String baseline = resource("db/migration/V1__baseline_schema.sql");

        for (String table : BASELINE_TABLES) {
            assertThat(baseline)
                    .as("baseline table %s", table)
                    .containsIgnoringCase("CREATE TABLE IF NOT EXISTS `" + table + "`");
        }
        assertThat(baseline).doesNotContainIgnoringCase("DROP TABLE");
    }

    @Test
    void integrityMigrationEnforcesBusinessUniquenessWithoutDeletingLegacyRows() throws IOException {
        String migration = resource("db/migration/V2__data_integrity_constraints.sql");

        assertThat(migration)
                .contains("UNIQUE KEY `uk_record_assessment_student` (`assessment_id`, `student_id`)")
                .contains("UNIQUE KEY `uk_appointment_assessment_student` (`assessment_id`, `student_id`)")
                .contains("UNIQUE KEY `uk_relation_assessment_student` (`assessment_id`, `student_id`)")
                .contains("UNIQUE KEY `uk_invite_assessment_teacher` (`assessment_id`, `invitee_teacher_id`)")
                .doesNotContainIgnoringCase("DELETE FROM")
                .doesNotContainIgnoringCase("DROP TABLE");
    }

    @Test
    void flywayConfigurationSupportsFreshAndExistingDatabases() throws IOException {
        String application = resource("application.yml");

        assertThat(application)
                .contains("flyway:")
                .contains("enabled: true")
                .contains("baseline-on-migrate: true")
                .contains("baseline-version: 1")
                .contains("locations: classpath:db/migration");
    }

    @Test
    void interviewLifecycleMigrationAddsTheAtomicQuestionStartMarker() throws IOException {
        String migration = resource("db/migration/V3__interview_lifecycle_state.sql");

        assertThat(migration)
                .containsIgnoringCase("ADD COLUMN `question_started_at` DATETIME NULL")
                .doesNotContainIgnoringCase("DELETE FROM")
                .doesNotContainIgnoringCase("DROP TABLE");
    }

    @Test
    void retentionMigrationMakesCleanupTimestampsReliableAndIndexed() throws IOException {
        String migration = resource("db/migration/V4__data_retention_support.sql");

        assertThat(migration)
                .contains("`deleted_at`")
                .contains("`idx_token_consumer_create_time`")
                .contains("`idx_record_retention`")
                .contains("`idx_assessment_retention`")
                .containsIgnoringCase("DEFAULT CURRENT_TIMESTAMP")
                .doesNotContainIgnoringCase("DROP TABLE");
    }

    @Test
    void foreignKeyMigrationRejectsOrphansAndProtectsUserOwnership() throws IOException {
        String migration = resource("db/migration/V5__referential_integrity_constraints.sql");

        assertThat(migration)
                .contains("CREATE PROCEDURE flyway_precheck_v5_references")
                .contains("CONSTRAINT `fk_assessment_creator`")
                .contains("CONSTRAINT `fk_invite_assessment`")
                .contains("CONSTRAINT `fk_invite_invitee_teacher`")
                .contains("CONSTRAINT `fk_invite_inviter_teacher`")
                .contains("CONSTRAINT `fk_relation_assessment`")
                .contains("CONSTRAINT `fk_relation_student`")
                .contains("CONSTRAINT `fk_appointment_setting_assessment`")
                .contains("CONSTRAINT `fk_student_appointment_assessment`")
                .contains("CONSTRAINT `fk_student_appointment_student`")
                .contains("CONSTRAINT `fk_answer_assessment`")
                .contains("CONSTRAINT `fk_answer_student`")
                .contains("CONSTRAINT `fk_record_assessment`")
                .contains("CONSTRAINT `fk_record_student`")
                .containsIgnoringCase("ON DELETE RESTRICT")
                .containsIgnoringCase("ON DELETE CASCADE")
                .containsIgnoringCase("SIGNAL SQLSTATE '45000'")
                .doesNotContainIgnoringCase("DELETE FROM")
                .doesNotContainIgnoringCase("DROP TABLE");
    }

    @Test
    void userRoleMigrationRejectsInvalidLegacyValuesAndConstrainsFutureWrites() throws IOException {
        String migration = resource("db/migration/V6__user_role_integrity.sql");

        assertThat(migration)
                .contains("CREATE PROCEDURE flyway_precheck_v6_user_roles")
                .containsIgnoringCase("`type` NOT IN (0, 1, 2)")
                .containsIgnoringCase("MODIFY COLUMN `type` INT NOT NULL")
                .contains("CONSTRAINT `chk_user_role`")
                .containsIgnoringCase("CHECK (`type` IN (0, 1, 2))")
                .containsIgnoringCase("SIGNAL SQLSTATE '45000'")
                .doesNotContainIgnoringCase("DELETE FROM")
                .doesNotContainIgnoringCase("DROP TABLE");
    }

    @Test
    void classAssignmentMigrationPreservesTheSourceRelationship() throws IOException {
        String migration = resource("db/migration/V7__assessment_school_class_assignments.sql");

        assertThat(migration)
                .containsIgnoringCase("CREATE TABLE `assessment_school_class`")
                .contains("UNIQUE KEY `uk_assessment_school_class` (`assessment_id`, `school_class`)")
                .contains("KEY `idx_assessment_school_class_class` (`school_class`)")
                .contains("CONSTRAINT `fk_assessment_school_class_assessment`")
                .containsIgnoringCase("INSERT INTO `assessment_school_class`")
                .containsIgnoringCase("`relation_type` = 1")
                .doesNotContainIgnoringCase("DELETE FROM")
                .doesNotContainIgnoringCase("DROP TABLE");
    }

    @Test
    void teacherScoreMigrationSeparatesUnreviewedRecordsFromExplicitZeroScores() throws IOException {
        String migration = resource("db/migration/V8__nullable_unreviewed_teacher_scores.sql");

        assertThat(migration)
                .containsIgnoringCase("UPDATE `student_assessment_record`")
                .containsIgnoringCase("SET `check_score` = NULL")
                .containsIgnoringCase("WHERE `check_score` = 0")
                .doesNotContainIgnoringCase("DROP TABLE");
    }

    @Test
    void objectCleanupMigrationCreatesADurableDueQueue() throws IOException {
        String migration = resource("db/migration/V9__managed_object_cleanup_outbox.sql");

        assertThat(migration)
                .containsIgnoringCase("CREATE TABLE `managed_object_cleanup_task`")
                .contains("`object_reference` VARCHAR(1000) NOT NULL")
                .contains("`next_attempt_at` DATETIME NOT NULL")
                .contains("`idx_object_cleanup_due`")
                .doesNotContainIgnoringCase("DROP TABLE");
    }

    @Test
    void uploadLifecycleMigrationTracksPendingObjectsAndExpiration() throws IOException {
        String migration = resource("db/migration/V10__managed_upload_lifecycle.sql");

        assertThat(migration)
                .containsIgnoringCase("CREATE TABLE `managed_upload`")
                .contains("`object_reference` VARCHAR(512) NOT NULL")
                .contains("`owner_id` INT NOT NULL")
                .contains("`expires_at` DATETIME NOT NULL")
                .contains("`uk_managed_upload_reference`")
                .contains("`idx_managed_upload_due`")
                .doesNotContainIgnoringCase("DROP TABLE");
    }

    private String resource(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        assertThat(resource.exists()).as("classpath resource %s", path).isTrue();
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
