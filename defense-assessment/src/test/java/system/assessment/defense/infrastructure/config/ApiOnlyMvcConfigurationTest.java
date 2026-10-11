package system.assessment.defense.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ApiOnlyMvcConfigurationTest {
    private static final String GENERIC = "{\"code\":500,\"message\":\"系统异常\",\"data\":null}";

    @Test void doesNotResolveOrRenderAnImplicitOrNamedView() throws Exception {
        var response = new MockHttpServletResponse();
        var mv = new ModelAndView("http://127.0.0.1:1/private-template", "answer", "private-answer");
        render(mv, response);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).isEqualTo(GENERIC);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test void explicitViewsAreNeverInvokedEither() throws Exception {
        var invoked = new AtomicInteger();
        View view = (model, request, response) -> invoked.incrementAndGet();
        var response = new MockHttpServletResponse();
        render(new ModelAndView(view), response);
        assertThat(invoked).hasValue(0);
        assertThat(response.getContentAsString()).isEqualTo(GENERIC);
    }

    @Test void clearsUncommittedWriterContentAndStaleContentLengthWithoutLeakingTheModel() throws Exception {
        var response = new MockHttpServletResponse();
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Length", "999");
        response.setHeader("Location", "https://invalid.example/private");
        response.setHeader("Set-Cookie", "fixture-cookie=preserved; HttpOnly");
        response.getWriter().write("private-prefix");
        render(new ModelAndView("secret"), response);
        assertThat(response.getContentAsString()).isEqualTo(GENERIC);
        assertThat(response.getHeader("Content-Length")).isNull();
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getHeader("Set-Cookie")).isEqualTo("fixture-cookie=preserved; HttpOnly");
    }

    @Test void clearsUncommittedStreamContentAndReturnsOnlyTheGenericJson() throws Exception {
        var response = new MockHttpServletResponse();
        response.getOutputStream().write("private-prefix".getBytes(StandardCharsets.UTF_8));
        render(new ModelAndView("secret"), response);
        assertThat(response.getContentAsString()).isEqualTo(GENERIC);
    }

    @Test void neverResolvesViewsWhenTheResponseWasAlreadyCommitted() throws Exception {
        var response = new MockHttpServletResponse();
        response.getWriter().write("already-committed");
        response.flushBuffer();
        render(new ModelAndView("secret"), response);
        assertThat(response.getContentAsString()).isEqualTo("already-committed");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test void retainsExistingErrorStatusWithoutRenderingTheHtmlErrorView() throws Exception {
        var response = new MockHttpServletResponse();
        response.setStatus(404);
        render(new ModelAndView("error"), response);
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":404,\"message\":\"系统异常\",\"data\":null}");
    }

    @Test void doesNotCarryAnInvalidStatusIntoTheGenericFailureResponse() throws Exception {
        var response = new MockHttpServletResponse();
        response.setStatus(600);
        render(new ModelAndView("error"), response);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).isEqualTo(GENERIC);
    }

    @Test void keepsBootDispatcherOptionsWithoutAllowingSensitiveRequestDetailLogging() {
        var props = new WebMvcProperties();
        props.setDispatchOptionsRequest(true);
        props.setDispatchTraceRequest(true);
        props.setPublishRequestHandledEvents(false);
        props.setLogRequestDetails(true);
        var servlet = new ApiOnlyMvcConfiguration().dispatcherServlet(props);
        assertThat(servlet).isInstanceOf(ApiOnlyMvcConfiguration.ApiOnlyDispatcherServlet.class);
        assertThat(ReflectionTestUtils.getField(servlet, "dispatchOptionsRequest")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(servlet, "dispatchTraceRequest")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(servlet, "publishEvents")).isEqualTo(false);
        assertThat(servlet.isEnableLoggingRequestDetails()).isFalse();
    }

    @Test void registersTheGuardOnlyInServletContexts() {
        new ApplicationContextRunner().withUserConfiguration(ApiOnlyMvcConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean("dispatcherServlet"));
        new WebApplicationContextRunner().withUserConfiguration(ApiOnlyMvcConfiguration.class)
                .run(context -> assertThat(context.getBean("dispatcherServlet"))
                        .isInstanceOf(ApiOnlyMvcConfiguration.ApiOnlyDispatcherServlet.class));
    }

    private static void render(ModelAndView mv, MockHttpServletResponse response) throws Exception {
        var servlet = new ApiOnlyMvcConfiguration().dispatcherServlet(new WebMvcProperties());
        ReflectionTestUtils.invokeMethod(servlet, "render", mv, new MockHttpServletRequest(), response);
    }
}
