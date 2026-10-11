package system.assessment.defense.security;

import org.junit.jupiter.api.Test;
import system.assessment.defense.application.dto.StudentDTO;
import system.assessment.defense.application.dto.StudentOptionDTO;
import system.assessment.defense.application.dto.TeacherDTO;
import system.assessment.defense.application.dto.TeacherOptionDTO;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDtoContractTest {

    @Test
    void responseDtosDoNotExposePasswords() {
        assertNoPasswordField(StudentDTO.class);
        assertNoPasswordField(TeacherDTO.class);
    }

    @Test
    void classSelectionOptionsExposeOnlyRequiredIdentityFields() {
        assertThat(Arrays.stream(StudentOptionDTO.class.getDeclaredFields()).map(Field::getName))
                .containsExactlyInAnyOrder("id", "name", "schoolClass");
    }

    @Test
    void teacherInvitationOptionsExposeOnlyRequiredIdentityFields() {
        assertThat(Arrays.stream(TeacherOptionDTO.class.getDeclaredFields()).map(Field::getName))
                .containsExactlyInAnyOrder("id", "name");
    }

    @Test
    void studentExportUsesAPasswordFreeContract() throws Exception {
        Class<?> exportType = Class.forName(
                "system.assessment.defense.application.dto.StudentExportExcelDTO");
        assertNoPasswordField(exportType);
    }

    private void assertNoPasswordField(Class<?> type) {
        assertThat(Arrays.stream(type.getDeclaredFields()).map(Field::getName))
                .as("%s must never serialize password data", type.getSimpleName())
                .noneMatch(name -> name.toLowerCase().contains("password"));
    }
}
