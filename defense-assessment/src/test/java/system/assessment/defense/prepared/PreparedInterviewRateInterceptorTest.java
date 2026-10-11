package system.assessment.defense.prepared;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import system.assessment.defense.application.service.SecurityRateLimitService;
import system.assessment.defense.infrastructure.config.SecurityRateLimitInterceptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedInterviewRateInterceptorTest {
    @Test void studentResultReadsConsumeCurrentUserQuotaForEncodedOrContextMountedPathsWithoutPaidCounters() {
        var limits=mock(SecurityRateLimitService.class);var interceptor=new SecurityRateLimitInterceptor(limits);
        try(var identity=mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsInt).thenReturn(72);
            for(String path:new String[]{"/backend/prepared-results/9","/backend/%70repared-results/9/answers/x","/backend/prepared-results;v=1/9","/campus/backend/%70repared-results/9"}) {
                var request=new MockHttpServletRequest("GET",path);if(path.startsWith("/campus"))request.setContextPath("/campus");
                assertThat(interceptor.preHandle(request,new MockHttpServletResponse(),null)).isTrue();
            }
        }
        verify(limits,times(4)).checkPreparedInterview(72,false);verifyNoMoreInteractions(limits);
    }
    @Test void chunkUploadsAndReceiptQueriesUseSeparateQuotaFromQuestionAndEndCommands() {
        var limits=mock(SecurityRateLimitService.class);var interceptor=new SecurityRateLimitInterceptor(limits);
        String root="/backend/prepared-interviews/9/attempts/00000000-0000-0000-0000-000000000001/answers/00000000-0000-0000-0000-000000000002/chunks";
        try(var identity=mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsInt).thenReturn(72);
            interceptor.preHandle(new MockHttpServletRequest("GET",root),new MockHttpServletResponse(),null);
            interceptor.preHandle(new MockHttpServletRequest("PUT",root+"/00000000-0000-0000-0000-000000000003/0"),new MockHttpServletResponse(),null);
            interceptor.preHandle(new MockHttpServletRequest("PUT",(root+"/00000000-0000-0000-0000-000000000003/0").replace("prepared-interviews","%70repared-interviews")),new MockHttpServletResponse(),null);
        }
        verify(limits).checkPreparedUpload(72,false);verify(limits,times(2)).checkPreparedUpload(72,true);verifyNoMoreInteractions(limits);
    }
    @Test void encodedMatrixOrContextMountedRoutesConsumeTheSameQuota() {
        var limits=mock(SecurityRateLimitService.class);var interceptor=new SecurityRateLimitInterceptor(limits);
        try(var identity=mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsInt).thenReturn(72);
            for(String path:new String[]{"/backend/%70repared-interviews/9/attempts","/backend/prepared-interviews;v=1/9/attempts","/campus/backend/%70repared-interviews/9/attempts"}) {
                var request=new MockHttpServletRequest("POST",path);if(path.startsWith("/campus"))request.setContextPath("/campus");
                assertThat(interceptor.preHandle(request,new MockHttpServletResponse(),null)).isTrue();
            }
        }
        verify(limits,times(3)).checkPreparedInterview(72,true);verifyNoMoreInteractions(limits);
    }
    @Test void preparedCommandsConsumeSessionUserQuotaBeforeControllerWithoutAiOrIpCounters() {
        var limits=mock(SecurityRateLimitService.class);var interceptor=new SecurityRateLimitInterceptor(limits);
        try(var identity=mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsInt).thenReturn(72);
            assertThat(interceptor.preHandle(new MockHttpServletRequest("GET","/backend/prepared-interviews/9/attempts/current"),new MockHttpServletResponse(),null)).isTrue();
            assertThat(interceptor.preHandle(new MockHttpServletRequest("POST","/backend/prepared-interviews/9/attempts"),new MockHttpServletResponse(),null)).isTrue();
        }
        verify(limits).checkPreparedInterview(72,false);verify(limits).checkPreparedInterview(72,true);verifyNoMoreInteractions(limits);
    }
}
