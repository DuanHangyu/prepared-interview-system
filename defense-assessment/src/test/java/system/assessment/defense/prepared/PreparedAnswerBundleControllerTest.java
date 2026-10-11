package system.assessment.defense.prepared;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedAnswerBundleControllerTest {
    @Test void batchRoutesUseOnlyActualStudentSessionAndWhitelistedCommand()throws Exception {
        var service=mock(PreparedAnswerBundleService.class);var controller=new PreparedAnswerBundleController(service,true);var request=new MockHttpServletRequest();request.setContent(new byte[]{1});
        assertThat(PreparedAnswerBundleController.class.getAnnotation(SaCheckRole.class).value()).containsExactly("STUDENT");
        assertThat(PreparedAnswerBundleController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/backend/prepared-interviews");
        var command=mock(PreparedAnswerBundle.Command.class);
        try(var session=mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsInt).thenReturn(72);controller.prepare(9,"attempt","answer",command);
            controller.upload(9,"attempt","answer","id",request);controller.status(9,"attempt","answer","id");
        }
        verify(service).prepare(72,9,"attempt","answer",command);verify(service).upload(eq(72),eq(9),eq("attempt"),eq("answer"),eq("id"),any());verify(service).status(72,9,"attempt","answer","id");
    }
    @Test void closedGateNeverReadsRecordingBodyButReadOnlyRecoveryRemainsAvailable()throws Exception {
        var service=mock(PreparedAnswerBundleService.class);var controller=new PreparedAnswerBundleController(service,false);var request=mock(jakarta.servlet.http.HttpServletRequest.class);
        assertThatThrownBy(()->controller.prepare(9,"attempt","answer",null)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        assertThatThrownBy(()->controller.upload(9,"attempt","answer","id",request)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);verifyNoInteractions(request,service);
        try(var session=mockStatic(StpUtil.class)){session.when(StpUtil::getLoginIdAsInt).thenReturn(72);controller.status(9,"attempt","answer","id");}verify(service).status(72,9,"attempt","answer","id");
    }
}
