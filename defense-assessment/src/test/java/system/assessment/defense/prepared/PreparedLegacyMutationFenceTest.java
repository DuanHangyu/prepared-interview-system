package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import system.assessment.defense.application.service.*;
import system.assessment.defense.application.dto.StudentDefenseFileUploadCmd;
import system.assessment.defense.application.dto.backed.*;
import system.assessment.defense.prepared.config.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedLegacyMutationFenceTest {
    @Test void legacyRecordCommandsGoThroughTheScopedTransactionalGuardBeforeDelegation() {
        var guard=mock(PreparedLegacyRecordGuard.class);var target=mock(StudyRecordService.class);
        var proxy=proxy(target,guard);var score=new StudentRecordChangeScoreCmd();score.setId(91);var retake=new RecordRetakeCmd();retake.setRecordId(92);
        when(guard.record(eq(7),eq(91),any())).thenThrow(new BusinessException(409,"prepared"));
        assertThatThrownBy(()->proxy.changeScore(7,score)).isInstanceOf(BusinessException.class);verifyNoInteractions(target);
        when(guard.record(eq(7),eq(92),any())).thenAnswer(i->((Supplier<?>)i.getArgument(2)).get());when(target.retake(7,retake)).thenReturn(true);
        assertThat(proxy.retake(7,retake)).isTrue();verify(target).retake(7,retake);verifyNoMoreInteractions(target);
    }
    @Test void materialBindingIsGuardedButAudioCleanupStillWorks() {
        var guard=mock(PreparedLegacyRecordGuard.class);var target=mock(StudentInterviewSessionService.class);var proxy=proxy(target,guard);
        var command=new StudentDefenseFileUploadCmd();command.setAssessmentId(8);
        when(guard.material(eq(7),eq(8),any())).thenThrow(new BusinessException(409,"prepared"));
        assertThatThrownBy(()->proxy.uploadDefenseFile(command,7)).isInstanceOf(BusinessException.class);verifyNoInteractions(target);
        proxy.releaseAudio(7);verify(target).releaseAudio(7);
    }
    @Test void invalidCommandsFailBeforeGuardOrDelegateAndOriginalErrorsAreNotChanged() {
        var guard=mock(PreparedLegacyRecordGuard.class);var target=mock(StudyRecordService.class);var proxy=proxy(target,guard);
        assertThatThrownBy(()->proxy.retake(7,null)).isInstanceOf(IllegalArgumentException.class);
        var command=new StudentRecordChangeScoreCmd();command.setId(9);
        when(guard.record(eq(7),eq(9),any())).thenAnswer(i->((Supplier<?>)i.getArgument(2)).get());
        var original=new BusinessException(403,"original");when(target.changeScore(7,command)).thenThrow(original);
        assertThatThrownBy(()->proxy.changeScore(7,command)).isSameAs(original);
    }
    private static <T> T proxy(T target,PreparedLegacyRecordGuard guard) {
        var factory=new AspectJProxyFactory(target);factory.setProxyTargetClass(true);factory.addAspect(new PreparedLegacyMutationFence(guard));return factory.getProxy();
    }
}
