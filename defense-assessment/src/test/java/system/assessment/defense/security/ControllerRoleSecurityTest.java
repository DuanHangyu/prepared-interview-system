package system.assessment.defense.security;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import org.junit.jupiter.api.Test;
import system.assessment.defense.interfaces.rest.FileController;
import system.assessment.defense.interfaces.rest.backend.StudentBackendController;
import system.assessment.defense.interfaces.rest.backend.TeacherBackendController;
import system.assessment.defense.interfaces.rest.front.AssessmentController;
import system.assessment.defense.interfaces.rest.front.StudentAssessmentController;
import system.assessment.defense.interfaces.rest.backend.AssessmentSettingsController;
import system.assessment.defense.interfaces.rest.backend.StudyRecordController;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerRoleSecurityTest {

    @Test
    void studentControllersRequireStudentRole() {
        assertRoles(StudentAssessmentController.class, "STUDENT");
        assertRoles(AssessmentController.class, "STUDENT");
    }

    @Test
    void assessmentAndRecordControllersRequireTeacherOrAdminRole() {
        assertRoles(AssessmentSettingsController.class, "TEACHER", "ADMIN");
        assertRoles(StudyRecordController.class, "TEACHER", "ADMIN");
    }

    @Test
    void fileControllerExplicitlyAllowsOnlyKnownAuthenticatedRoles() {
        assertRoles(FileController.class, "STUDENT", "TEACHER", "ADMIN");
    }

    @Test
    void mixedBackendControllersDeclareTeacherAndAdminBoundary() {
        assertRoles(StudentBackendController.class, "TEACHER", "ADMIN");
        assertRoles(TeacherBackendController.class, "TEACHER", "ADMIN");
    }

    @Test
    void destructiveAdministrationEndpointsRequireAdminRole() throws Exception {
        assertMethodRoles(StudentBackendController.class, "createStudent", "ADMIN");
        assertMethodRoles(StudentBackendController.class, "modifyStudent", "ADMIN");
        assertMethodRoles(StudentBackendController.class, "deleteStudent", "ADMIN");
        assertMethodRoles(StudentBackendController.class, "importStudent", "ADMIN");
        assertMethodRoles(StudentBackendController.class, "exportStudent", "ADMIN");
        assertMethodRoles(TeacherBackendController.class, "pageList", "ADMIN");
        assertMethodRoles(TeacherBackendController.class, "createStudent", "ADMIN");
        assertMethodRoles(TeacherBackendController.class, "modifyStudent", "ADMIN");
        assertMethodRoles(TeacherBackendController.class, "deleteStudent", "ADMIN");
    }

    @Test
    void retiredUnboundedStudentListIsNotExposed() {
        assertThat(Arrays.stream(StudentBackendController.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("all");
    }

    private void assertRoles(Class<?> controller, String... expectedRoles) {
        SaCheckRole annotation = controller.getAnnotation(SaCheckRole.class);
        assertThat(annotation)
                .as("%s must declare a server-side role policy", controller.getSimpleName())
                .isNotNull();
        assertThat(annotation.mode()).isEqualTo(expectedRoles.length > 1 ? SaMode.OR : SaMode.AND);
        assertThat(Set.copyOf(Arrays.asList(annotation.value())))
                .containsExactlyInAnyOrder(expectedRoles);
    }

    private void assertMethodRoles(Class<?> controller, String methodName, String... expectedRoles) {
        SaCheckRole annotation = Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .map(method -> method.getAnnotation(SaCheckRole.class))
                .orElse(null);
        assertThat(annotation)
                .as("%s.%s must declare a server-side role policy", controller.getSimpleName(), methodName)
                .isNotNull();
        assertThat(Set.copyOf(Arrays.asList(annotation.value())))
                .containsExactlyInAnyOrder(expectedRoles);
    }
}
