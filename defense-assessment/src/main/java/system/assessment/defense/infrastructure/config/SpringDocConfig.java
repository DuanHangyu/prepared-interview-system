package system.assessment.defense.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springdoc.core.customizers.OperationCustomizer;
import system.assessment.defense.interfaces.rest.LoginController;

import java.util.List;
import java.util.Map;

/**
 * @Author taoHouChao
 * @Date 15:36 2025/6/9
 */
@OpenAPIDefinition(
        servers = {
                @Server(description = "当前部署", url = "/")
        }
)
@Configuration
public class SpringDocConfig {

    @Bean
    public OpenAPI openAPI() {
        // 1. 定义 SecurityScheme（安全方案）
        SecurityScheme securityScheme = new SecurityScheme()
                .name("Authorization")
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE);

        // 2. 定义全局 SecurityRequirement（哪些接口需要认证）
        SecurityRequirement securityRequirement = new SecurityRequirement().addList("Authorization");
        return new OpenAPI()
                // 配置接口文档基本信息
                .info(this.getApiInfo())
                .addSecurityItem(securityRequirement) // 全局生效
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes("Authorization", securityScheme));
    }

    @Bean
    public OperationCustomizer apiContractOperationCustomizer() {
        return (operation, handlerMethod) -> {
            if (isPublicLogin(handlerMethod)) {
                // An empty operation-level array explicitly overrides the
                // OpenAPI document's global session-cookie requirement.
                operation.setSecurity(List.of());
            }
            wrapSuccessResponses(operation.getResponses());
            addStandardErrorResponses(operation.getResponses());
            return operation;
        };
    }

    private boolean isPublicLogin(HandlerMethod handlerMethod) {
        return LoginController.class.isAssignableFrom(handlerMethod.getBeanType())
                && "login".equals(handlerMethod.getMethod().getName());
    }

    private void wrapSuccessResponses(Map<String, ApiResponse> responses) {
        if (responses == null) {
            return;
        }
        responses.forEach((status, response) -> {
            if (!status.startsWith("2") || response.getContent() == null) {
                return;
            }
            response.getContent().values().forEach(mediaType -> {
                Schema<?> payload = mediaType.getSchema();
                if (payload != null && !isEnvelope(payload) && !isBinary(payload)) {
                    mediaType.setSchema(successEnvelope(payload));
                }
            });
        });
    }

    private void addStandardErrorResponses(Map<String, ApiResponse> responses) {
        if (responses == null) {
            return;
        }
        addErrorResponse(responses, "400", "请求参数错误");
        addErrorResponse(responses, "401", "未登录或登录已过期");
        addErrorResponse(responses, "403", "无权限");
        addErrorResponse(responses, "409", "数据冲突");
        addErrorResponse(responses, "413", "请求体过大");
        addErrorResponse(responses, "429", "请求过于频繁");
        addErrorResponse(responses, "500", "系统异常");
        addErrorResponse(responses, "503", "依赖服务暂不可用");
    }

    private void addErrorResponse(Map<String, ApiResponse> responses, String status, String description) {
        responses.putIfAbsent(status, new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(
                        org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                        new MediaType().schema(errorEnvelope(Integer.parseInt(status), description))
                )));
    }

    private Schema<?> successEnvelope(Schema<?> payload) {
        ObjectSchema envelope = new ObjectSchema();
        envelope.addProperty("code", new IntegerSchema().example(200));
        envelope.addProperty("message", new StringSchema().example("success"));
        envelope.addProperty("data", payload);
        envelope.addExtension("x-base-result-envelope", true);
        return envelope;
    }

    private Schema<?> errorEnvelope(int status, String message) {
        ObjectSchema envelope = new ObjectSchema();
        envelope.addProperty("code", new IntegerSchema().example(status));
        envelope.addProperty("message", new StringSchema().example(message));
        envelope.addProperty("data", new ObjectSchema().nullable(true));
        envelope.addExtension("x-base-result-envelope", true);
        return envelope;
    }

    private boolean isEnvelope(Schema<?> schema) {
        return schema.getExtensions() != null
                && Boolean.TRUE.equals(schema.getExtensions().get("x-base-result-envelope"));
    }

    private boolean isBinary(Schema<?> schema) {
        return "string".equals(schema.getType()) && "binary".equals(schema.getFormat());
    }

    private Info getApiInfo() {
        return new Info()
                // 配置文档标题
                .title("AI 答辩考核系统 API")
                // 配置文档描述
                .description("接口统一返回 {code, message, data}；认证使用 HttpOnly 会话 Cookie。")
                // 配置版本号
                .version("2.0");
    }
}
