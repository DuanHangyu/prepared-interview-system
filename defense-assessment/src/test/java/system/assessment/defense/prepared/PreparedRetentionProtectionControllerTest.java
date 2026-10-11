package system.assessment.defense.prepared;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.retention.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedRetentionProtectionControllerTest {
    @Test void mutationUsesFreshSessionRoleAndExistingClosedWorkflowBoundary(){
        var service=mock(PreparedRetentionProtectionService.class);
        var closed=new PreparedRetentionProtectionController(service,false);
        assertThat(PreparedRetentionProtectionController.class.getAnnotation(SaCheckRole.class).value()).containsExactly("TEACHER","ADMIN");
        assertThatThrownBy(()->closed.execute(8,91,"attempt",null)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);verifyNoInteractions(service);
        try(var session=mockStatic(StpUtil.class)){
            session.when(StpUtil::getLoginIdAsInt).thenReturn(72);closed.status(8,91,"attempt");
            byte[] body=("{\"idempotencyKey\":\""+UUID.randomUUID()+"\",\"expectedRevision\":0,\"action\":\"PROTECT\",\"reason\":\"核验原尝试\"}").getBytes(StandardCharsets.UTF_8);
            new PreparedRetentionProtectionController(service,true).execute(8,91,"attempt",body);
            verify(service).execute(72,91,8,"attempt",PreparedRetentionProtection.decode(body));verify(service).status(72,91,8,"attempt");
        }
    }
}
