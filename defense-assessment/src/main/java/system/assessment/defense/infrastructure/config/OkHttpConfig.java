package system.assessment.defense.infrastructure.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * @Author taoHouChao
 * @Date 12:40 2025/5/19
 */
@Configuration
public class OkHttpConfig {

    @Bean
    public OkHttpClient okHttpClient() {
        // 连接池：最多 5 个空闲连接，存活 5 分钟
        ConnectionPool connectionPool = new ConnectionPool(5, 5, TimeUnit.MINUTES);

        return new OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .connectTimeout(10, TimeUnit.MINUTES)      // 连接建立
                .readTimeout(6, TimeUnit.MINUTES)        // 读取响应（语音识别较慢）
                .writeTimeout(3, TimeUnit.MINUTES)       // 上传音频
                .callTimeout(9, TimeUnit.MINUTES)        // 整个调用超时（防止 hang 住）
                // Model requests are billable POSTs. A transport layer must
                // not replay them after an ambiguous connection failure;
                // durable business workflows decide whether a retry is safe.
                .retryOnConnectionFailure(false)
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)) // 显式支持 HTTP/2
                .build();
    }
}
