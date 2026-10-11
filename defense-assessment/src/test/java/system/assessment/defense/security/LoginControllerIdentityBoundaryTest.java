package system.assessment.defense.security;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.application.dto.ChangePasswordDTO;
import system.assessment.defense.application.dto.UserDetailDTO;
import system.assessment.defense.application.service.LoginService;
import system.assessment.defense.interfaces.rest.LoginController;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginControllerIdentityBoundaryTest {
    @Test void existingAccountEndpointsContinueUsingOnlyTheSessionIdentity() {
        var service=mock(LoginService.class);var controller=new LoginController();
        ReflectionTestUtils.setField(controller,"loginService",service);
        var detail=UserDetailDTO.builder().id(72).role("STUDENT").build();
        var password=new ChangePasswordDTO();
        when(service.userDetail(72)).thenReturn(detail);
        when(service.changePassword(72,password)).thenReturn(true);
        try(var session=mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsInt).thenReturn(72);
            assertThat(controller.userDetail()).isSameAs(detail);
            assertThat(controller.changePassword(password)).isTrue();
            controller.logout();
        }
        verify(service).userDetail(72);verify(service).changePassword(72,password);verify(service).logout();
        verifyNoMoreInteractions(service);
    }
}
