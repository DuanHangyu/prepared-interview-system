package system.assessment.defense.security;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import system.assessment.defense.application.service.SecurityRateLimitService;
import system.assessment.defense.infrastructure.config.SecurityRateLimitInterceptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityRateLimitInterceptorBoundaryTest {
    private final SecurityRateLimitService limits = mock(SecurityRateLimitService.class);
    private final SecurityRateLimitInterceptor interceptor = new SecurityRateLimitInterceptor(limits);

    @Test void reviewGetHeadAndPostUseReadReadAndWriteIdentityLimits() {
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsInt).thenReturn(72);
            for (String method : new String[]{"GET","HEAD","POST"}) call(method,"/backend/prepared-reviews/9/students/72");
        }
        verify(limits,times(2)).checkPreparedReview(72,false);
        verify(limits).checkPreparedReview(72,true); verifyNoMoreInteractions(limits);
    }

    @Test void originalQuestionAndUploadHeadRequestsCannotConsumeWriteBudgets() {
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsInt).thenReturn(72);
            call("HEAD","/backend/prepared-interviews/9/attempts/current");
            call("HEAD","/backend/prepared-interviews/9/attempts/00000000-0000-0000-0000-000000000001/answers/00000000-0000-0000-0000-000000000002/chunks");
        }
        verify(limits).checkPreparedInterview(72,false); verify(limits).checkPreparedUpload(72,false);
        verifyNoMoreInteractions(limits);
    }

    @Test void filePostHasItsOwnIpAndOptionalIdentityQuotaAndGetIsNotAnUpload() {
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::isLogin).thenReturn(true);
            identity.when(StpUtil::getLoginIdAsInt).thenReturn(72);
            call("POST","/file/upload");
            identity.when(StpUtil::isLogin).thenReturn(false);
            call("POST","/file/upload");
            call("GET","/file/upload");
        }
        verify(limits).checkFileUpload("campus",72); verify(limits).checkFileUpload("campus",null);
        verifyNoMoreInteractions(limits);
    }

    @Test void legacyAiCostGuardsRemainUnchangedAndAreNotReplacedByLoginReservation() {
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::isLogin).thenReturn(true);
            identity.when(StpUtil::getLoginIdAsInt).thenReturn(72);
            call("POST","/gemini/pickUpFileContent"); call("POST","/gemini/pickUpVoice");
            call("POST","/gemini/generate-voice"); call("POST","/gemini/other");
            identity.when(StpUtil::isLogin).thenReturn(false); call("POST","/gemini/other");
        }
        verify(limits).checkAiHttp("campus",72,10); verify(limits,times(2)).checkAiHttp("campus",72,5);
        verify(limits).checkAiHttp("campus",72,3); verify(limits).checkAiHttp("campus",null,5);
        verifyNoMoreInteractions(limits);
    }

    @Test void nonPostLoginAndUnrelatedRoutesDoNotAllocateLoginReservations() throws Exception {
        call("GET","/login"); call("POST","/logout");
        var request = new MockHttpServletRequest("POST","/other");
        request.addHeader("X-Login-Success","true");
        SecurityRateLimitInterceptor.authenticationSucceeded(request);
        interceptor.afterCompletion(request,new MockHttpServletResponse(),null,null);
        verifyNoInteractions(limits);
    }

    private void call(String method, String path) {
        var request = new MockHttpServletRequest(method,path); request.setRemoteAddr("campus");
        assertThat(interceptor.preHandle(request,new MockHttpServletResponse(),null)).isTrue();
    }
}
