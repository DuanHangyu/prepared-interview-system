package system.assessment.defense.infrastructure.repository.dao.service.impl;

import org.junit.jupiter.api.Test;
import system.assessment.defense.infrastructure.repository.dao.mapper.StudentAssessmentRecordMapper;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentAssessmentRecordServiceConcurrencyTest {

    private final StudentAssessmentRecordMapper mapper = mock(StudentAssessmentRecordMapper.class);
    private final StudentAssessmentRecordServiceImpl service =
            new StudentAssessmentRecordServiceImpl(mapper);

    @Test
    void interviewStartUpdatesAnEligibleRecordBeforeTryingInsert() {
        LocalDateTime now = LocalDateTime.now();
        when(mapper.resetForInterviewStart(7, 11, now)).thenReturn(1);

        assertThat(service.startInterview(7, 11, now)).isTrue();

        verify(mapper).resetForInterviewStart(7, 11, now);
    }

    @Test
    void concurrentInterviewStartUsesInsertIgnoreAsStableDuplicateResult() {
        LocalDateTime now = LocalDateTime.now();
        when(mapper.resetForInterviewStart(7, 11, now)).thenReturn(0);
        when(mapper.insertInterviewStartIfAbsent(any(StudentAssessmentRecordPO.class))).thenReturn(0);

        assertThat(service.startInterview(7, 11, now)).isFalse();
    }

    @Test
    void questionPhaseCanOnlyBeStartedOnce() {
        LocalDateTime now = LocalDateTime.now();
        when(mapper.markQuestionPhaseStarted(7, 11, now, false)).thenReturn(0);
        when(mapper.insertQuestionPhaseIfAbsent(any(StudentAssessmentRecordPO.class))).thenReturn(1);

        assertThat(service.startQuestionPhase(7, 11, now)).isTrue();

        when(mapper.insertQuestionPhaseIfAbsent(any(StudentAssessmentRecordPO.class))).thenReturn(0);
        assertThat(service.startQuestionPhase(7, 11, now)).isFalse();
    }

    @Test
    void combinedAssessmentRequiresCompletedDefenseBeforeQuestionClaim() {
        LocalDateTime now = LocalDateTime.now();
        when(mapper.markQuestionPhaseStarted(7, 11, now, true)).thenReturn(0);

        assertThat(service.startQuestionPhase(7, 11, now, true)).isFalse();

        verify(mapper, never()).insertQuestionPhaseIfAbsent(any(StudentAssessmentRecordPO.class));
    }

    @Test
    void failedQuestionGenerationReleasesOnlyItsOwnClaim() {
        LocalDateTime startedAt = LocalDateTime.now().minusSeconds(2);
        LocalDateTime failedAt = LocalDateTime.now();
        when(mapper.releaseQuestionPhase(7, 11, startedAt, failedAt)).thenReturn(1);

        assertThat(service.releaseQuestionPhase(7, 11, startedAt, failedAt)).isTrue();
        verify(mapper).releaseQuestionPhase(7, 11, startedAt, failedAt);
    }

    @Test
    void analysisClaimsExposeAtomicCompareAndSetResults() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusMinutes(6);
        when(mapper.claimInitialAnalysis(42, now)).thenReturn(1);
        when(mapper.claimManualAnalysis(43, now)).thenReturn(0);
        when(mapper.claimRetryAnalysis(44, staleBefore, now)).thenReturn(1);

        assertThat(service.claimInitialAnalysis(42, now)).isTrue();
        assertThat(service.claimManualAnalysis(43, now)).isFalse();
        assertThat(service.claimRetryAnalysis(44, staleBefore, now)).isTrue();
    }
}
