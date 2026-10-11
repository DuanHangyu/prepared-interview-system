package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import system.assessment.defense.infrastructure.config.WebsocketConfig;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** This default configuration contract supplements actual HTTP upgrade tests. */
class PreparedHttpOnlyCommunicationContractTest {
    @Test void thePreparedConfigurationDoesNotEnableOrRegisterRealtimeHandlers() {
        assertThat(WebsocketConfig.class.isAnnotationPresent(EnableWebSocket.class)).isFalse();
        assertThat(WebSocketConfigurer.class.isAssignableFrom(WebsocketConfig.class)).isFalse();
        // Coverage instrumentation adds synthetic bookkeeping members, not
        // application registrations. The annotation/type checks remain strict.
        assertThat(Arrays.stream(WebsocketConfig.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic()).toList()).isEmpty();
    }

    @Test void thePreparedConfigurationDoesNotInstantiateArchivedRealtimeHandlers() {
        assertThat(Arrays.stream(WebsocketConfig.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic()).toList()).isEmpty();
        assertThat(WebsocketConfig.class.getDeclaredConstructors()).singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes()).isEmpty());
    }
}
