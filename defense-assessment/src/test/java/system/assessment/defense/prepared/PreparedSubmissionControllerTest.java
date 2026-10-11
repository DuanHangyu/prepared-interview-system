package system.assessment.defense.prepared;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PreparedSubmissionControllerTest {
    @Test void actualStudentIdentityAndClosedWritesNeverBecomeClientSuccess(){
        var service=mock(PreparedSubmissionService.class);var closed=new PreparedSubmissionController(service,false);
        assertThat(PreparedSubmissionController.class.getAnnotation(SaCheckRole.class).value()).containsExactly("STUDENT");
        assertThatThrownBy(()->closed.submit(8,"attempt",null)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);verifyNoInteractions(service);
        try(var session=mockStatic(StpUtil.class)){session.when(StpUtil::getLoginIdAsInt).thenReturn(72);closed.status(8,"attempt");var command=new PreparedSubmission.Command(UUID.randomUUID().toString(),3L);new PreparedSubmissionController(service,true).submit(8,"attempt",command);verify(service).submit(72,8,"attempt",command);}
        verify(service).status(72,8,"attempt");assertThat(PreparedSubmission.Command.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName).containsExactly("idempotencyKey","expectedRevision");
    }
    @Test void missingOrUnsafeOriginalCommandIsRejectedBeforePersistence(){
        assertThatThrownBy(()->new PreparedSubmission.Command("replacement",0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedSubmission.Command(UUID.randomUUID().toString(),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedSubmission.Command(UUID.randomUUID().toString(),-1L)).isInstanceOf(IllegalArgumentException.class);
    }
}
