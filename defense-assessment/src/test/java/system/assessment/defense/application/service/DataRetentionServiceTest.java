package system.assessment.defense.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.infrastructure.concurrency.MySqlAdvisoryLockService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataRetentionServiceTest {

    private final DataRetentionRepository repository = mock(DataRetentionRepository.class);
    private final OssManager ossManager = mock(OssManager.class);
    private final MySqlAdvisoryLockService lockService = mock(MySqlAdvisoryLockService.class);
    private final DataRetentionProperties properties = new DataRetentionProperties();
    private DataRetentionService service;

    @BeforeEach
    void setUp() {
        properties.setBatchSize(20);
        properties.setMaxBatchesPerRun(1);
        service = new DataRetentionService(properties, repository,
                new ManagedObjectCleanupService(ossManager), lockService);
    }

    @Test
    void cleanupDeletesObjectsBeforeClearingExpiredMediaAndMaterials() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 2, 12, 0);
        var media = new DataRetentionRepository.InterviewMediaCandidate(
                11, 22, 33,
                "{\"fileUrl\":\"defense-assessment-new/users/33/file.pdf\"}",
                "defense-assessment-new/system/voice.wav");
        var material = new DataRetentionRepository.DeletedAssessmentMaterialCandidate(
                44, "[{\"fileUrl\":\"defense-assessment-new/users/7/material.pdf\"}]");

        when(repository.deleteTokenAuditsBefore(any(), eq(20))).thenReturn(3);
        when(repository.findExpiredInterviewMedia(any(), eq(20))).thenReturn(List.of(media));
        when(repository.findAnswerVoiceReferences(22, 33)).thenReturn(List.of(
                "defense-assessment-new/system/answer.wav",
                "defense-assessment-new/system/follow.wav"));
        when(repository.findExpiredDeletedMaterials(any(), eq(20))).thenReturn(List.of(material));
        when(ossManager.deleteStoredObject(any())).thenReturn(true);

        DataRetentionService.CleanupResult result = service.cleanupAt(now);

        assertThat(result.tokenAudits()).isEqualTo(3);
        assertThat(result.mediaRecords()).isEqualTo(1);
        assertThat(result.assessmentMaterials()).isEqualTo(1);
        verify(ossManager).deleteStoredObject("defense-assessment-new/users/33/file.pdf");
        verify(ossManager).deleteStoredObject("defense-assessment-new/system/voice.wav");
        verify(ossManager).deleteStoredObject("defense-assessment-new/system/answer.wav");
        verify(ossManager).deleteStoredObject("defense-assessment-new/system/follow.wav");
        verify(ossManager).deleteStoredObject("defense-assessment-new/users/7/material.pdf");
        verify(repository).clearInterviewMedia(11, 22, 33);
        verify(repository).clearDeletedAssessmentMaterial(44);
        verify(repository).deleteTokenAuditsBefore(now.minus(Duration.ofDays(30)), 20);
    }

    @Test
    void failedObjectDeletionKeepsDatabaseReferencesForRetry() {
        var media = new DataRetentionRepository.InterviewMediaCandidate(
                11, 22, 33, null, "defense-assessment-new/system/voice.wav");
        when(repository.findExpiredInterviewMedia(any(), anyInt())).thenReturn(List.of(media));
        when(repository.findExpiredDeletedMaterials(any(), anyInt())).thenReturn(List.of());
        when(ossManager.deleteStoredObject(any())).thenThrow(new IllegalStateException("temporary OSS failure"));

        DataRetentionService.CleanupResult result = service.cleanupAt(LocalDateTime.of(2026, 10, 2, 12, 0));

        assertThat(result.failures()).isEqualTo(1);
        verify(repository, never()).clearInterviewMedia(anyInt(), anyInt(), anyInt());
    }

    @Test
    void businessRecordsAreOnlyPurgedAfterExplicitOptIn() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 2, 12, 0);
        when(repository.findExpiredInterviewMedia(any(), anyInt())).thenReturn(List.of());
        when(repository.findExpiredDeletedMaterials(any(), anyInt())).thenReturn(List.of());
        when(repository.findExpiredBusinessRecords(any(), anyInt())).thenReturn(List.of(91));

        service.cleanupAt(now);
        verify(repository, never()).deleteBusinessRecord(91);

        properties.setPurgeBusinessRecords(true);
        DataRetentionService.CleanupResult result = service.cleanupAt(now);

        assertThat(result.businessRecords()).isEqualTo(1);
        verify(repository).deleteBusinessRecord(91);
        verify(repository).findExpiredBusinessRecords(now.minus(Duration.ofDays(730)), 20);
    }

    @Test
    void scheduledCleanupUsesCrossInstanceLockAndHonorsDisableSwitch() {
        properties.setEnabled(false);
        service.runScheduledCleanup();
        verifyNoInteractions(lockService);

        properties.setEnabled(true);
        service.runScheduledCleanup();
        verify(lockService).tryWithLock(eq("scheduler:retention"), any(Runnable.class));
    }
}
