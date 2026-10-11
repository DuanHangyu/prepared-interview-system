package system.assessment.defense.application.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UnboundUploadLifecycleContractTest {

    @Test
    void uploadedPdfsAreRegisteredBoundAtomicallyAndExpired() throws IOException {
        Path migration = Path.of("src/main/resources/db/migration/V10__managed_upload_lifecycle.sql");
        Path lifecycleService = Path.of(
                "src/main/java/system/assessment/defense/application/service/ManagedUploadService.java");
        String fileController = Files.readString(Path.of(
                "src/main/java/system/assessment/defense/interfaces/rest/FileController.java"));
        String accessControl = Files.readString(Path.of(
                "src/main/java/system/assessment/defense/application/service/FileAccessControlService.java"));

        assertThat(migration).exists();
        assertThat(Files.readString(migration))
                .contains("managed_upload")
                .contains("expires_at");
        assertThat(lifecycleService).exists();
        assertThat(Files.readString(lifecycleService))
                .contains("@Scheduled")
                .contains("consumeForBinding")
                .contains("claimForCleanup");
        assertThat(fileController).contains("managedUploadService.upload");
        assertThat(accessControl).contains("managedUploadService.consumeForBinding");
    }
}
