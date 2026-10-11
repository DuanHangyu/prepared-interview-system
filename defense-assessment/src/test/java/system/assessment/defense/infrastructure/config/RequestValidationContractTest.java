package system.assessment.defense.infrastructure.config;

import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;
import system.assessment.defense.application.dto.AssessmentSettingPagingQuery;
import system.assessment.defense.application.dto.AssessmentSettingsCreateCmd;
import system.assessment.defense.application.dto.AssessmentSettingsModifyCmd;
import system.assessment.defense.application.dto.FileBindingCmd;
import system.assessment.defense.application.dto.LoginDTO;
import system.assessment.defense.application.dto.StudentDefenseFileUploadCmd;
import system.assessment.defense.application.dto.StudentDefensePagingQuery;
import system.assessment.defense.application.dto.StudentCreateCmd;
import system.assessment.defense.application.dto.StudentModifyCmd;
import system.assessment.defense.application.dto.StudentPageQuery;
import system.assessment.defense.application.dto.StudentExportCmd;
import system.assessment.defense.application.dto.StudyRecordPagingQuery;
import system.assessment.defense.application.dto.TeacherCreateCmd;
import system.assessment.defense.application.dto.TeacherModifyCmd;
import system.assessment.defense.application.dto.TeacherPageQuery;
import system.assessment.defense.application.dto.ChangePasswordDTO;
import system.assessment.defense.application.dto.backed.AppointmentSettingCreateCmd;
import system.assessment.defense.application.dto.backed.SchoolClassStudentSaveCmd;
import system.assessment.defense.application.dto.backed.StudentRecordExcelQuery;
import system.assessment.defense.interfaces.rest.LoginController;
import system.assessment.defense.interfaces.rest.backend.AssessmentSettingsController;
import system.assessment.defense.interfaces.rest.backend.StudentBackendController;
import system.assessment.defense.interfaces.rest.backend.StudyRecordController;
import system.assessment.defense.interfaces.rest.backend.TeacherBackendController;
import system.assessment.defense.interfaces.rest.front.StudentAssessmentController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestValidationContractTest {

    private static final List<Class<?>> CONTROLLERS = List.of(
            LoginController.class,
            AssessmentSettingsController.class,
            StudentBackendController.class,
            StudyRecordController.class,
            TeacherBackendController.class,
            StudentAssessmentController.class
    );

    @Test
    void everyRequestBodyIsValidatedAtTheHttpBoundary() {
        List<String> missingValidation = new ArrayList<>();

        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.isAnnotationPresent(RequestBody.class)
                            && !parameter.isAnnotationPresent(Valid.class)) {
                        missingValidation.add(controller.getSimpleName() + "#" + method.getName());
                    }
                }
            }
        }

        assertThat(missingValidation).as("all @RequestBody parameters must also use @Valid").isEmpty();
    }

    @Test
    void loginAndNestedFileBindingRejectMissingRequiredFields() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            LoginDTO login = new LoginDTO();
            assertThat(validator.validate(login)).hasSize(2);

            StudentDefenseFileUploadCmd upload = new StudentDefenseFileUploadCmd();
            assertThat(validator.validate(upload)).hasSize(2);

            upload.setAssessmentId(1);
            upload.setFile(new FileBindingCmd());
            assertThat(validator.validate(upload))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("file.fileUrl");
        }
    }

    @Test
    void allPageRequestsDefaultToTwentyAndRejectOutOfRangeValues() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            List<Object> queries = List.of(
                    new AssessmentSettingPagingQuery(),
                    new StudentDefensePagingQuery(),
                    new StudyRecordPagingQuery(),
                    new StudentPageQuery(),
                    new TeacherPageQuery()
            );

            for (Object query : queries) {
                assertThat(read(query, "getPage")).isEqualTo(1);
                assertThat(read(query, "getSize")).isEqualTo(20);
                write(query, "setPage", 0);
                write(query, "setSize", 101);
                assertThat(validator.validate(query))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .contains("page", "size");
            }
        }
    }

    @Test
    void persistencePaginationHasAnIndependentHardLimit() {
        var interceptors = new MybatisConfig().mybatisPlusInterceptor().getInterceptors();

        assertThat(interceptors).hasSize(1);
        assertThat(interceptors.getFirst()).isInstanceOf(PaginationInnerInterceptor.class);
        assertThat(((PaginationInnerInterceptor) interceptors.getFirst()).getMaxLimit()).isEqualTo(100L);
    }

    @Test
    void nestedCollectionsAndExportSelectorsRejectNullOrEmptyChoices() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            AssessmentSettingsCreateCmd assessment = new AssessmentSettingsCreateCmd();
            assessment.setAssessmentFiles(Collections.singletonList(null));
            assertThat(validator.validate(assessment))
                    .extracting(violation -> violation.getMessage())
                    .contains("考核文件不能为空");

            AppointmentSettingCreateCmd appointment = new AppointmentSettingCreateCmd();
            appointment.setTimePeriods(Collections.singletonList(null));
            assertThat(validator.validate(appointment))
                    .extracting(violation -> violation.getMessage())
                    .contains("预约时间段不能为空");

            SchoolClassStudentSaveCmd schoolClass = new SchoolClassStudentSaveCmd();
            schoolClass.setSchoolClass("一班");
            schoolClass.setStudents(Collections.singletonList(null));
            assertThat(validator.validate(schoolClass))
                    .extracting(violation -> violation.getMessage())
                    .contains("学生id不能为空");

            assertThat(validator.validate(new StudentExportCmd()))
                    .extracting(violation -> violation.getMessage())
                    .contains("请选择要导出的学生");
            assertThat(validator.validate(new StudentRecordExcelQuery()))
                    .extracting(violation -> violation.getMessage())
                    .contains("请提供考核主题或记录ID");
        }
    }

    @Test
    void explicitExportSelectionsAreBounded() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            StudentExportCmd students = new StudentExportCmd();
            students.setIds(Collections.nCopies(1_001, 1));
            assertThat(validator.validate(students))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("ids");

            StudentRecordExcelQuery records = new StudentRecordExcelQuery();
            records.setRecordIds(Collections.nCopies(1_001, 1));
            assertThat(validator.validate(records))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("recordIds");
        }
    }

    @Test
    void assessmentConfigurationBoundsModelCostAndBulkWork() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            List<Class<?>> commands = List.of(
                    AssessmentSettingsCreateCmd.class,
                    AssessmentSettingsModifyCmd.class
            );
            for (Class<?> command : commands) {
                assertInvalidProperty(validator, command, "theme", "x".repeat(501));
                assertInvalidProperty(validator, command, "assessmentCriteria", "x".repeat(12_001));
                assertInvalidProperty(validator, command, "assessmentRequirements", "x".repeat(8_001));
                assertInvalidProperty(validator, command, "followUpPrompt", "x".repeat(4_001));
                assertInvalidProperty(validator, command, "followUpStandards", "x".repeat(4_001));
                assertInvalidProperty(validator, command, "questionCount", 101);
                assertInvalidProperty(validator, command, "answerTime", 3_601);
                assertInvalidProperty(validator, command, "assessmentFiles",
                        Collections.nCopies(11, new FileBindingCmd()));
                assertInvalidProperty(validator, command, "inviteTeachers",
                        Collections.nCopies(101, 1));
                assertInvalidProperty(validator, command, "schoolClassList",
                        Collections.nCopies(101, new SchoolClassStudentSaveCmd()));
                assertInvalidProperty(validator, command, "studentIds",
                        Collections.nCopies(1_001, 1));
            }

            assertInvalidProperty(validator, SchoolClassStudentSaveCmd.class, "students",
                    Collections.nCopies(1_001, 1));
            assertInvalidProperty(validator, SchoolClassStudentSaveCmd.class, "schoolClass",
                    "x".repeat(256));
            assertInvalidProperty(validator, AppointmentSettingCreateCmd.class, "timePeriods",
                    Collections.nCopies(201, new AppointmentSettingCreateCmd.TimePeriod()));
            assertInvalidProperty(validator, AppointmentSettingCreateCmd.TimePeriod.class, "id", -1);
            assertInvalidProperty(validator, AppointmentSettingCreateCmd.TimePeriod.class, "location",
                    "x".repeat(256));
            assertInvalidProperty(validator, FileBindingCmd.class, "fileUrl", "x".repeat(1_001));
            assertInvalidProperty(validator, FileBindingCmd.class, "fileName", "x".repeat(256));
            assertInvalidProperty(validator, FileBindingCmd.class, "fileSize", "x".repeat(33));
        }
    }

    @Test
    void accountWriteCommandsRejectBlankAndDatabaseOversizedFields() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            for (Class<?> command : List.of(StudentCreateCmd.class, StudentModifyCmd.class)) {
                assertInvalidProperty(validator, command, "name", " ");
                assertInvalidProperty(validator, command, "name", "x".repeat(256));
                assertInvalidProperty(validator, command, "account", " ");
                assertInvalidProperty(validator, command, "account", "x".repeat(256));
                assertInvalidProperty(validator, command, "schoolClass", " ");
                assertInvalidProperty(validator, command, "schoolClass", "x".repeat(256));
                assertInvalidProperty(validator, command, "password", "x".repeat(73));
            }
            for (Class<?> command : List.of(TeacherCreateCmd.class, TeacherModifyCmd.class)) {
                assertInvalidProperty(validator, command, "name", " ");
                assertInvalidProperty(validator, command, "name", "x".repeat(256));
                assertInvalidProperty(validator, command, "account", " ");
                assertInvalidProperty(validator, command, "account", "x".repeat(256));
                assertInvalidProperty(validator, command, "phone", " ");
                assertInvalidProperty(validator, command, "phone", "x".repeat(33));
                assertInvalidProperty(validator, command, "password", "x".repeat(73));
            }
            assertInvalidProperty(validator, LoginDTO.class, "account", "x".repeat(256));
            assertInvalidProperty(validator, LoginDTO.class, "password", "x".repeat(256));
            assertInvalidProperty(validator, ChangePasswordDTO.class, "currentPassword", "x".repeat(256));
            assertInvalidProperty(validator, ChangePasswordDTO.class, "newPassword", "x".repeat(73));
        }
    }

    @Test
    void searchFiltersRejectOversizedValuesBeforeDatabaseQueries() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertInvalidProperty(validator, StudentPageQuery.class, "name", "x".repeat(256));
            assertInvalidProperty(validator, StudentPageQuery.class, "phone", "x".repeat(33));
            assertInvalidProperty(validator, StudentPageQuery.class, "account", "x".repeat(256));
            assertInvalidProperty(validator, StudentPageQuery.class, "schoolClass", "x".repeat(256));

            assertInvalidProperty(validator, TeacherPageQuery.class, "name", "x".repeat(256));
            assertInvalidProperty(validator, TeacherPageQuery.class, "phone", "x".repeat(33));
            assertInvalidProperty(validator, TeacherPageQuery.class, "account", "x".repeat(256));

            assertInvalidProperty(validator, AssessmentSettingPagingQuery.class, "theme", "x".repeat(501));
            assertInvalidProperty(validator, StudentDefensePagingQuery.class, "theme", "x".repeat(501));
            assertInvalidProperty(validator, StudyRecordPagingQuery.class, "studentName", "x".repeat(256));
            assertInvalidProperty(validator, StudyRecordPagingQuery.class, "theme", "x".repeat(501));
            assertInvalidProperty(validator, StudentRecordExcelQuery.class, "theme", "x".repeat(501));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertInvalidProperty(Validator validator, Class<?> type,
                                       String property, Object value) {
        assertThat(validator.validateValue((Class) type, property, value))
                .as("%s.%s should reject an oversized value", type.getSimpleName(), property)
                .isNotEmpty();
    }

    private Object read(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void write(Object target, String methodName, Integer value) {
        try {
            target.getClass().getMethod(methodName, Integer.class).invoke(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
