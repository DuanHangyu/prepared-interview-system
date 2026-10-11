package system.assessment.defense.prepared;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import system.assessment.defense.prepared.processing.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedStudentResultControllerTest {
    @Test void onlyAuthenticatedStudentIdentitySelectsTheSubjectAndRoutesAreReadOnly(){
        var service=mock(PreparedStudentResultService.class);var controller=new PreparedStudentResultController(service);
        assertThat(PreparedStudentResultController.class.getAnnotation(SaCheckRole.class).value()).containsExactly("STUDENT");
        try(var identity=mockStatic(StpUtil.class)){identity.when(StpUtil::getLoginIdAsInt).thenReturn(72);controller.summary(8);controller.answer(8,"original-answer","original-version");controller.report(8,"original-version");}
        verify(service).summary(72,8);verify(service).answer(72,8,"original-answer","original-version");verify(service).report(72,8,"original-version");verifyNoMoreInteractions(service);
        var routes=Arrays.stream(PreparedStudentResultController.class.getDeclaredMethods()).filter(m->java.lang.reflect.Modifier.isPublic(m.getModifiers())&&!m.isSynthetic()).toList();
        assertThat(routes).hasSize(4).allMatch(m->m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class)!=null);
    }
}
