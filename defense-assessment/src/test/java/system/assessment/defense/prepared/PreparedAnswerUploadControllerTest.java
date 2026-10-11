package system.assessment.defense.prepared;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedAnswerUploadControllerTest {
    @Test void windowQueryRemainsAuthenticatedReadOnlyWhenWorkflowIsClosed(){
        var service=mock(PreparedAnswerUploadService.class);var controller=new PreparedAnswerUploadController(service,false);
        try(var session=mockStatic(StpUtil.class)){session.when(StpUtil::getLoginIdAsInt).thenReturn(72);controller.window(9,"attempt","answer","client");}
        verify(service).window(72,9,"attempt","answer","client");
    }
    @Test void binaryRoutesRequireStudentRoleAndAuthenticSessionAndForwardOnlyWhitelistedOriginalClaims()throws Exception {
        var service=mock(PreparedAnswerUploadService.class);var controller=new PreparedAnswerUploadController(service,true);var request=new MockHttpServletRequest();request.setContent(new byte[]{1});
        assertThat(PreparedAnswerUploadController.class.getAnnotation(SaCheckRole.class).value()).containsExactly("STUDENT");
        assertThat(PreparedAnswerUploadController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/backend/prepared-interviews");
        try(var session=mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsInt).thenReturn(72);
            controller.upload(9,"attempt","answer","segment",0,"client",1,"audio/webm",1,"hash",request);
            controller.receipts(9,"attempt","answer");
        }
        verify(service).upload(eq(72),eq(9),eq("attempt"),eq("answer"),eq(new PreparedAnswerUpload.Command("client","segment",1,"audio/webm",0,1,"hash")),any());
        verify(service).receipts(72,9,"attempt","answer");
    }
    @Test void closedGateDoesNotReadRecordingBodyOrInvokeStorageButRecoveryQueriesRemainAvailable()throws Exception {
        var service=mock(PreparedAnswerUploadService.class);var controller=new PreparedAnswerUploadController(service,false);var request=mock(jakarta.servlet.http.HttpServletRequest.class);
        assertThatThrownBy(()->controller.upload(9,"attempt","answer","segment",0,"client",1,"audio/webm",1,"hash",request)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        verifyNoInteractions(request,service);
        try(var session=mockStatic(StpUtil.class)){session.when(StpUtil::getLoginIdAsInt).thenReturn(72);controller.receipts(9,"attempt","answer");}
        verify(service).receipts(72,9,"attempt","answer");
    }
}
