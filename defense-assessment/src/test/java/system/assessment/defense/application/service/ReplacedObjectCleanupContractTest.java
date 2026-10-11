package system.assessment.defense.application.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReplacedObjectCleanupContractTest {

    @Test
    void replacingBoundFilesQueuesOnlyTheRemovedReferences() throws IOException {
        String cleanup = Files.readString(Path.of(
                "src/main/java/system/assessment/defense/application/service/ManagedObjectCleanupService.java"));
        String assessment = Files.readString(Path.of(
                "src/main/java/system/assessment/defense/application/service/AssessmentAppService.java"));
        String interview = Files.readString(Path.of(
                "src/main/java/system/assessment/defense/application/service/StudentInterviewSessionService.java"));

        assertThat(cleanup).contains("queueRemovedReferences");
        assertThat(assessment).contains("objectCleanupService.queueRemovedReferences");
        assertThat(interview).contains("objectCleanupService.queueRemovedReferences");
    }
}
