package system.assessment.defense.infrastructure.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * @Author taoHouChao
 * @Date 23:22 2025/5/17
 */
@Configuration
public class AliyunOssConfig {

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.accessKeyId}")
    private String accessKeyId;

    @Value("${aliyun.oss.accessKeySecret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.connection-timeout-ms:5000}")
    private int connectionTimeoutMs;

    @Value("${aliyun.oss.socket-timeout-ms:30000}")
    private int socketTimeoutMs;

    @Value("${aliyun.oss.request-timeout-ms:45000}")
    private int requestTimeoutMs;

    @Value("${aliyun.oss.max-error-retry:1}")
    private int maxErrorRetry;

    // Optional compatible PDF storage must not block prepared account/manual-paper
    // startup. Actual use still constructs the real SDK and fails on missing credentials.
    @Bean
    @Lazy
    public OSS ossClient() {
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setConnectionTimeout(connectionTimeoutMs);
        configuration.setSocketTimeout(socketTimeoutMs);
        configuration.setRequestTimeoutEnabled(true);
        configuration.setRequestTimeout(requestTimeoutMs);
        configuration.setMaxErrorRetry(maxErrorRetry);
        return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret, configuration);
    }
}
