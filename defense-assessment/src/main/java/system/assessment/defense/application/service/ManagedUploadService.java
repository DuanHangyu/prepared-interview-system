package system.assessment.defense.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.infrastructure.concurrency.MySqlAdvisoryLockService;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManagedUploadService {

    private static final Duration BINDING_WINDOW = Duration.ofHours(24);
    private static final Duration STALE_CLEANING_TIMEOUT = Duration.ofMinutes(10);
    private static final int BATCH_SIZE = 100;
    private static final long MAX_RETRY_DELAY_MINUTES = 360;

    private final ManagedUploadRepository repository;
    private final OssManager ossManager;
    private final FileSecurityService fileSecurityService;
    private final MySqlAdvisoryLockService advisoryLockService;

    public String upload(MultipartFile file, Integer ownerId) {
        fileSecurityService.requireSafePdf(file);
        if (ownerId == null || ownerId <= 0) {
            throw new BusinessException(ErrorCodeEnums.NO_PERMISSION);
        }
        String objectKey = ossManager.createPdfObjectKey(file.getOriginalFilename(), ownerId);
        LocalDateTime now = LocalDateTime.now();
        repository.register(ownerId, objectKey, file.getSize(), now.plus(BINDING_WINDOW));
        try {
            ossManager.uploadFileToKey(file, objectKey);
            return objectKey;
        } catch (RuntimeException exception) {
            repository.scheduleCleanup(ownerId, objectKey, now);
            throw exception;
        }
    }

    public void consumeForBinding(Integer ownerId, String objectKey) {
        if (ownerId == null
                || repository.consumeForBinding(ownerId, objectKey, LocalDateTime.now()) != 1) {
            throw new BusinessException(ErrorCodeEnums.FILE_UPLOAD_EXPIRED);
        }
    }

    @Scheduled(
            fixedDelayString = "${managed-upload.cleanup-delay-ms:60000}",
            initialDelayString = "${managed-upload.initial-delay-ms:60000}")
    public void runScheduledCleanup() {
        boolean acquired = advisoryLockService.tryWithLock(
                "scheduler:managed-upload", () -> processExpiredUploads(LocalDateTime.now()));
        if (!acquired) {
            log.debug("unbound upload cleanup skipped because another instance owns the lock");
        }
    }

    public int processExpiredUploads(LocalDateTime now) {
        LocalDateTime staleCleaningBefore = now.minus(STALE_CLEANING_TIMEOUT);
        List<ManagedUploadRepository.UploadTask> tasks =
                repository.findCleanupCandidates(now, staleCleaningBefore, BATCH_SIZE);
        int deleted = 0;
        for (ManagedUploadRepository.UploadTask task : tasks) {
            if (repository.claimForCleanup(task.id(), now, staleCleaningBefore) != 1) {
                continue;
            }
            try {
                ossManager.deleteStoredObject(task.objectReference());
                repository.deleteAfterCleanup(task.id());
                deleted++;
            } catch (RuntimeException exception) {
                repository.retryLater(task.id(),
                        now.plusMinutes(retryDelayMinutes(task.attempts())),
                        exception.getClass().getSimpleName());
                log.warn("unbound upload cleanup failed, taskId:{}, attempt:{}, errorType:{}",
                        task.id(), task.attempts() + 1, exception.getClass().getSimpleName());
            }
        }
        return deleted;
    }

    private long retryDelayMinutes(int previousAttempts) {
        int exponent = Math.min(Math.max(previousAttempts, 0), 9);
        return Math.min(1L << exponent, MAX_RETRY_DELAY_MINUTES);
    }
}
