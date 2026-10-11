package system.assessment.defense.application.service;

import org.junit.jupiter.api.Test;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.infrastructure.concurrency.MySqlAdvisoryLockService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManagedObjectCleanupRetryServiceTest {

    private final ManagedObjectCleanupTaskRepository repository =
            mock(ManagedObjectCleanupTaskRepository.class);
    private final OssManager ossManager = mock(OssManager.class);
    private final MySqlAdvisoryLockService lockService = mock(MySqlAdvisoryLockService.class);
    private final ManagedObjectCleanupRetryService service =
            new ManagedObjectCleanupRetryService(repository, ossManager, lockService);

    @Test
    void successfulDeletionRemovesTheDurableTask() {
        var task = new ManagedObjectCleanupTaskRepository.CleanupTask(
                7, "defense-assessment-new/system/voice.wav", 0);
        when(repository.findDue(100)).thenReturn(List.of(task));

        assertThat(service.processDueTasks()).isEqualTo(1);

        verify(ossManager).deleteStoredObject(task.objectReference());
        verify(repository).deleteById(7);
        verify(repository, never()).markFailure(eq(7L), anyLong(), any());
    }

    @Test
    void failedDeletionIsRetainedWithExponentialBackoff() {
        var task = new ManagedObjectCleanupTaskRepository.CleanupTask(
                8, "defense-assessment-new/system/voice.wav", 3);
        when(repository.findDue(100)).thenReturn(List.of(task));
        when(ossManager.deleteStoredObject(task.objectReference()))
                .thenThrow(new IllegalStateException("temporary OSS failure"));

        assertThat(service.processDueTasks()).isZero();

        verify(repository).markFailure(8, 8, "IllegalStateException");
        verify(repository, never()).deleteById(8);
    }

    @Test
    void aReferenceStillUsedByAnotherBusinessRecordIsNeverDeleted() {
        var task = new ManagedObjectCleanupTaskRepository.CleanupTask(
                9, "defense-assessment-new/users/7/shared.pdf", 0);
        when(repository.findDue(100)).thenReturn(List.of(task));
        when(repository.isReferenced(task.objectReference())).thenReturn(true);

        assertThat(service.processDueTasks()).isZero();

        verify(repository).deleteById(9);
        verifyNoInteractions(ossManager);
    }
}
