package system.assessment.defense.interfaces.rest.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import system.assessment.defense.application.dto.StudentOptionDTO;
import system.assessment.defense.application.service.FileSecurityService;
import system.assessment.defense.application.service.StudentAppService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentBackendControllerTest {

    @Mock private StudentAppService studentAppService;
    @Mock private FileSecurityService fileSecurityService;

    @InjectMocks private StudentBackendController controller;

    @Test
    void schoolClassStudentListReturnsLazyClassNodes() {
        when(studentAppService.allSchoolClassOptions()).thenReturn(List.of("Class 1", "Class 2"));

        assertThat(controller.schoolClassStudentList())
                .extracting(group -> group.getSchoolClass())
                .containsExactly("Class 1", "Class 2");
        assertThat(controller.schoolClassStudentList())
                .allSatisfy(group -> assertThat(group.getStudents()).isEmpty());
    }

    @Test
    void schoolClassStudentOptionsLoadsOnlyTheRequestedClass() {
        StudentOptionDTO student = StudentOptionDTO.builder()
                .id(3)
                .name("Student")
                .schoolClass("Class 1")
                .build();
        when(studentAppService.studentOptionsBySchoolClass("Class 1"))
                .thenReturn(List.of(student));

        assertThat(controller.schoolClassStudentOptions("Class 1"))
                .containsExactly(student);
        verify(studentAppService).studentOptionsBySchoolClass("Class 1");
    }
}
