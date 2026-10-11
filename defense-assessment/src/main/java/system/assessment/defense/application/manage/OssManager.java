package system.assessment.defense.application.manage;

import com.alicp.jetcache.anno.CacheRefresh;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.*;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.multipart.MultipartFile;
import system.assessment.defense.application.service.FileSecurityService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * @Author taoHouChao
 * @Date 23:22 2025/5/17
 */
@Component
@Slf4j
public class OssManager {

    @Resource
    @Lazy
    private OSS ossClient;

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.bucketName}")
    private String bucketName;

    @Resource
    private FileSecurityService fileSecurityService;

    public String uploadFile(MultipartFile file, Integer ownerId) {
        fileSecurityService.requireSafePdf(file);
        String uploadFileName = createPdfObjectKey(file.getOriginalFilename(), ownerId);
        uploadFileToKey(file, uploadFileName);
        return uploadFileName;
    }

    public String createPdfObjectKey(String originalFilename, Integer ownerId) {
        return getFileName(originalFilename, ownerId);
    }

    public void uploadFileToKey(MultipartFile file, String uploadFileName) {
        fileSecurityService.requireSafePdf(file);
        fileSecurityService.requireSafePdfKey(uploadFileName);
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentType("application/pdf");
        objectMetadata.setContentLength(file.getSize());
        objectMetadata.setContentDisposition("attachment");
        try (InputStream inputStream = file.getInputStream()) {
            PutObjectResult putObjectResult = ossClient.putObject(
                    bucketName, uploadFileName, inputStream, objectMetadata);
            if (putObjectResult != null) {
                log.info("uploaded assessment PDF, bytes:{}", file.getSize());
                return;
            }
        } catch (IOException e) {
            log.error("上传文件失败,e", e);
            throw new IllegalStateException("文件上传失败", e);
        }
        throw new IllegalStateException("文件上传失败");
    }

    public String uploadVoice(byte[] voiceData) {
        if (voiceData == null || voiceData.length == 0) {
            throw new IllegalArgumentException("语音内容不能为空");
        }
        // 将字节数组转换为输入流
        ByteArrayInputStream inputStream = new ByteArrayInputStream(voiceData);
        // 设置对象元数据
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(voiceData.length);
        metadata.setContentType("audio/wav"); // 根据实际音频格式设置

        String uploadFileName = getSystemFileName(UUID.randomUUID() + ".wav");
        try {
            PutObjectResult putObjectResult = ossClient.putObject(bucketName, uploadFileName, inputStream, metadata);
            if (putObjectResult != null) {
                log.info("uploaded interview voice, bytes:{}", voiceData.length);
                return uploadFileName;
            }
        } catch (Exception e) {
            log.error("上传文件失败,e", e);
            throw new IllegalStateException("语音上传失败", e);
        }
        throw new IllegalStateException("语音上传失败");
    }

    private String getFileName(String originalFilename, Integer ownerId) {
        DateTimeFormatter dft = DateTimeFormatter.ofPattern("yyyy/MM/dd");
        LocalDateTime time = LocalDateTime.now();
        //拼装OSS上存储的路径
        String folder = dft.format(time);
        String fileName = generateUUID();
        // 扩展名
        if (originalFilename.contains(".")) {
            String extension = Optional.of(originalFilename).map(item -> item.substring(item.lastIndexOf("."))).orElse("");
            //在OSS上bucket下的文件名
            return "defense-assessment-new/users/" + ownerId + "/" + folder + "/" + fileName + extension.toLowerCase();
        }else {
            return "defense-assessment-new/users/" + ownerId + "/" + folder + "/" + fileName;
        }
    }

    private String getSystemFileName(String originalFilename) {
        DateTimeFormatter dft = DateTimeFormatter.ofPattern("yyyy/MM/dd");
        String folder = dft.format(LocalDateTime.now());
        String extension = originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : "";
        return "defense-assessment-new/system/" + folder + "/" + generateUUID() + extension.toLowerCase();
    }

    private String generateUUID() {
        return UUID.randomUUID().toString().replaceAll("-", "").substring(0, 32);
    }

    public boolean deleteStoredObject(String reference) {
        if (reference == null || reference.isBlank()) {
            return false;
        }
        String key = normalizeOwnedKey(reference);
        ossClient.deleteObject(bucketName, key);
        log.info("deleted managed OSS object, category:{}, keyLength:{}",
                key.startsWith("defense-assessment-new/users/") ? "user" : "system", key.length());
        return true;
    }

    private String normalizeOwnedKey(String reference) {
        String key = reference.trim();
        if (key.startsWith("http://") || key.startsWith("https://")) {
            URI uri;
            try {
                uri = URI.create(key);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid managed object URL", exception);
            }
            String expectedHost = bucketName + "." + endpoint;
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !expectedHost.equalsIgnoreCase(uri.getHost())
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Object URL is not owned by this application");
            }
            key = uri.getPath();
            if (key.startsWith("/")) {
                key = key.substring(1);
            }
        }
        if ((!key.startsWith("defense-assessment-new/users/")
                && !key.startsWith("defense-assessment-new/system/"))
                || key.contains("..")
                || key.contains("\\")
                || key.length() > 1000) {
            throw new IllegalArgumentException("Object key is not owned by this application");
        }
        return key;
    }

    @Cached(name = "oss_url_", key = "#url",
            expire = 600, localExpire = 300,
            localLimit = 1000,
            cacheType = CacheType.LOCAL)
    @CacheRefresh(refresh = 100, stopRefreshAfterLastAccess = 3600)
    public String getSignedUrl(String url) {
        // 设置URL过期时间（单位：毫秒，建议30分钟）
        Date expiration = new Date(System.currentTimeMillis() + 60 * 60 * 1000);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, url, HttpMethod.GET);
        request.setExpiration(expiration);
        String httpUrl = ossClient.generatePresignedUrl(request).toString();
        return httpUrl.replace("http://", "https://");
    }

    public void preview(String fileKey, HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {

            fileSecurityService.requireSafePdfKey(fileKey);

            // 1. 获取文件元信息
            ObjectMetadata metadata;
            try {
                metadata = ossClient.getObjectMetadata(bucketName, fileKey);
            } catch (OSSException e) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "文件不存在");
                return;
            }

            long fileSize = metadata.getContentLength();
            if (fileSize <= 0 || fileSize > FileSecurityService.MAX_PDF_BYTES) {
                response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "文件大小不合法");
                return;
            }
            String storedContentType = metadata.getContentType();
            if (storedContentType != null
                    && !storedContentType.isBlank()
                    && !"application/pdf".equalsIgnoreCase(storedContentType)
                    && !"application/octet-stream".equalsIgnoreCase(storedContentType)) {
                response.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "文件类型不合法");
                return;
            }
            if (!hasPdfMagic(fileKey)) {
                response.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "文件内容不合法");
                return;
            }

            // 2. 解析 Range 请求
            String rangeHeader = request.getHeader("Range");
            long start = 0, end = fileSize - 1;
            boolean isPartial = false;

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                isPartial = true;
                String[] ranges = rangeHeader.substring(6).split("-");
                try {
                    start = ranges.length > 0 && !ranges[0].isEmpty() ? Long.parseLong(ranges[0]) : 0;
                    end = ranges.length > 1 && !ranges[1].isEmpty() ? Long.parseLong(ranges[1]) : fileSize - 1;
                } catch (NumberFormatException e) {
                    response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                    response.setHeader("Content-Range", "bytes */" + fileSize);
                    return;
                }

                // 校验范围合法性
                start = Math.max(0, start);
                end = Math.min(end, fileSize - 1);

                if (start > end) {
                    response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                    response.setHeader("Content-Range", "bytes */" + fileSize);
                    return;
                }
            }

            // 3. 设置响应头
            response.setContentType("application/pdf");
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Content-Length", String.valueOf(end - start + 1));
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'; object-src 'none'");
            response.setHeader("Cross-Origin-Resource-Policy", "same-site");
            response.setHeader("Cache-Control", "private, no-store");

            if (isPartial) {
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT); // 206
                response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
            } else {
                response.setStatus(HttpServletResponse.SC_OK); // 200
                // 支持中文文件名
                String fileName = fileKey.substring(fileKey.lastIndexOf("/") + 1);
                response.setHeader("Content-Disposition",
                        "inline; filename=\"" + URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "\"");
            }

            // 4. 从 OSS 获取指定范围的数据
            GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, fileKey);
            getObjectRequest.setRange(start, end);
            try (OSSObject ossObject = ossClient.getObject(getObjectRequest);
                    InputStream inputStream = ossObject.getObjectContent()) {
                // 5. 写出到响应流
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    response.getOutputStream().write(buffer, 0, bytesRead);
                }
                response.flushBuffer();
            }

        } catch (Exception e) {
            // 日志记录
            log.error("文件预览失败", e);
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "服务器内部错误");
            }
        }
    }

    private boolean hasPdfMagic(String fileKey) throws IOException {
        GetObjectRequest headerRequest = new GetObjectRequest(bucketName, fileKey);
        headerRequest.setRange(0, 4);
        OSSObject headerObject = ossClient.getObject(headerRequest);
        try (InputStream input = headerObject.getObjectContent()) {
            return "%PDF-".equals(new String(input.readNBytes(5), StandardCharsets.US_ASCII));
        } finally {
            headerObject.close();
        }
    }
}
