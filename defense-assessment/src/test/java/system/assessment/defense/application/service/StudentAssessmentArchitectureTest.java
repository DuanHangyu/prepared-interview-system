package system.assessment.defense.application.service;

import org.junit.jupiter.api.Test;
import system.assessment.defense.application.dto.StudentDefenseStatisticDTO;
import system.assessment.defense.application.dto.WebSocketResponse;
import system.assessment.defense.application.dto.WebsocketMessageDTO;
import system.assessment.defense.application.dto.front.StudentAppointmentCmd;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentAssessmentArchitectureTest {

    private static final Path SERVICE_ROOT = Path.of(
            "src/main/java/system/assessment/defense/application/service");

    @Test
    void facadeDependsOnlyOnFocusedApplicationServices() {
        Set<Class<?>> dependencyTypes = List.of(StudentAssessmentService.class.getDeclaredFields())
                .stream()
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .collect(Collectors.toSet());

        assertThat(dependencyTypes).containsExactlyInAnyOrder(
                StudentAssessmentQueryService.class,
                StudentAppointmentApplicationService.class,
                StudentAssessmentAnalysisService.class,
                StudentInterviewSessionService.class
        );
    }

    @Test
    void focusedServicesStayWithinExplicitSizeBudgets() throws IOException {
        assertLineCount("StudentAssessmentService.java", 150);
        assertLineCount("StudentInterviewSessionService.java", 1_000);
        assertLineCount("StudentAssessmentQueryService.java", 600);
        assertLineCount("StudentAppointmentApplicationService.java", 220);
        assertLineCount("StudentAssessmentAnalysisService.java", 400);
    }

    @Test
    void facadeContainsNoInfrastructureOrSchedulerCode() throws IOException {
        String source = Files.readString(SERVICE_ROOT.resolve("StudentAssessmentService.java"));

        assertThat(source)
                .doesNotContain("infrastructure.repository")
                .doesNotContain("RedisTemplate")
                .doesNotContain("@Scheduled")
                .doesNotContain("@Transactional");
    }

    @Test
    void facadeRoutesEachResponsibilityToItsOwner() {
        StudentAssessmentQueryService queryService = mock(StudentAssessmentQueryService.class);
        StudentAppointmentApplicationService appointmentService =
                mock(StudentAppointmentApplicationService.class);
        StudentAssessmentAnalysisService analysisService =
                mock(StudentAssessmentAnalysisService.class);
        StudentInterviewSessionService interviewService =
                mock(StudentInterviewSessionService.class);
        StudentAssessmentService facade = new StudentAssessmentService(
                queryService, appointmentService, analysisService, interviewService);

        StudentDefenseStatisticDTO statistic = StudentDefenseStatisticDTO.empty();
        when(queryService.studentDefenseStatistic(7)).thenReturn(statistic);
        StudentAppointmentCmd appointment = new StudentAppointmentCmd();
        when(appointmentService.studentAppointment(7, appointment)).thenReturn(true);
        when(analysisService.reanalyze(9, 31)).thenReturn(true);
        WebsocketMessageDTO message = new WebsocketMessageDTO();
        Consumer<WebSocketResponse> consumer = ignored -> { };

        assertThat(facade.studentDefenseStatistic(7)).isSameAs(statistic);
        assertThat(facade.studentAppointment(7, appointment)).isTrue();
        assertThat(facade.reanalyze(9, 31)).isTrue();
        facade.handleWebsocketMessage(7, message, consumer);

        verify(queryService).studentDefenseStatistic(7);
        verify(appointmentService).studentAppointment(7, appointment);
        verify(analysisService).reanalyze(9, 31);
        verify(interviewService).handleWebsocketMessage(7, message, consumer);
    }

    private void assertLineCount(String fileName, int maximum) throws IOException {
        long lineCount;
        try (var lines = Files.lines(SERVICE_ROOT.resolve(fileName))) {
            lineCount = lines.count();
        }
        assertThat(lineCount)
                .as("%s must stay at or below %s lines", fileName, maximum)
                .isLessThanOrEqualTo(maximum);
    }
}
