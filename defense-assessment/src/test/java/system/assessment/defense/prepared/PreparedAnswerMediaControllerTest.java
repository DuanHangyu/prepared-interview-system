package system.assessment.defense.prepared;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedAnswerMediaControllerTest {
    @Test void usesActualStudentSessionAndNeverAcceptsCloudOrCodecPaths() {
        var service=mock(PreparedAnswerMediaService.class);var controller=new PreparedAnswerMediaController(service,true);
        assertThat(PreparedAnswerMediaController.class.getAnnotation(SaCheckRole.class).value()).containsExactly("STUDENT");
        var command=new PreparedAnswerMediaService.Command("d44c8ec0-334a-4b74-a568-7aff1cfa13db","a".repeat(64));
        try(var session=mockStatic(StpUtil.class)){session.when(StpUtil::getLoginIdAsInt).thenReturn(72);controller.request(9,"attempt","answer",command);controller.status(9,"attempt","answer");}
        verify(service).request(72,9,"attempt","answer",command);verify(service).status(72,9,"attempt","answer");
        assertThat(PreparedAnswerMediaService.Command.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName).containsExactly("captureClientId","manifestSha256");
    }
    @Test void defaultClosedWritesAndAuthorizedReadRecoveryRemainSeparate() {
        var service=mock(PreparedAnswerMediaService.class);var controller=new PreparedAnswerMediaController(service,false);
        assertThatThrownBy(()->controller.request(9,"attempt","answer",null)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);verifyNoInteractions(service);
        try(var session=mockStatic(StpUtil.class)){session.when(StpUtil::getLoginIdAsInt).thenReturn(72);controller.status(9,"attempt","answer");}verify(service).status(72,9,"attempt","answer");
    }
}
