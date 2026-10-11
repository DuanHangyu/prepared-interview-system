package system.assessment.defense.infrastructure.common;

import com.alicp.jetcache.anno.Cached;
import com.sun.net.httpserver.HttpServer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import system.assessment.defense.application.manage.OssManager;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpUtilsBoundaryTest {

    @Test
    void acceptsResponseAtConfiguredByteLimit() throws Exception {
        try (ResponseBody body = ResponseBody.create(new byte[8], MediaType.get("application/octet-stream"))) {
            assertThat(HttpUtils.readBoundedBody(body, 8)).hasSize(8);
        }
    }

    @Test
    void rejectsKnownAndStreamingResponsesAboveConfiguredByteLimit() throws Exception {
        try (ResponseBody knownLength = ResponseBody.create(new byte[9], null)) {
            assertThatThrownBy(() -> HttpUtils.readBoundedBody(knownLength, 8))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("文件大小超过限制");
        }

        try (ResponseBody streaming = unknownLengthBody(new byte[9])) {
            assertThatThrownBy(() -> HttpUtils.readBoundedBody(streaming, 8))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("文件大小超过限制");
        }
    }

    @Test
    void rawFileBytesAreNeverCached() throws Exception {
        Method download = HttpUtils.class.getMethod("downloadFile", String.class);

        assertThat(download.getAnnotation(Cached.class)).isNull();
    }

    @Test
    void actualChunkedDownloadIsRejectedAtTheBoundary() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oversized", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[9]);
            exchange.close();
        });
        server.start();
        try {
            OssManager ossManager = mock(OssManager.class);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/oversized";
            when(ossManager.getSignedUrl("owned-key")).thenReturn(url);
            HttpUtils httpUtils = new HttpUtils(new OkHttpClient(), ossManager, 8);

            assertThatThrownBy(() -> httpUtils.downloadFile("owned-key"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("文件下载失败")
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        } finally {
            server.stop(0);
        }
    }

    private ResponseBody unknownLengthBody(byte[] bytes) {
        return new ResponseBody() {
            private final Buffer source = new Buffer().write(bytes);

            @Override
            public MediaType contentType() {
                return null;
            }

            @Override
            public long contentLength() {
                return -1;
            }

            @Override
            public Buffer source() {
                return source;
            }
        };
    }
}
