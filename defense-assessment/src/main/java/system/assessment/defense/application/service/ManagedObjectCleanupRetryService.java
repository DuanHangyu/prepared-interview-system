package system.assessment.defense.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.infrastructure.concurrency.MySqlAdvisoryLockService;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManagedObjectCleanupRetryService {

    private static final int BATCH_SIZE = 100;
    private static final long MAX_RETRY_DELAY_MINUTES = 360;

    private final ManagedObjectCleanupTaskRepository repository;
    private final OssManager ossManager;
    private final MySqlAdvisoryLockService advisoryLockService;

    @Scheduled(
            fixedDelayString = "${object-cleanup.retry-delay-ms:60000}",
            initialDelayString = "${object-cleanup.initial-delay-ms:60000}")
    public void runScheduledCleanup() {
        boolean acquired = advisoryLockService.tryWithLock(
                "scheduler:object-cleanup", this::processDueTasks);
        if (!acquired) {
            log.debug("object cleanup skipped because another instance owns the lock");
        }
    }

    public int processDueTasks() {
        List<ManagedObjectCleanupTaskRepository.CleanupTask> tasks = repository.findDue(BATCH_SIZE);
        int deleted = 0;
        for (ManagedObjectCleanupTaskRepository.CleanupTask task : tasks) {
            try {
                if (repository.isReferenced(task.objectReference())) {
                    repository.deleteById(task.id());
                    continue;
                }
                ossManager.deleteStoredObject(task.objectReference());
                repository.deleteById(task.id());
                deleted++;
            } catch (RuntimeException exception) {
                repository.markFailure(task.id(), retryDelayMinutes(task.attempts()),
                        exception.getClass().getSimpleName());
                log.warn("managed object cleanup failed, taskId:{}, attempt:{}, errorType:{}",
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
