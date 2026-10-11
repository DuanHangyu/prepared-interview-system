package system.assessment.defense.security;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.application.service.FileSecurityService;
import system.assessment.defense.interfaces.rest.FileController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssFileBoundaryTest {

    private OSS oss;
    private OssManager manager;

    @BeforeEach
    void setUp() {
        oss = mock(OSS.class);
        manager = new OssManager();
        ReflectionTestUtils.setField(manager, "ossClient", oss);
        ReflectionTestUtils.setField(manager, "bucketName", "test-bucket");
        ReflectionTestUtils.setField(manager, "endpoint", "oss.example.test");
        ReflectionTestUtils.setField(manager, "fileSecurityService", new FileSecurityService());
    }

    @Test
    void uploadUsesOwnerScopedKeyAndSafeMetadata() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "REPORT.PDF", "application/pdf", "%PDF-1.7\nbody".getBytes());
        when(oss.putObject(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenReturn(new PutObjectResult());

        String key = manager.uploadFile(file, 12);

        ArgumentCaptor<ObjectMetadata> metadata = ArgumentCaptor.forClass(ObjectMetadata.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(oss).putObject(
                org.mockito.Mockito.eq("test-bucket"), keyCaptor.capture(), any(InputStream.class), metadata.capture());
        assertThat(key).isEqualTo(keyCaptor.getValue());
        assertThat(key).matches("defense-assessment-new/users/12/\\d{4}/\\d{2}/\\d{2}/[a-f0-9]{32}\\.pdf");
        assertThat(metadata.getValue().getContentType()).isEqualTo("application/pdf");
        assertThat(metadata.getValue().getContentDisposition()).isEqualTo("attachment");
    }

    @Test
    void uploadClosesTheMultipartStreamAfterObjectStoreWrite() throws Exception {
        org.springframework.web.multipart.MultipartFile file = mock(
                org.springframework.web.multipart.MultipartFile.class);
        InputStream uploadStream = mock(InputStream.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(13L);
        when(file.getOriginalFilename()).thenReturn("report.pdf");
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getInputStream()).thenReturn(
                new ByteArrayInputStream("%PDF-1.7\nbody".getBytes()),
                new ByteArrayInputStream("%PDF-1.7\nbody".getBytes()),
                uploadStream);
        when(oss.putObject(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenReturn(new PutObjectResult());

        manager.uploadFile(file, 12);

        verify(uploadStream).close();
    }

    @Test
    void previewForcesPdfSandboxAndNoSniffHeaders() throws Exception {
        String key = "defense-assessment-new/2025/08/10/0123456789abcdef0123456789abcdef.pdf";
        byte[] body = "%PDF-body".getBytes();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(body.length);
        metadata.setContentType("application/pdf");
        when(oss.getObjectMetadata("test-bucket", key)).thenReturn(metadata);

        OSSObject header = new OSSObject();
        header.setObjectContent(new ByteArrayInputStream(Arrays.copyOf(body, 5)));
        OSSObject content = new OSSObject();
        content.setObjectContent(new ByteArrayInputStream(body));
        when(oss.getObject(any(GetObjectRequest.class))).thenReturn(header, content);

        MockHttpServletResponse response = new MockHttpServletResponse();
        manager.preview(key, new MockHttpServletRequest(), response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Content-Security-Policy")).contains("sandbox");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getContentAsByteArray()).isEqualTo(body);
    }

    @Test
    void previewClosesObjectStoreResourcesWhenStreamingFails() throws Exception {
        String key = "defense-assessment-new/2025/08/10/0123456789abcdef0123456789abcdef.pdf";
        byte[] body = "%PDF-body".getBytes();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(body.length);
        metadata.setContentType("application/pdf");
        when(oss.getObjectMetadata("test-bucket", key)).thenReturn(metadata);

        OSSObject header = new OSSObject();
        header.setObjectContent(new ByteArrayInputStream(Arrays.copyOf(body, 5)));
        OSSObject content = mock(OSSObject.class);
        InputStream failingStream = mock(InputStream.class);
        when(content.getObjectContent()).thenReturn(failingStream);
        when(failingStream.read(any(byte[].class))).thenThrow(new IOException("stream failed"));
        when(oss.getObject(any(GetObjectRequest.class))).thenReturn(header, content);

        MockHttpServletResponse response = new MockHttpServletResponse();
        manager.preview(key, new MockHttpServletRequest(), response);

        verify(failingStream).close();
        verify(content).close();
        assertThat(response.getStatus()).isEqualTo(500);
    }

    @Test
    void unsafeDirectUploadUrlEndpointHasBeenRemoved() {
        assertThat(Arrays.stream(FileController.class.getDeclaredMethods())
                .map(method -> method.getName()))
                .doesNotContain("generateUploadUrl");
    }

    @Test
    void failedObjectStoreWriteCannotReturnAFalseSuccess() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "%PDF-1.7\nbody".getBytes());
        when(oss.putObject(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> manager.uploadFile(file, 12))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("上传失败");
        assertThatThrownBy(() -> manager.uploadVoice(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }
}
