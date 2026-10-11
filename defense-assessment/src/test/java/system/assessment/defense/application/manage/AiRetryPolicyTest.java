package system.assessment.defense.application.manage;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.DefenseAssessmentSystemApplication;
import system.assessment.defense.infrastructure.config.OkHttpConfig;
import system.assessment.defense.infrastructure.config.ThreadPoolConfig;
import system.assessment.defense.infrastructure.repository.dao.mapper.TokenConsumerMapper;
import system.assessment.defense.infrastructure.repository.dao.po.TokenConsumerPO;

import java.util.Arrays;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class AiRetryPolicyTest {

    @Test
    void billableModelOperationsDoNotHideGenericRuntimeRetries() {
        assertThat(Arrays.stream(GeminiManager.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getAnnotations()))
                .map(annotation -> annotation.annotationType().getName())
                .filter(name -> name.equals("org.springframework.retry.annotation.Retryable")))
                .as("provider calls must not be retried for every parse, schema, or business failure")
                .isEmpty();
        assertThat(DefenseAssessmentSystemApplication.class.getAnnotations())
                .noneMatch(annotation -> annotation.annotationType().getName()
                        .equals("org.springframework.retry.annotation.EnableRetry"));
        assertThat(new OkHttpConfig().okHttpClient().retryOnConnectionFailure()).isFalse();
        ThreadPoolExecutor businessExecutor = new ThreadPoolConfig().threadPoolExecutor();
        try {
            assertThat(businessExecutor.getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            businessExecutor.shutdownNow();
        }
    }

    @Test
    void saturatedTelemetryQueueCannotDiscardSuccessfulModelResult() {
        ThreadPoolExecutor rejectedExecutor = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
        rejectedExecutor.shutdownNow();
        OpenAiManager manager = new OpenAiManager(
                new OkHttpConfig().okHttpClient(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(TokenConsumerMapper.class),
                rejectedExecutor);

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(
                manager, "persistAuditAsync", TokenConsumerPO.builder().method("test").build()))
                .doesNotThrowAnyException();
    }
}
