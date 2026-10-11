package system.assessment.defense.infrastructure.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyFrontendSurfaceContractTest {

    private static final Path REPOSITORY_ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void unreachablePagesComponentsAndApiAdaptersAreRemoved() throws IOException {
        List<String> deadPaths = List.of(
                "defense-system/src/views/ResultPage.vue",
                "defense-system/src/views/Dashboard.vue",
                "defense-system/src/views/QuestionPage.vue",
                "defense-system/src/views/studentDashboard/defense/index.vue",
                "defense-system/src/views/studentDashboard/questions/index.vue",
                "defense-system/src/views/studyDetail/index copy.vue",
                "defense-system/src/views/studyDetail/AudioPlayer.vue",
                "defense-system/src/views/studyDetail/chart.ts",
                "defense-system/src/components/AbilityPie.vue",
                "defense-system/src/components/ScoreGauge.vue",
                "defense-system/src/components/ScoreProgress.vue",
                "defense-system/src/components/CountdownTimer.vue",
                "defense-system/src/components/RadarChart.vue",
                "defense-system/src/views/teacherDashboard/components/EllipsisText.vue",
                "defense-system/src/utils/file.ts",
                "defense-system/src/api/kh.ts",
                "defense-system/src/api/projectType.ts",
                "defense-assessment/src/main/java/system/assessment/defense/interfaces/rest/ProjectTypeController.java",
                "defense-assessment/src/main/java/system/assessment/defense/application/service/ProjectTypeAppService.java",
                "defense-assessment/src/main/java/system/assessment/defense/infrastructure/repository/dao/po/ProjectTypePO.java",
                "defense-assessment/src/main/java/system/assessment/defense/application/dto/backed/TeacherModifyPasswordCmd.java"
        );

        for (String deadPath : deadPaths) {
            assertThat(Files.notExists(REPOSITORY_ROOT.resolve(deadPath)))
                    .as("legacy path %s must be removed", deadPath)
                    .isTrue();
        }

        assertThat(file("defense-system/src/router/index.ts"))
                // Match the retired exact route/import, not a legitimate PreparedResultPage.
                .doesNotContainPattern("[\"'](?:@/views/ResultPage\\.vue|ResultPage)[\"']")
                .doesNotContain("path: \"/result\"");
        assertThat(file("defense-system/src/api/studentAssessment.ts"))
                .doesNotContain("save-answer")
                .doesNotContain("saveAnswer");
        assertThat(file("defense-assessment/src/main/java/system/assessment/defense/interfaces/rest/backend/TeacherBackendController.java"))
                .doesNotContain("modify-password")
                .doesNotContain("modifyPassword");
        assertThat(file("defense-system/src/components/Layout/componets/StudentContainter.vue"))
                .doesNotContain("ResultPage");
    }

    @Test
    void activeStudentRoutesCarryPreparedIdAndKeepLegacyIdValidation() throws IOException {
        String dashboard = file("defense-system/src/views/studentDashboard/index.vue");
        String questions = file("defense-system/src/views/studentDashboard/questions/index2.vue");

        assertThat(dashboard)
                .contains("Number.isSafeInteger(Number(e?.id))")
                .contains("name: \"PreparedPreflight\", params: { assessmentId: String(e?.id) }")
                .doesNotContain("router.push(`/question?id=")
                .doesNotContain("router.push(`/dashboard?id=");
        assertThat(file("defense-system/src/views/prepared/PreparedPreflightPage.vue"))
                .contains("if (!assessmentId())")
                .contains("getPreparedPreflightContext(assessmentId(), controller.signal)")
                .doesNotContain("new WebSocket");
        assertThat(questions)
                .contains("const assessmentId = Number(route.query.id)")
                .contains("Number.isInteger(assessmentId)")
                .contains("assessmentId <= 0");
    }

    @Test
    void productionEndpointsAreSameOriginAndDeadEnvironmentVariablesAreGone() throws IOException {
        for (String environment : List.of(".env.production", ".env.formal")) {
            String content = file("defense-system/" + environment);
            assertThat(content)
                    .contains("VUE_APP_BASE_API = '/api'")
                    .contains("VUE_APP_BASE_TEXT_WS = '/ws/defense-assessment'")
                    .doesNotContain("localhost")
                    .doesNotContain("defense.hotsupper.top")
                    .doesNotContain("web.deepolylink.com")
                    .doesNotContain("VUE_APP_API_BASE_URL")
                    .doesNotContain("VUE_APP_WEBSOCKET_BASE_URL")
                    .doesNotContain("VUE_APP_BASE_URL");
        }

        assertThat(file("defense-system/src/views/teacherDashboard/StudyRecord.vue"))
                .doesNotContain("getProjectTypeList")
                .doesNotContain("projectTypes");
        assertThat(file("defense-system/Dockerfile")).doesNotContain("VUE_APP_API_BASE_URL");
        assertThat(file("compose.yaml")).doesNotContain("VUE_APP_API_BASE_URL");
        assertThat(file("defense-assessment/src/main/resources/application.yml"))
                .doesNotContain("defense.hotsupper.top")
                .doesNotContain("web.deepolylink.com");
        assertThat(file("defense-system/src/views/studentDashboard/index.vue"))
                .doesNotContain("S2024001024")
                .doesNotContain("本科 · 大三");
    }

    private String file(String relativePath) throws IOException {
        Path path = REPOSITORY_ROOT.resolve(relativePath);
        assertThat(Files.exists(path)).as("contract file %s", relativePath).isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
