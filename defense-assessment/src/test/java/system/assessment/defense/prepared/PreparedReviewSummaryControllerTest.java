package system.assessment.defense.prepared;

import cn.dev33.satoken.annotation.*;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.processing.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedReviewSummaryControllerTest {
    @Test void readOnlyManagerRouteUsesSessionActorNotClientIdentity()throws Exception{
        var service=mock(PreparedReviewSummaryService.class);var controller=new PreparedReviewSummaryController(service);
        var roles=PreparedReviewSummaryController.class.getAnnotation(SaCheckRole.class);assertThat(roles.value()).containsExactly("TEACHER","ADMIN");assertThat(roles.mode()).isEqualTo(SaMode.OR);
        try(var identity=mockStatic(StpUtil.class)){identity.when(StpUtil::getLoginIdAsInt).thenReturn(7);controller.summary(21,"original-attempt");}
        verify(service).summary(7,21,"original-attempt");verifyNoMoreInteractions(service);
        var route=PreparedReviewSummaryController.class.getMethod("summary",int.class,String.class);assertThat(route.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class)).isNotNull();
    }
}
