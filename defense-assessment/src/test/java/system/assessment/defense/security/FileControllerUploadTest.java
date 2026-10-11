package system.assessment.defense.security;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.application.service.ManagedUploadService;
import system.assessment.defense.interfaces.rest.FileController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileControllerUploadTest {

    @Test
    void uploadEndpointUsesTheTrackedUploadLifecycle() {
        String key = "defense-assessment-new/users/7/2026/10/03/0123456789abcdef0123456789abcdef.pdf";
        MockMultipartFile file = new MockMultipartFile(
                "file", "design.pdf", "application/pdf", "%PDF-test".getBytes());
        ManagedUploadService managedUploadService = mock(ManagedUploadService.class);
        OssManager ossManager = mock(OssManager.class);
        when(managedUploadService.upload(file, 7)).thenReturn(key);
        when(ossManager.getSignedUrl(key)).thenReturn("https://signed.example/file");
        FileController controller = new FileController();
        ReflectionTestUtils.setField(controller, "managedUploadService", managedUploadService);
        ReflectionTestUtils.setField(controller, "ossClientUtils", ossManager);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);

            var result = controller.uploadFile(file);

            assertThat(result.getUrl()).isEqualTo(key);
            assertThat(result.getPresignedUrl()).isEqualTo("https://signed.example/file");
        }
        verify(managedUploadService).upload(file, 7);
    }
}
