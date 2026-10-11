package system.assessment.defense.prepared;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.config.*;
import java.io.IOException;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Advice contract tests supplement, not replace actual authenticated HTTP read boundaries. */
class PreparedLegacyReadFenceTest {
    @Test void teacherAndStudentDelegateOnlyThroughTheCorrectScopeAndPreserveOriginalFailures()throws Throwable{
        var guard=mock(PreparedLegacyRecordGuard.class);var fence=new PreparedLegacyReadFence(guard);var call=mock(ProceedingJoinPoint.class);when(call.getArgs()).thenReturn(new Object[]{7,21});
        when(guard.record(eq(7),eq(21),any())).thenAnswer(i->i.getArgument(2,Supplier.class).get());when(guard.studentDetail(eq(7),eq(21),any())).thenAnswer(i->i.getArgument(2,Supplier.class).get());
        when(call.proceed()).thenReturn("legacy-only");assertThat(fence.teacherDetail(call)).isEqualTo("legacy-only");assertThat(fence.studentDetail(call)).isEqualTo("legacy-only");
        var checked=new IOException("original checked");doThrow(checked).when(call).proceed();assertThatThrownBy(()->fence.teacherDetail(call)).isSameAs(checked);assertThatThrownBy(()->fence.studentDetail(call)).isSameAs(checked);
        var runtime=new IllegalStateException("original runtime");doThrow(runtime).when(call).proceed();assertThatThrownBy(()->fence.teacherDetail(call)).isSameAs(runtime);
        var error=new AssertionError("original assertion");doThrow(error).when(call).proceed();assertThatThrownBy(()->fence.studentDetail(call)).isSameAs(error);
    }
    @Test void malformedActorAndResourceNeverReachLegacyReads()throws Throwable{
        var guard=mock(PreparedLegacyRecordGuard.class);var fence=new PreparedLegacyReadFence(guard);var call=mock(ProceedingJoinPoint.class);
        for(Object[] args:new Object[][]{{0,21},{7,0},{"7",21},{7,null}}){when(call.getArgs()).thenReturn(args);assertThatThrownBy(()->fence.teacherDetail(call)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->fence.studentDetail(call)).isInstanceOf(IllegalArgumentException.class);}
        verify(call,never()).proceed();verifyNoInteractions(guard);
    }
}
