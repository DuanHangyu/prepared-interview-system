package system.assessment.defense.application.service;

import org.junit.jupiter.api.Test;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ManagedObjectCleanupServiceTest {

    @Test
    void interviewArtifactsAreQueuedInsteadOfBeingDeletedInline() {
        OssManager ossManager = mock(OssManager.class);
        ManagedObjectCleanupTaskRepository repository = mock(ManagedObjectCleanupTaskRepository.class);
        ManagedObjectCleanupService service = new ManagedObjectCleanupService(ossManager, repository);
        StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                .defenseFile("{\"fileUrl\":\"users/7/defense.pdf\"}")
                .defenseVoice("system/voice.wav")
                .build();

        service.deleteInterviewArtifacts(List.of(record), List.of());

        verify(repository).enqueue(argThat(references ->
                references.size() == 2
                        && references.contains("users/7/defense.pdf")
                        && references.contains("system/voice.wav")));
        verifyNoInteractions(ossManager);
    }

    @Test
    void replacementQueuesOnlyReferencesThatAreNoLongerRetained() {
        OssManager ossManager = mock(OssManager.class);
        ManagedObjectCleanupTaskRepository repository = mock(ManagedObjectCleanupTaskRepository.class);
        ManagedObjectCleanupService service = new ManagedObjectCleanupService(ossManager, repository);

        service.queueRemovedReferences(
                List.of("users/7/old.pdf", "users/7/retained.pdf"),
                List.of("users/7/retained.pdf", "users/7/new.pdf"));

        verify(repository).enqueue(argThat(references ->
                references.size() == 1 && references.contains("users/7/old.pdf")));
    }
}
