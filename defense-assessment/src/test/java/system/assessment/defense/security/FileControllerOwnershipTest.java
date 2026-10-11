package system.assessment.defense.security;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.application.service.FileAccessControlService;
import system.assessment.defense.application.service.FileSecurityService;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;
import system.assessment.defense.interfaces.rest.FileController;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FileControllerOwnershipTest {

    private static final String STOLEN_KEY =
            "defense-assessment-new/users/12/2026/10/03/0123456789abcdef0123456789abcdef.pdf";

    private OssManager ossManager;
    private FileController controller;

    @BeforeEach
    void setUp() {
        UserService userService = mock(UserService.class);
        ResourceAccessControlService resourceAccess = mock(ResourceAccessControlService.class);
        AssessmentSettingService settingService = mock(AssessmentSettingService.class);
        StudentAssessmentRecordService recordService = mock(StudentAssessmentRecordService.class);
        StudentAssessmentQuestionAnswerService answerService = mock(StudentAssessmentQuestionAnswerService.class);
        FileSecurityService fileSecurity = new FileSecurityService();
        FileAccessControlService fileAccess = new FileAccessControlService(
                userService, resourceAccess, settingService, recordService, answerService, fileSecurity);

        when(userService.findById(13)).thenReturn(Optional.of(
                UserPO.builder().id(13).type(0).build()));
        when(settingService.list(any(Wrapper.class))).thenReturn(List.of());
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(
                StudentAssessmentRecordPO.builder()
                        .assessmentId(21)
                        .studentId(13)
                        .defenseFile("{\"fileUrl\":\"" + STOLEN_KEY + "\"}")
                        .build()));
        when(answerService.list(any(Wrapper.class))).thenReturn(List.of());

        ossManager = mock(OssManager.class);
        controller = new FileController();
        ReflectionTestUtils.setField(controller, "ossClientUtils", ossManager);
        ReflectionTestUtils.setField(controller, "fileSecurityService", fileSecurity);
        ReflectionTestUtils.setField(controller, "fileAccessControlService", fileAccess);
    }

    @Test
    void forgedExistingRecordCannotObtainSignedUrlOrPreview() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(13);

            assertThatThrownBy(() -> controller.getSignedUrl(STOLEN_KEY))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> controller.preview(
                    STOLEN_KEY, mock(HttpServletRequest.class), mock(HttpServletResponse.class)))
                    .isInstanceOf(BusinessException.class);
        }

        verifyNoInteractions(ossManager);
    }
}
