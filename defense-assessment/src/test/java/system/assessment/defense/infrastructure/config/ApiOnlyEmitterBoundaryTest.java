package system.assessment.defense.infrastructure.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual Boot MVC handlers and the configured DispatcherServlet; no application,
 * network listener, database, model, or student account is started by this fixture. */
class ApiOnlyEmitterBoundaryTest {
    private static final String GENERIC = "{\"code\":500,\"message\":\"系统异常\",\"data\":null}";
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class))
            .withUserConfiguration(ApiOnlyMvcConfiguration.class, FixtureConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"/fixture/sse", "/fixture/sse-entity", "/fixture/emitter",
            "/fixture/emitter-entity", "/fixture/exception", "/fixture/null-sse",
            "/fixture/null-entity"})
    void unsupportedEmitterChannelsNeverRenderFragmentsOrExposeTheirHeaders(String path) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var controller = context.getBean(FixtureController.class);
            var request = new MockHttpServletRequest(context.getServletContext(), "GET", path);
            request.setAsyncSupported(true);
            var response = new MockHttpServletResponse();
            var servlet = context.getBean(DispatcherServlet.class);
            assertThat(servlet).isInstanceOf(ApiOnlyMvcConfiguration.ApiOnlyDispatcherServlet.class);
            servlet.init(new MockServletConfig(context.getServletContext(), "api-only-fixture"));
            servlet.service(request, response);

            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getContentAsString()).isEqualTo(GENERIC);
            assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("X-Fixture-Private")).isNull();
            assertThat(response.getHeader("Location")).isNull();
            assertThat(request.isAsyncStarted()).isFalse();
            assertThat(controller.rendered).hasValue(0);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"/fixture/bytes", "/fixture/stream", "/fixture/direct"})
    void normalBinaryAndDirectResponsePathsRemainAvailable(String path) {
        runner.run(context -> {
            var response = dispatch(context.getBean(DispatcherServlet.class),
                    context.getServletContext(), path);
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsByteArray()).isEqualTo("fixture-audio".getBytes(StandardCharsets.UTF_8));
            assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            assertThat(context.getBean(FixtureController.class).rendered).hasValue(0);
        });
    }

    @Test void normalJsonAndDirectHttpRedirectRemainAvailable() {
        runner.run(context -> {
            var servlet = context.getBean(DispatcherServlet.class);
            var response = dispatch(servlet, context.getServletContext(), "/fixture/json");
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("{\"saved\":true}");
            var redirect = dispatch(servlet, context.getServletContext(), "/fixture/redirect");
            assertThat(redirect.getStatus()).isEqualTo(302);
            assertThat(redirect.getHeader("Location")).isEqualTo("/fixture/json");
        });
    }

    private static MockHttpServletResponse dispatch(DispatcherServlet servlet,
            jakarta.servlet.ServletContext context, String path) throws Exception {
        var request = new MockHttpServletRequest(context, "GET", path);
        request.setAsyncSupported(true);
        var response = new MockHttpServletResponse();
        servlet.init(new MockServletConfig(context, "api-only-fixture"));
        servlet.service(request, response);
        return response;
    }

    @Configuration(proxyBeanMethods = false)
    static class FixtureConfiguration implements WebMvcConfigurer {
        @Bean FixtureController fixtureController() { return new FixtureController(); }
        @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            // Deterministic fixture-only executor; no background workload or listener.
            configurer.setTaskExecutor(new TaskExecutorAdapter(new SyncTaskExecutor()));
        }
    }

    @RestController
    static class FixtureController {
        final AtomicInteger rendered = new AtomicInteger();
        private ModelAndView fragment() {
            View view = (model, request, response) -> {
                rendered.incrementAndGet();
                response.getWriter().write("fixture-private-fragment");
            };
            return new ModelAndView(view, "fixture", "fixture-private-model");
        }
        private SseEmitter sse() throws IOException {
            var emitter = new SseEmitter();
            emitter.send(SseEmitter.event().data(fragment()));
            emitter.complete();
            return emitter;
        }
        private ResponseBodyEmitter emitter() throws IOException {
            var emitter = new ResponseBodyEmitter();
            emitter.send(fragment());
            emitter.complete();
            return emitter;
        }
        @GetMapping("/fixture/sse") SseEmitter directSse() throws IOException { return sse(); }
        @GetMapping("/fixture/sse-entity") ResponseEntity<SseEmitter> sseEntity() throws IOException {
            return ResponseEntity.accepted().header("X-Fixture-Private", "fixture-private")
                    .header("Location", "/fixture/private").body(sse());
        }
        @GetMapping("/fixture/emitter") ResponseBodyEmitter directEmitter() throws IOException { return emitter(); }
        @GetMapping("/fixture/emitter-entity") ResponseEntity<ResponseBodyEmitter> emitterEntity() throws IOException {
            return ResponseEntity.accepted().header("X-Fixture-Private", "fixture-private").body(emitter());
        }
        @GetMapping("/fixture/null-sse") SseEmitter nullSse() { return null; }
        @GetMapping("/fixture/null-entity") ResponseEntity<SseEmitter> nullEntity() {
            return ResponseEntity.accepted().header("X-Fixture-Private", "fixture-private").body(null);
        }
        @GetMapping("/fixture/exception") void exception() { throw new FixtureException(); }
        @ExceptionHandler(FixtureException.class) SseEmitter exceptionSse() throws IOException { return sse(); }
        @GetMapping("/fixture/json") Map<String, Boolean> json() { return Map.of("saved", true); }
        @GetMapping("/fixture/bytes") ResponseEntity<byte[]> bytes() {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body("fixture-audio".getBytes(StandardCharsets.UTF_8));
        }
        @GetMapping("/fixture/stream") ResponseEntity<StreamingResponseBody> stream() {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(output -> output.write("fixture-audio".getBytes(StandardCharsets.UTF_8)));
        }
        @GetMapping("/fixture/direct") void direct(HttpServletResponse response) throws IOException {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.getOutputStream().write("fixture-audio".getBytes(StandardCharsets.UTF_8));
        }
        @GetMapping("/fixture/redirect") void redirect(HttpServletResponse response) throws IOException {
            response.sendRedirect("/fixture/json");
        }
    }

    static final class FixtureException extends RuntimeException { }
}
