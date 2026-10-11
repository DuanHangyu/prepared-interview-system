package system.assessment.defense.infrastructure.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual loopback Tomcat + Boot MVC and production guard, with clearly synthetic
 * handlers/resolver. No database, interview, model, cloud, or capacity acceptance. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiOnlyMvcConfigurationLiveHttpTest {
    private ServletWebServerApplicationContext context;
    private HttpClient http;
    private String base;
    private final AtomicInteger resolved = new AtomicInteger();
    private final AtomicInteger rendered = new AtomicInteger();

    @BeforeAll void start() {
        context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(RuntimeConfiguration.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.address=127.0.0.1", "server.port=0", "spring.main.banner-mode=off",
                        "spring.mvc.log-request-details=false", "logging.level.root=OFF")
                .initializers(c -> {
                    c.getBeanFactory().registerSingleton("resolveCounter", resolved);
                    c.getBeanFactory().registerSingleton("renderCounter", rendered);
                }).run("--server.address=127.0.0.1", "--server.port=0");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        base = "http://127.0.0.1:" + context.getWebServer().getPort();
        // Builder default properties can be overridden by application.yml or
        // inherited environment. Command-line properties must win for this owned
        // ephemeral listener; never bind or stop another application's port.
        assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("0");
        assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(context.getBean("dispatcherServlet"))
                .isInstanceOf(ApiOnlyMvcConfiguration.ApiOnlyDispatcherServlet.class);
        assertThat(context.getServletContext().getServletRegistrations()).containsKey("dispatcherServlet");
    }

    @AfterAll void stop() {
        try { if (http != null) http.close(); }
        finally { if (context != null) context.close(); }
    }

    @ParameterizedTest @ValueSource(strings={"/unsafe/implicit/template", "/unsafe-named", "/unsafe-direct", "/unsafe-exception", "/unsafe-stream", "/unsafe-writer"})
    void rejectsActualImplicitNamedExplicitAndExceptionViewsBeforeResolution(String route) throws Exception {
        var response = get(route);
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo("{\"code\":500,\"message\":\"系统异常\",\"data\":null}");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-store");
        assertThat(resolved).hasValue(0);
        assertThat(rendered).hasValue(0);
    }

    @Test void realJsonAndDirectRedirectResponsesDoNotEnterViewRendering() throws Exception {
        var json = get("/api/ok");
        assertThat(json.statusCode()).isEqualTo(200);
        assertThat(json.body()).isEqualTo("{\"status\":\"ok\"}");
        var redirect = get("/api/redirect");
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location").orElseThrow()).isEqualTo("/api/ok");
        assertThat(resolved).hasValue(0);
    }

    @Test void realBinaryDownloadsAndDirectServletWritesRemainUnchanged() throws Exception {
        var file = get("/api/file");
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo("owned-binary-fixture");
        assertThat(file.headers().firstValue("Content-Disposition").orElseThrow())
                .isEqualTo("attachment; filename=fixture.txt");
        var direct = get("/api/direct");
        assertThat(direct.statusCode()).isEqualTo(200);
        assertThat(direct.body()).isEqualTo("direct-response");
        assertThat(resolved).hasValue(0);
    }

    private HttpResponse<String> get(String route) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base+route)).timeout(Duration.ofSeconds(5))
                        .header("Accept", "text/html,application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods=false)
    @Import({ApiOnlyMvcConfiguration.class, RuntimeController.class})
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class})
    static class RuntimeConfiguration {
        @Bean ViewResolver unsafeResolver(@org.springframework.beans.factory.annotation.Qualifier("resolveCounter") AtomicInteger resolveCounter) {
            return (name, locale) -> { resolveCounter.incrementAndGet(); throw new AssertionError("VIEW_RESOLUTION_MUST_NOT_RUN"); };
        }
    }

    @Controller
    static class RuntimeController {
        private final AtomicInteger rendered;
        RuntimeController(@org.springframework.beans.factory.annotation.Qualifier("renderCounter") AtomicInteger rendered) {
            this.rendered = rendered;
        }
        @RequestMapping("/unsafe/**") void implicit(Model model) { model.addAttribute("privateAnswer", "private-answer-fixture"); }
        @GetMapping("/unsafe-named") String named() { return "http://127.0.0.1:1/private-template"; }
        @GetMapping("/unsafe-direct") ModelAndView explicit() {
            return new ModelAndView((View) (model, request, response) -> rendered.incrementAndGet());
        }
        @GetMapping("/unsafe-exception") void failure() { throw new IllegalArgumentException("private-exception-fixture"); }
        @ExceptionHandler(IllegalArgumentException.class) ModelAndView error() { return new ModelAndView("private-error-template"); }
        @GetMapping("/unsafe-stream") ModelAndView stream(HttpServletResponse response) throws Exception {
            response.getOutputStream().write("private-prefix-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new ModelAndView("private-template");
        }
        @GetMapping("/unsafe-writer") ModelAndView writer(HttpServletResponse response) throws Exception {
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("private-prefix-fixture");
            return new ModelAndView("private-template");
        }
        @GetMapping("/api/ok") @ResponseBody Map<String,String> ok() { return Map.of("status", "ok"); }
        @GetMapping("/api/redirect") ResponseEntity<Void> redirect() {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/api/ok")).build();
        }
        @GetMapping("/api/file") ResponseEntity<byte[]> file() {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("Content-Disposition", "attachment; filename=fixture.txt")
                    .body("owned-binary-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        @GetMapping("/api/direct") void direct(HttpServletResponse response) throws Exception {
            response.getWriter().write("direct-response");
        }
    }
}
