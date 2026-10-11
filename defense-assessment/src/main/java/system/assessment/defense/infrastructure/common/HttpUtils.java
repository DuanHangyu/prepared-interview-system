package system.assessment.defense.infrastructure.common;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import system.assessment.defense.application.manage.OssManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * @USER taoHouChao
 * @DATE 08:23 2025/8/15
 */
@Component
@Slf4j
public class HttpUtils {

    private final OkHttpClient okHttpClient;

    private final OssManager ossManger;

    private final long maxDownloadBytes;

    public HttpUtils(OkHttpClient okHttpClient,
                     OssManager ossManger,
                     @Value("${file.download.max-bytes:73400320}") long maxDownloadBytes) {
        if (maxDownloadBytes <= 0 || maxDownloadBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("文件下载大小限制配置不合法");
        }
        this.okHttpClient = okHttpClient;
        this.ossManger = ossManger;
        this.maxDownloadBytes = maxDownloadBytes;
    }

    public FileByteDTO downloadFile(String fileUrl) {
        String signedUrl = ossManger.getSignedUrl(fileUrl);
        Request request = new Request.Builder()
                .url(signedUrl)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to download image: " + response);
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Response body is null");
            }
            byte[] bytes = readBoundedBody(body, maxDownloadBytes);
            log.info("download file success, urlLength:{}, fileSize:{}", fileUrl.length(), bytes.length);
            return new FileByteDTO(bytes);
        } catch (Exception e) {
            log.error("Failed to download file, urlLength:{}, errorType:{}",
                    fileUrl == null ? 0 : fileUrl.length(), e.getClass().getSimpleName());
            throw new IllegalStateException("文件下载失败", e);
        }
    }

    static byte[] readBoundedBody(ResponseBody body, long maxBytes) throws IOException {
        if (maxBytes <= 0 || maxBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("文件下载大小限制配置不合法");
        }
        long contentLength = body.contentLength();
        if (contentLength > maxBytes) {
            throw new IllegalArgumentException("文件大小超过限制");
        }

        int initialCapacity = contentLength > 0
                ? (int) Math.min(contentLength, maxBytes)
                : 8192;
        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity)) {
            byte[] buffer = new byte[8192];
            long totalBytes = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > maxBytes) {
                    throw new IllegalArgumentException("文件大小超过限制");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
