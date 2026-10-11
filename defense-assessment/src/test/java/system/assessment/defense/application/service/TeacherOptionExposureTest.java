package system.assessment.defense.application.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import system.assessment.defense.application.dto.TeacherOptionDTO;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentInviteService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherOptionExposureTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "teacher-option-test"),
                UserPO.class);
    }

    @Mock private UserService userService;
    @Mock private PasswordSecurityService passwordSecurityService;
    @Mock private AssessmentSettingService assessmentSettingService;
    @Mock private AssessmentInviteService assessmentInviteService;

    @InjectMocks private TeacherAppService teacherAppService;

    @Test
    void invitationOptionsExcludeTheCurrentTeacherAndSensitiveFields() {
        when(userService.list(any(Wrapper.class))).thenReturn(List.of(
                UserPO.builder().id(8).name("Invitee").account("invitee-account").phone("10001").build()));

        List<TeacherOptionDTO> options;
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(7);
            options = teacherAppService.allTeacherOptions();
        }

        assertThat(options).singleElement().satisfies(option -> {
            assertThat(option.getId()).isEqualTo(8);
            assertThat(option.getName()).isEqualTo("Invitee");
        });
        ArgumentCaptor<Wrapper<UserPO>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(userService).list(query.capture());
        assertThat(query.getValue().getSqlSegment())
                .contains("id <>")
                .contains("LIMIT 50");
    }
}
