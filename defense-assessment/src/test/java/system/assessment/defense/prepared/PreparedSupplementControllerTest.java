package system.assessment.defense.prepared;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedSupplementControllerTest {
    @Test void approvalsUseSessionManagerAndClosedWritesDoNotBecomeStudentSuccess(){
        var service=mock(PreparedSupplementService.class);var closed=new PreparedSupplementController(service,false);
        assertThat(PreparedSupplementController.class.getAnnotation(SaCheckRole.class).value()).containsExactly("TEACHER","ADMIN");
        assertThatThrownBy(()->closed.approve(8,91,"attempt","answer",null)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);verifyNoInteractions(service);
        try(var session=mockStatic(StpUtil.class)){
            session.when(StpUtil::getLoginIdAsInt).thenReturn(72);closed.status(8,91,"attempt","answer");
            byte[] body=new PreparedSupplementCommandTest().raw().getBytes(StandardCharsets.UTF_8);var command=PreparedSupplement.decode(body);
            new PreparedSupplementController(service,true).approve(8,91,"attempt","answer",body);
            verify(service).approve(72,91,8,"attempt","answer",command);verify(service).status(72,91,8,"attempt","answer");
        }
    }
}
