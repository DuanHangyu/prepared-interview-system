package system.assessment.defense.security;

import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import system.assessment.defense.application.dto.ChangePasswordDTO;
import system.assessment.defense.interfaces.rest.LoginController;
import system.assessment.defense.interfaces.rest.backend.TeacherBackendController;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordChangeControllerContractTest {

    @Test
    void commonEndpointValidatesTheTwoFieldCommand() throws Exception {
        Method method = LoginController.class.getMethod("changePassword", ChangePasswordDTO.class);

        assertThat(method.getParameterAnnotations()[0])
                .anyMatch(annotation -> annotation.annotationType().equals(Valid.class));
    }

    @Test
    void teacherControllerDoesNotExposeASecondPasswordMutationEndpoint() {
        assertThat(Arrays.stream(TeacherBackendController.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("modifyPassword");
    }
}
