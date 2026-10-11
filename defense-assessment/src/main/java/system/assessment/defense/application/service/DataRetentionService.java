package system.assessment.defense.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import system.assessment.defense.infrastructure.concurrency.MySqlAdvisoryLockService;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataRetentionService {

    private final DataRetentionProperties properties;

    private final DataRetentionRepository repository;

    private final ManagedObjectCleanupService objectCleanupService;

    private final MySqlAdvisoryLockService advisoryLockService;

    @Scheduled(cron = "${data-retention.cleanup-cron:0 30 3 * * ?}")
    public void runScheduledCleanup() {
        if (!properties.isEnabled()) {
            return;
        }
        boolean acquired = advisoryLockService.tryWithLock(
                "scheduler:retention", () -> cleanupAt(LocalDateTime.now()));
        if (!acquired) {
            log.info("retention cleanup skipped because another instance owns the lock");
        }
    }

    public CleanupResult cleanupAt(LocalDateTime now) {
        validateConfiguration();
        MutableResult result = new MutableResult();
        cleanupTokenAudits(now, result);
        cleanupInterviewMedia(now, result);
        cleanupDeletedAssessmentMaterials(now, result);
        if (properties.isPurgeBusinessRecords()) {
            cleanupBusinessRecords(now, result);
        }
        CleanupResult finalResult = result.toImmutable();
        log.info("retention cleanup completed, tokenAudits:{}, mediaRecords:{}, assessmentMaterials:{}, businessRecords:{}, failures:{}",
                finalResult.tokenAudits(), finalResult.mediaRecords(), finalResult.assessmentMaterials(),
                finalResult.businessRecords(), finalResult.failures());
        return finalResult;
    }

    private void cleanupTokenAudits(LocalDateTime now, MutableResult result) {
        LocalDateTime cutoff = now.minus(properties.getTokenAudit());
        for (int batch = 0; batch < properties.getMaxBatchesPerRun(); batch++) {
            int deleted = repository.deleteTokenAuditsBefore(cutoff, properties.getBatchSize());
            result.tokenAudits += deleted;
            if (deleted < properties.getBatchSize()) {
                return;
            }
        }
    }

    private void cleanupInterviewMedia(LocalDateTime now, MutableResult result) {
        LocalDateTime cutoff = now.minus(properties.getInterviewMedia());
        for (int batch = 0; batch < properties.getMaxBatchesPerRun(); batch++) {
            List<DataRetentionRepository.InterviewMediaCandidate> candidates =
                    repository.findExpiredInterviewMedia(cutoff, properties.getBatchSize());
            for (DataRetentionRepository.InterviewMediaCandidate candidate : candidates) {
                try {
                    List<String> references = new java.util.ArrayList<>();
                    references.addAll(objectCleanupService.extractFileReferences(candidate.defenseFile()));
                    references.add(candidate.defenseVoice());
                    references.addAll(repository.findAnswerVoiceReferences(
                            candidate.assessmentId(), candidate.studentId()));
                    objectCleanupService.deleteAll(references);
                    repository.clearInterviewMedia(candidate.recordId(), candidate.assessmentId(), candidate.studentId());
                    result.mediaRecords++;
                } catch (RuntimeException exception) {
                    result.failures++;
                    log.warn("retention media cleanup failed, recordId:{}, errorType:{}",
                            candidate.recordId(), exception.getClass().getSimpleName());
                }
            }
            if (candidates.size() < properties.getBatchSize()) {
                return;
            }
        }
    }

    private void cleanupDeletedAssessmentMaterials(LocalDateTime now, MutableResult result) {
        LocalDateTime cutoff = now.minus(properties.getDeletedAssessmentMaterial());
        for (int batch = 0; batch < properties.getMaxBatchesPerRun(); batch++) {
            List<DataRetentionRepository.DeletedAssessmentMaterialCandidate> candidates =
                    repository.findExpiredDeletedMaterials(cutoff, properties.getBatchSize());
            for (DataRetentionRepository.DeletedAssessmentMaterialCandidate candidate : candidates) {
                try {
                    objectCleanupService.deleteAll(
                            objectCleanupService.extractFileReferences(candidate.assessmentFiles()));
                    repository.clearDeletedAssessmentMaterial(candidate.assessmentId());
                    result.assessmentMaterials++;
                } catch (RuntimeException exception) {
                    result.failures++;
                    log.warn("retention material cleanup failed, assessmentId:{}, errorType:{}",
                            candidate.assessmentId(), exception.getClass().getSimpleName());
                }
            }
            if (candidates.size() < properties.getBatchSize()) {
                return;
            }
        }
    }

    private void cleanupBusinessRecords(LocalDateTime now, MutableResult result) {
        LocalDateTime cutoff = now.minus(properties.getBusinessRecord());
        for (int batch = 0; batch < properties.getMaxBatchesPerRun(); batch++) {
            List<Integer> recordIds = repository.findExpiredBusinessRecords(cutoff, properties.getBatchSize());
            for (Integer recordId : recordIds) {
                try {
                    repository.deleteBusinessRecord(recordId);
                    result.businessRecords++;
                } catch (RuntimeException exception) {
                    result.failures++;
                    log.warn("retention business record cleanup failed, recordId:{}, errorType:{}",
                            recordId, exception.getClass().getSimpleName());
                }
            }
            if (recordIds.size() < properties.getBatchSize()) {
                return;
            }
        }
    }

    private void validateConfiguration() {
        if (properties.getTokenAudit().isNegative() || properties.getTokenAudit().isZero()
                || properties.getInterviewMedia().isNegative() || properties.getInterviewMedia().isZero()
                || properties.getDeletedAssessmentMaterial().isNegative()
                || properties.getDeletedAssessmentMaterial().isZero()
                || properties.getBusinessRecord().isNegative() || properties.getBusinessRecord().isZero()
                || properties.getBatchSize() < 1 || properties.getBatchSize() > 1000
                || properties.getMaxBatchesPerRun() < 1 || properties.getMaxBatchesPerRun() > 100) {
            throw new IllegalStateException("Invalid data retention configuration");
        }
    }

    public record CleanupResult(
            int tokenAudits,
            int mediaRecords,
            int assessmentMaterials,
            int businessRecords,
            int failures) {
    }

    private static final class MutableResult {
        private int tokenAudits;
        private int mediaRecords;
        private int assessmentMaterials;
        private int businessRecords;
        private int failures;

        CleanupResult toImmutable() {
            return new CleanupResult(tokenAudits, mediaRecords, assessmentMaterials, businessRecords, failures);
        }
    }
}
