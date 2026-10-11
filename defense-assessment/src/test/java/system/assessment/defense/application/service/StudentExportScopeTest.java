package system.assessment.defense.application.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import system.assessment.defense.application.dto.StudentExportCmd;
import system.assessment.defense.infrastructure.common.HttpUtils;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentStudentRelationService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StudentExportScopeTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "student-export-scope-test"),
                UserPO.class);
    }

    @Mock private UserService userService;
    @Mock private StudentAssessmentAppointmentService appointmentService;
    @Mock private AssessmentStudentRelationService relationService;
    @Mock private StudentAssessmentRecordService recordService;
    @Mock private HttpUtils httpUtils;
    @Mock private StudentAssessmentQuestionAnswerService questionAnswerService;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private PasswordSecurityService passwordSecurityService;
    @Mock private ManagedObjectCleanupService objectCleanupService;
    @InjectMocks private StudentAppService studentAppService;

    @Test
    void explicitStudentExportCannotIncludeOtherAccountRoles() {
        when(userService.listByIds(List.of(10, 20))).thenReturn(List.of(
                UserPO.builder().id(10).name("Student").account("s-10").schoolClass("Class 1").type(0).build(),
                UserPO.builder().id(20).name("Teacher").account("t-20").type(1).build()
        ));
        StudentExportCmd command = new StudentExportCmd();
        command.setIds(List.of(10, 20));

        var exported = studentAppService.exportStudent(command);

        assertThat(exported)
                .extracting(item -> item.getAccount())
                .containsExactly("s-10");
    }

    @Test
    void allStudentExportRejectsMoreThanTheSafeBatchSize() {
        when(userService.list(any(Wrapper.class))).thenReturn(IntStream.rangeClosed(1, 1_001)
                .mapToObj(id -> UserPO.builder().id(id).type(0).account("s-" + id).build())
                .toList());
        StudentExportCmd command = new StudentExportCmd();
        command.setAll(true);

        assertThatThrownBy(() -> studentAppService.exportStudent(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1000");
        ArgumentCaptor<Wrapper<UserPO>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(userService).list(wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment()).contains("LIMIT 1001");
    }

    @Test
    void classAndStudentOptionQueriesAreBounded() {
        when(userService.list(any(Wrapper.class)))
                .thenReturn(List.of(UserPO.builder().schoolClass("Class 1").build()))
                .thenReturn(List.of(UserPO.builder()
                        .id(10).name("Student").schoolClass("Class 1").build()));

        assertThat(studentAppService.allSchoolClassOptions()).containsExactly("Class 1");
        assertThat(studentAppService.studentOptionsBySchoolClass("Class 1"))
                .extracting(option -> option.getId())
                .containsExactly(10);

        ArgumentCaptor<Wrapper<UserPO>> wrappers = ArgumentCaptor.forClass(Wrapper.class);
        verify(userService, org.mockito.Mockito.times(2)).list(wrappers.capture());
        assertThat(wrappers.getAllValues())
                .allSatisfy(wrapper -> assertThat(wrapper.getSqlSegment()).contains("LIMIT 1001"));
    }
}
