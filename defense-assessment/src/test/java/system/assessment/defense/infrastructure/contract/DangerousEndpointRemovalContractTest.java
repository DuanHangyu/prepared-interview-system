package system.assessment.defense.infrastructure.contract;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DangerousEndpointRemovalContractTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    @Test
    void productionSourceDoesNotExposeModelPlaygroundOrBulkAiMutationEndpoint() throws Exception {
        String sources;
        try (var paths = Files.walk(MAIN_JAVA)) {
            sources = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(DangerousEndpointRemovalContractTest::read)
                    .reduce("", (left, right) -> left + "\n" + right);
        }

        assertThat(sources)
                .doesNotContain("@RequestMapping(\"/gemini\")")
                .doesNotContain("@GetMapping(\"/executor\")")
                .doesNotContain("batchAnalysisUserInfo(");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }
}
