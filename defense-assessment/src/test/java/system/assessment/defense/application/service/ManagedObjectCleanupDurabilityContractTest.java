package system.assessment.defense.application.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedObjectCleanupDurabilityContractTest {

    @Test
    void postCommitObjectDeletionUsesADurableRetryQueue() throws IOException {
        Path migration = Path.of("src/main/resources/db/migration/V9__managed_object_cleanup_outbox.sql");
        String cleanupService = Files.readString(Path.of(
                "src/main/java/system/assessment/defense/application/service/ManagedObjectCleanupService.java"));
        Path retryService = Path.of(
                "src/main/java/system/assessment/defense/application/service/ManagedObjectCleanupRetryService.java");

        assertThat(migration).exists();
        assertThat(Files.readString(migration))
                .contains("managed_object_cleanup_task")
                .contains("next_attempt_at");
        assertThat(cleanupService)
                .contains("ManagedObjectCleanupTaskRepository")
                .contains("enqueue");
        assertThat(retryService).exists();
        assertThat(Files.readString(retryService))
                .contains("@Scheduled")
                .contains("markFailure")
                .contains("deleteById");
    }
}
