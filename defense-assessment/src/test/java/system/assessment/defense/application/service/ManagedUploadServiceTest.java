package system.assessment.defense.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.infrastructure.concurrency.MySqlAdvisoryLockService;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedUploadServiceTest {

    private static final String KEY =
            "defense-assessment-new/users/7/2026/10/03/0123456789abcdef0123456789abcdef.pdf";

    private final ManagedUploadRepository repository = mock(ManagedUploadRepository.class);
    private final OssManager ossManager = mock(OssManager.class);
    private final FileSecurityService fileSecurityService = mock(FileSecurityService.class);
    private final MySqlAdvisoryLockService lockService = mock(MySqlAdvisoryLockService.class);
    private final ManagedUploadService service =
            new ManagedUploadService(repository, ossManager, fileSecurityService, lockService);

    @Test
    void uploadIsRegisteredBeforeTheObjectStoreWrite() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("design.pdf");
        when(file.getSize()).thenReturn(1024L);
        when(ossManager.createPdfObjectKey("design.pdf", 7)).thenReturn(KEY);

        assertThat(service.upload(file, 7)).isEqualTo(KEY);

        var order = inOrder(repository, ossManager);
        order.verify(repository).register(eq(7), eq(KEY), eq(1024L), any(LocalDateTime.class));
        order.verify(ossManager).uploadFileToKey(file, KEY);
    }

    @Test
    void ambiguousObjectStoreFailureLeavesTheUploadDueForCleanup() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("design.pdf");
        when(file.getSize()).thenReturn(1024L);
        when(ossManager.createPdfObjectKey("design.pdf", 7)).thenReturn(KEY);
        org.mockito.Mockito.doThrow(new IllegalStateException("OSS timeout"))
                .when(ossManager).uploadFileToKey(file, KEY);

        assertThatThrownBy(() -> service.upload(file, 7))
                .isInstanceOf(IllegalStateException.class);

        verify(repository).scheduleCleanup(eq(7), eq(KEY), any(LocalDateTime.class));
    }

    @Test
    void bindingConsumesExactlyOnePendingUnexpiredUpload() {
        when(repository.consumeForBinding(eq(7), eq(KEY), any(LocalDateTime.class)))
                .thenReturn(1, 0);

        service.consumeForBinding(7, KEY);

        assertThatThrownBy(() -> service.consumeForBinding(7, KEY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重新上传");
    }

    @Test
    void expirationClaimsBeforeDeletingAndRetriesFailures() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 3, 9, 0);
        LocalDateTime staleBefore = now.minusMinutes(10);
        var success = new ManagedUploadRepository.UploadTask(11, KEY, 0);
        var failure = new ManagedUploadRepository.UploadTask(12, KEY + "-other", 2);
        when(repository.findCleanupCandidates(now, staleBefore, 100))
                .thenReturn(List.of(success, failure));
        when(repository.claimForCleanup(11, now, staleBefore)).thenReturn(1);
        when(repository.claimForCleanup(12, now, staleBefore)).thenReturn(1);
        when(ossManager.deleteStoredObject(failure.objectReference()))
                .thenThrow(new IllegalStateException("OSS unavailable"));

        assertThat(service.processExpiredUploads(now)).isEqualTo(1);

        verify(repository).deleteAfterCleanup(11);
        verify(repository).retryLater(12, now.plusMinutes(4), "IllegalStateException");
        verify(repository, never()).deleteAfterCleanup(12);
    }
}
