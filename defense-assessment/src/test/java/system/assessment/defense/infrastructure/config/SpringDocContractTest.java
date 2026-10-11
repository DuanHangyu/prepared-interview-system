package system.assessment.defense.infrastructure.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import system.assessment.defense.application.dto.LoginDTO;
import system.assessment.defense.interfaces.rest.LoginController;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SpringDocContractTest {

    @Test
    void documentsSaTokenAsHttpOnlyCookieAuthentication() {
        var api = new SpringDocConfig().openAPI();
        var scheme = api.getComponents().getSecuritySchemes().get("Authorization");

        assertThat(scheme.getType()).isEqualTo(io.swagger.v3.oas.models.security.SecurityScheme.Type.APIKEY);
        assertThat(scheme.getIn()).isEqualTo(io.swagger.v3.oas.models.security.SecurityScheme.In.COOKIE);
        assertThat(scheme.getName()).isEqualTo("Authorization");
        assertThat(api.getSecurity()).hasSize(1);
    }

    @Test
    void loginOverridesGlobalAuthenticationAndResponsesMatchRuntimeEnvelope() throws Exception {
        Operation operation = new Operation().responses(new ApiResponses().addApiResponse(
                "200",
                new ApiResponse().description("ok").content(new Content().addMediaType(
                        org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                        new MediaType().schema(new StringSchema())
                ))
        ));
        HandlerMethod login = new HandlerMethod(
                new LoginController(),
                LoginController.class.getDeclaredMethod("login", LoginDTO.class, HttpServletRequest.class)
        );

        operationCustomizer().customize(operation, login);

        assertThat(operation.getSecurity()).isEmpty();
        Schema<?> success = operation.getResponses().get("200").getContent()
                .get(org.springframework.http.MediaType.APPLICATION_JSON_VALUE).getSchema();
        assertThat(success.getProperties()).containsKeys("code", "message", "data");
        assertThat(((Schema<?>) success.getProperties().get("data"))).isInstanceOf(StringSchema.class);
        assertThat(operation.getResponses().keySet())
                .containsAll(Set.of("400", "401", "403", "409", "413", "429", "500", "503"));
    }

    @Test
    void generatedDocumentationPayloadsAreNotWrappedByRuntimeAdvice() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/v3/api-docs/swagger-config");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            Method method = LoginController.class.getDeclaredMethod("login", LoginDTO.class, HttpServletRequest.class);
            MethodParameter returnType = new MethodParameter(method, -1);

            assertThat(new GlobalResponse().supports(returnType, MappingJackson2HttpMessageConverter.class))
                    .isFalse();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentationRoutesBypassAuthenticationInterceptor() {
        InterceptorRegistry registry = new InterceptorRegistry();
        new SaTokenConfig(
                mock(SecurityRateLimitInterceptor.class),
                mock(CsrfOriginInterceptor.class),
                mock(system.assessment.defense.infrastructure.repository.dao.service.UserService.class)).addInterceptors(registry);

        var registrations = (java.util.List<InterceptorRegistration>)
                ReflectionTestUtils.getField(registry, "registrations");
        var exclusions = registrations.stream()
                .map(registration -> (java.util.List<String>)
                        ReflectionTestUtils.getField(registration, "excludePatterns"))
                .filter(patterns -> patterns != null && patterns.contains("/v3/api-docs/**"))
                .findFirst()
                .orElseThrow();

        assertThat(exclusions).contains(
                "/v3/api-docs/**",
                "/swagger-ui.html",
                "/swagger-ui/**",
                "/webjars/**"
        );
    }

    private OperationCustomizer operationCustomizer() throws Exception {
        Method factory = SpringDocConfig.class.getDeclaredMethod("apiContractOperationCustomizer");
        return (OperationCustomizer) factory.invoke(new SpringDocConfig());
    }
}
