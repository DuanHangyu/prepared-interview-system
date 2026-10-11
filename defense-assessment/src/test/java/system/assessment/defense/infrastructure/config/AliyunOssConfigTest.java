package system.assessment.defense.infrastructure.config;

import com.aliyun.oss.ClientConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AliyunOssConfigTest {

    @Test
    void appliesBoundedUploadTimeoutsAndRetries() {
        AliyunOssConfig config = new AliyunOssConfig();
        ReflectionTestUtils.setField(config, "endpoint", "https://oss.example.com");
        ReflectionTestUtils.setField(config, "accessKeyId", "test-key");
        ReflectionTestUtils.setField(config, "accessKeySecret", "test-secret");
        ReflectionTestUtils.setField(config, "connectionTimeoutMs", 5_000);
        ReflectionTestUtils.setField(config, "socketTimeoutMs", 30_000);
        ReflectionTestUtils.setField(config, "requestTimeoutMs", 45_000);
        ReflectionTestUtils.setField(config, "maxErrorRetry", 1);

        OSS client = config.ossClient();
        try {
            ClientConfiguration clientConfiguration = ((OSSClient) client).getClientConfiguration();
            assertThat(clientConfiguration.getConnectionTimeout()).isEqualTo(5_000);
            assertThat(clientConfiguration.getSocketTimeout()).isEqualTo(30_000);
            assertThat(clientConfiguration.getRequestTimeout()).isEqualTo(45_000);
            assertThat(clientConfiguration.isRequestTimeoutEnabled()).isTrue();
            assertThat(clientConfiguration.getMaxErrorRetry()).isEqualTo(1);
        } finally {
            client.shutdown();
        }
    }
}
