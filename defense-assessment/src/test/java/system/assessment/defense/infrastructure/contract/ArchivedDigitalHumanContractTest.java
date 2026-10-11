package system.assessment.defense.infrastructure.contract;

import org.junit.jupiter.api.Test;
import system.assessment.defense.application.dto.AssessmentSettingsCreateCmd;
import system.assessment.defense.application.dto.AssessmentSettingsDTO;
import system.assessment.defense.application.dto.AssessmentSettingsModifyCmd;
import system.assessment.defense.application.dto.AssessmentSimpleDTO;
import system.assessment.defense.application.dto.StudentTodoDefenseDTO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArchivedDigitalHumanContractTest {

    private static final Path REPOSITORY_ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void publishedSchemaIsPreservedWhileRuntimeMappingsAreRemoved() throws IOException {
        String baseline = file("defense-assessment/src/main/resources/db/migration/V1__baseline_schema.sql");
        assertThat(baseline)
                .contains("`digital_human_id` INT DEFAULT NULL")
                .contains("CREATE TABLE IF NOT EXISTS `digital_human`");

        for (Class<?> activeType : List.of(
                AssessmentSettingsCreateCmd.class,
                AssessmentSettingsModifyCmd.class,
                AssessmentSettingsDTO.class,
                AssessmentSimpleDTO.class,
                StudentTodoDefenseDTO.class,
                AssessmentSettingPO.class)) {
            assertThat(Arrays.stream(activeType.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                    .as("active type %s", activeType.getSimpleName())
                    .doesNotContain("digitalHuman", "digitalHumanId");
        }
    }

    @Test
    void archivedFeatureHasNoExecutableJavaSurface() throws IOException {
        List<String> archivedJavaTypes = List.of(
                "defense-assessment/src/main/java/system/assessment/defense/application/dto/backed/DigitalHumanDTO.java",
                "defense-assessment/src/main/java/system/assessment/defense/infrastructure/repository/dao/po/DigitalHumanPO.java",
                "defense-assessment/src/main/java/system/assessment/defense/infrastructure/repository/dao/mapper/DigitalHumanMapper.java",
                "defense-assessment/src/main/java/system/assessment/defense/infrastructure/repository/dao/service/DigitalHumanService.java",
                "defense-assessment/src/main/java/system/assessment/defense/infrastructure/repository/dao/service/impl/DigitalHumanServiceImpl.java");
        for (String archivedType : archivedJavaTypes) {
            assertThat(Files.notExists(REPOSITORY_ROOT.resolve(archivedType)))
                    .as("archived runtime type %s", archivedType)
                    .isTrue();
        }

        for (String activePath : List.of(
                "defense-assessment/src/main/java/system/assessment/defense/application/service/AssessmentAppService.java",
                "defense-assessment/src/main/java/system/assessment/defense/application/service/StudentAssessmentService.java")) {
            assertThat(file(activePath))
                    .doesNotContain("DigitalHuman")
                    .doesNotContain("digitalHuman")
                    .doesNotContain("lipShape");
        }
    }

    @Test
    void archiveDecisionIsDocumentedAndFrontendHasNoConsumer() throws IOException {
        String archive = file("docs/ARCHIVED_FEATURES.md");
        assertThat(archive)
                .contains("数字人")
                .contains("digital_human")
                .contains("digital_human_id")
                .contains("只读封存")
                .contains("不得写入")
                .contains("不查询")
                .contains("不传输");

        try (var files = Files.walk(REPOSITORY_ROOT.resolve("defense-system/src"))) {
            for (Path source : files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".ts")
                            || path.toString().endsWith(".js")
                            || path.toString().endsWith(".vue"))
                    .toList()) {
                String content = Files.readString(source, StandardCharsets.UTF_8);
                assertThat(content)
                        .as("active frontend source %s", source)
                        .doesNotContain("digitalHuman")
                        .doesNotContain("digital_human")
                        .doesNotContain("lipShape");
            }
        }
    }

    private String file(String relativePath) throws IOException {
        Path path = REPOSITORY_ROOT.resolve(relativePath);
        assertThat(Files.exists(path)).as("contract file %s", relativePath).isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
