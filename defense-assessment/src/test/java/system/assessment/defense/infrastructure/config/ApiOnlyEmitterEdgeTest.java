package system.assessment.defense.infrastructure.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Controlled framework boundaries only; no network, native database, or cloud. */
class ApiOnlyEmitterEdgeTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class))
            .withUserConfiguration(ApiOnlyMvcConfiguration.class, FixtureConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"/edge/named", "/edge/flux", "/edge/mono", "/edge/flux-entity", "/edge/advice"})
    void rejectsNamedAndReactiveFragmentsAndGlobalExceptionEmittersBeforeAnyResolution(String path) {
        runner.run(context -> {
            var response = dispatch(context.getBean(DispatcherServlet.class), context.getServletContext(), path);
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getContentAsString()).isEqualTo(generic(500));
            assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
            var fixture = context.getBean(FixtureController.class);
            assertThat(fixture.resolved).hasValue(0);
            assertThat(fixture.rendered).hasValue(0);
            assertThat(fixture.subscribed).hasValue(0);
        });
    }

    @Test void preservesExistingErrorStatusAndSecurityHeadersButNotUncommittedSensitiveWriterData() {
        runner.run(context -> {
            var response = dispatch(context.getBean(DispatcherServlet.class), context.getServletContext(), "/edge/writer");
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentAsString()).isEqualTo(generic(503));
            assertThat(response.getHeader("Content-Length")).isNull();
            assertThat(response.getHeader("Location")).isNull();
            assertThat(response.getHeader("Set-Cookie")).isEqualTo("fixture=kept; HttpOnly");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        });
    }

    @Test void clearsPreviouslySelectedOutputStreamWithoutReturningAnyQueuedEvent() {
        runner.run(context -> {
            var response = dispatch(context.getBean(DispatcherServlet.class), context.getServletContext(), "/edge/stream");
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getContentAsString()).isEqualTo(generic(500));
            assertThat(context.getBean(FixtureController.class).rendered).hasValue(0);
        });
    }

    @Test void neverRendersOrResolvesFragmentsAfterTheResponseWasAlreadyCommitted() {
        runner.run(context -> {
            var response = dispatch(context.getBean(DispatcherServlet.class), context.getServletContext(), "/edge/committed");
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("fixture-already-committed");
            assertThat(context.getBean(FixtureController.class).rendered).hasValue(0);
            assertThat(context.getBean(FixtureController.class).resolved).hasValue(0);
        });
    }

    @Test void rejectedUnfinishedEmitterCannotQueueMoreEventsOrStartAsyncWork() {
        runner.run(context -> {
            var response = dispatch(context.getBean(DispatcherServlet.class), context.getServletContext(), "/edge/unfinished");
            assertThat(response.getStatus()).isEqualTo(500);
            var emitter = context.getBean(FixtureController.class).unfinished;
            assertThat(emitter).isNotNull();
            assertThatThrownBy(() -> emitter.send("fixture-late-event"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(response.getContentAsString()).isEqualTo(generic(500));
        });
    }

    @Test void normalJsonExceptionAndFiniteNoContentEntityAreNotRejectedAsEmitters() {
        runner.run(context -> {
            var servlet = context.getBean(DispatcherServlet.class);
            var error = dispatch(servlet, context.getServletContext(), "/edge/json-error");
            assertThat(error.getStatus()).isEqualTo(409);
            assertThat(error.getContentAsString()).isEqualTo("{\"code\":409}");
            var empty = dispatch(servlet, context.getServletContext(), "/edge/empty");
            assertThat(empty.getStatus()).isEqualTo(204);
            assertThat(empty.getContentAsString()).isEmpty();
            assertThat(empty.getHeader("X-Fixture-Public")).isEqualTo("kept");
        });
    }

    @Test void noMvcGuardRegistrationAppearsInNonServletApplications() {
        new ApplicationContextRunner().withUserConfiguration(ApiOnlyMvcConfiguration.class).run(context -> {
            assertThat(context).doesNotHaveBean("dispatcherServlet");
            assertThat(context).doesNotHaveBean("apiOnlyMvcRegistrations");
        });
    }

    private static String generic(int status) {
        return "{\"code\":" + status + ",\"message\":\"系统异常\",\"data\":null}";
    }

    private static MockHttpServletResponse dispatch(DispatcherServlet servlet,
            jakarta.servlet.ServletContext context, String path) throws Exception {
        var request = new MockHttpServletRequest(context, "GET", path);
        // Rejection must occur before an unsupported stream requires servlet async.
        request.setAsyncSupported(false);
        var response = new MockHttpServletResponse();
        servlet.init(new MockServletConfig(context, "api-only-edge-fixture"));
        servlet.service(request, response);
        assertThat(request.isAsyncStarted()).isFalse();
        return response;
    }

    @Configuration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean FixtureController fixtureController() { return new FixtureController(); }
        @Bean FixtureAdvice fixtureAdvice(FixtureController controller) { return new FixtureAdvice(controller); }
        @Bean ViewResolver fixtureViewResolver(FixtureController controller) {
            return (name, locale) -> {
                controller.resolved.incrementAndGet();
                return controller.view();
            };
        }
    }

    @RestControllerAdvice
    static class FixtureAdvice {
        private final FixtureController controller;
        FixtureAdvice(FixtureController controller) { this.controller = controller; }
        @ExceptionHandler(AdviceException.class) SseEmitter emitterError() throws IOException { return controller.named(); }
        @ExceptionHandler(JsonException.class) ResponseEntity<Map<String, Integer>> jsonError() {
            return ResponseEntity.status(409).body(Map.of("code", 409));
        }
    }

    @RestController
    static class FixtureController {
        final AtomicInteger resolved = new AtomicInteger();
        final AtomicInteger rendered = new AtomicInteger();
        final AtomicInteger subscribed = new AtomicInteger();
        SseEmitter unfinished;
        View view() {
            return (model, request, response) -> {
                rendered.incrementAndGet();
                response.getWriter().write("fixture-private-fragment");
            };
        }
        @GetMapping("/edge/named") SseEmitter named() throws IOException {
            var emitter = new SseEmitter();
            emitter.send(SseEmitter.event().data(new ModelAndView("fixture-private-template")));
            emitter.complete();
            return emitter;
        }
        @GetMapping(value = "/edge/flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Flux<ModelAndView> flux() {
            return Flux.just(new ModelAndView(view())).doOnSubscribe(subscription -> subscribed.incrementAndGet());
        }
        @GetMapping(value = "/edge/mono", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Mono<ModelAndView> mono() {
            return Mono.just(new ModelAndView(view())).doOnSubscribe(subscription -> subscribed.incrementAndGet());
        }
        @GetMapping(value = "/edge/flux-entity", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        ResponseEntity<Flux<ModelAndView>> fluxEntity() { return ResponseEntity.ok(flux()); }
        @GetMapping("/edge/advice") void advice() { throw new AdviceException(); }
        @GetMapping("/edge/writer") SseEmitter writer(HttpServletResponse response) throws IOException {
            response.setCharacterEncoding("UTF-8");
            response.setStatus(503);
            response.setHeader("Content-Length", "999");
            response.setHeader("Location", "/fixture-private");
            response.setHeader("Set-Cookie", "fixture=kept; HttpOnly");
            response.getWriter().write("fixture-private-prefix");
            return named();
        }
        @GetMapping("/edge/stream") SseEmitter stream(HttpServletResponse response) throws IOException {
            response.getOutputStream().write("fixture-private-prefix".getBytes(StandardCharsets.UTF_8));
            return named();
        }
        @GetMapping("/edge/committed") SseEmitter committed(HttpServletResponse response) throws IOException {
            response.getWriter().write("fixture-already-committed");
            response.flushBuffer();
            return named();
        }
        @GetMapping("/edge/unfinished") SseEmitter unfinished() { return unfinished = new SseEmitter(); }
        @GetMapping("/edge/json-error") void jsonError() { throw new JsonException(); }
        @GetMapping("/edge/empty") ResponseEntity<Void> empty() {
            return ResponseEntity.noContent().header("X-Fixture-Public", "kept").build();
        }
    }

    static final class AdviceException extends RuntimeException { }
    static final class JsonException extends RuntimeException { }
}
