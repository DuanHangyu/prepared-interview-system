package system.assessment.defense.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcProperties;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The Vue client renders pages. This API server must never resolve or render
 * MVC views, including implicit request-derived names and exception views.
 * Blocking before resolution also avoids XSLT template loading during resolution.
 * This is a narrow runtime boundary, not a patched Spring dependency. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(WebMvcProperties.class)
public class ApiOnlyMvcConfiguration {
    @Bean(name=DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME)
    public DispatcherServlet dispatcherServlet(WebMvcProperties properties) {
        var servlet = new ApiOnlyDispatcherServlet();
        servlet.setDispatchOptionsRequest(properties.isDispatchOptionsRequest());
        servlet.setDispatchTraceRequest(properties.isDispatchTraceRequest());
        servlet.setPublishEvents(properties.isPublishRequestHandledEvents());
        // Never opt in to request parameters/header logging on this sensitive API.
        servlet.setEnableLoggingRequestDetails(false);
        return servlet;
    }

    @Bean
    public WebMvcRegistrations apiOnlyMvcRegistrations() {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerAdapter getRequestMappingHandlerAdapter() {
                return new RequestMappingHandlerAdapter() {
                    @Override public void afterPropertiesSet() {
                        super.afterPropertiesSet();
                        setReturnValueHandlers(withoutEmitterRendering(getReturnValueHandlers()));
                    }
                };
            }

            @Override
            public ExceptionHandlerExceptionResolver getExceptionHandlerExceptionResolver() {
                return new ExceptionHandlerExceptionResolver() {
                    @Override public void afterPropertiesSet() {
                        super.afterPropertiesSet();
                        var handlers = new ArrayList<>(withoutEmitterRendering(
                                Objects.requireNonNull(getReturnValueHandlers()).getHandlers()));
                        // Exception resolvers do not have the native emitter
                        // handler in this framework version: reject before the
                        // JSON/entity processors can serialize the emitter itself.
                        handlers.add(0, new RejectEmitterHandler(
                                new ResponseBodyEmitterReturnValueHandler(getMessageConverters())));
                        setReturnValueHandlers(handlers);
                    }
                };
            }
        };
    }

    private static List<HandlerMethodReturnValueHandler> withoutEmitterRendering(
            List<HandlerMethodReturnValueHandler> handlers) {
        // Replace in place: appended custom handlers run after the native emitter
        // handler. That handler can render fragments without DispatcherServlet.render.
        // Keep Boot's converters, advice, async executor and all other handler order.
        return Objects.requireNonNull(handlers).stream()
                .map(handler -> handler instanceof ResponseBodyEmitterReturnValueHandler
                        ? new RejectEmitterHandler(handler) : handler)
                .toList();
    }

    private record RejectEmitterHandler(HandlerMethodReturnValueHandler delegate)
            implements HandlerMethodReturnValueHandler {
        @Override public boolean supportsReturnType(MethodParameter returnType) {
            return delegate.supportsReturnType(returnType);
        }

        @Override public void handleReturnValue(Object value, MethodParameter returnType,
                ModelAndViewContainer container, NativeWebRequest request) throws IOException {
            container.setRequestHandled(true);
            Object body = value instanceof ResponseEntity<?> entity ? entity.getBody() : value;
            if (body instanceof ResponseBodyEmitter emitter) emitter.complete();
            // Do not initialize a native emitter, render a fragment, copy its
            // ResponseEntity headers, or start an SSE/reactive stream. This API
            // uses finite HTTP commands and controlled raw file streaming instead.
            writeGenericFailure(Objects.requireNonNull(
                    request.getNativeResponse(HttpServletResponse.class)));
        }
    }

    static final class ApiOnlyDispatcherServlet extends DispatcherServlet {
        @Override
        protected void render(ModelAndView ignored, HttpServletRequest request,
                              HttpServletResponse response) throws Exception {
            // In particular, do not call super.render or resolveViewName. Even a
            // committed response must not cause a view resolver/view side effect.
            writeGenericFailure(response);
        }
    }

    private static void writeGenericFailure(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) return;
        int status = response.getStatus();
        if (status < 400 || status > 599) status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        response.resetBuffer();
        response.setStatus(status);
        response.setHeader("Content-Length", null);
        response.setHeader("Location", null);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = "{\"code\":" + status + ",\"message\":\"系统异常\",\"data\":null}";
        try {
            response.getWriter().write(body);
        } catch (IllegalStateException outputStreamAlreadySelected) {
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
    }
}
