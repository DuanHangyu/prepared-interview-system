package system.assessment.defense.infrastructure.config;

import org.springframework.context.annotation.Configuration;

/**
 * The copied prepared application uses authenticated HTTP commands and durable
 * recordings, not the archived real-time interview protocol. Do not enable
 * WebSocket mappings here, even when a legacy handler still exists for source
 * compatibility. A business-method fence does not protect the handshake or
 * connection allocation. Historical HTTP reads remain independently governed.
 */
@Configuration(proxyBeanMethods = false)
public class WebsocketConfig {
}
