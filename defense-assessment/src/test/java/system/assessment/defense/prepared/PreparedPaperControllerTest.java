package system.assessment.defense.prepared;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.prepared.paper.PreparedPaperController;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PreparedPaperControllerTest {
    @Test void paperManagementIsNotAnUnprotectedPublicEndpoint() {
        SaCheckRole role = PreparedPaperController.class.getAnnotation(SaCheckRole.class);
        assertNotNull(role);
        assertEquals(SaMode.OR, role.mode());
        assertEquals(java.util.Set.of("TEACHER", "ADMIN"), java.util.Set.of(role.value()));
        assertArrayEquals(new String[]{"/backend/prepared-papers"},
                PreparedPaperController.class.getAnnotation(RequestMapping.class).value());
    }

    @Test void manualDraftAndApprovalHaveSeparateCommands() {
        var methods = Arrays.asList(PreparedPaperController.class.getDeclaredMethods());
        assertTrue(methods.stream().anyMatch(m -> m.isAnnotationPresent(GetMapping.class)));
        assertTrue(methods.stream().anyMatch(m -> m.isAnnotationPresent(PutMapping.class)));
        assertTrue(methods.stream().anyMatch(m -> m.isAnnotationPresent(PostMapping.class)
                && Arrays.asList(m.getAnnotation(PostMapping.class).value()).contains("/{assessmentId}/approve")));
    }
}
