package system.assessment.defense.prepared;

import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.prepared.attempt.PreparedStudentEligibilityController;
import static org.assertj.core.api.Assertions.*;

class PreparedStudentEligibilityControllerTest {
    @Test void studentOnlyReadEndpointDoesNotAcceptStudentIdOrExposeWriteCommands() throws Exception {
        var type=PreparedStudentEligibilityController.class;
        assertThat(type.getAnnotation(SaCheckRole.class).value()).containsExactly("STUDENT");
        assertThat(type.getAnnotation(RequestMapping.class).value()).containsExactly("/backend/prepared-interviews");
        var method=type.getDeclaredMethod("eligibility",int.class);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/{assessmentId}/eligibility");
        assertThat(method.getParameters()[0].getAnnotation(Min.class).value()).isEqualTo(1);
        var context=type.getDeclaredMethod("preflightContext",int.class);
        assertThat(context.getAnnotation(GetMapping.class).value()).containsExactly("/{assessmentId}/preflight-context");
        assertThat(context.getParameters()[0].getAnnotation(Min.class).value()).isEqualTo(1);
        assertThat(java.util.Arrays.stream(type.getDeclaredMethods()).filter(m->!m.isSynthetic()).toList()).hasSize(2);
    }
}
