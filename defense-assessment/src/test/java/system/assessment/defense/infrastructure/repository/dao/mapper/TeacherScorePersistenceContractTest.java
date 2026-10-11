package system.assessment.defense.infrastructure.repository.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TeacherScorePersistenceContractTest {

    @Test
    void atomicLifecycleWritesKeepUnreviewedTeacherScoresNull() {
        String sql = Arrays.stream(StudentAssessmentRecordMapper.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Update.class)
                        || method.isAnnotationPresent(Insert.class))
                .map(TeacherScorePersistenceContractTest::sql)
                .collect(Collectors.joining("\n"))
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertThat(sql)
                .contains("check_score = null")
                .contains("'', -1, null")
                .doesNotContain("check_score = 0")
                .doesNotContain("'', -1, 0");
    }

    private static String sql(Method method) {
        Update update = method.getAnnotation(Update.class);
        if (update != null) {
            return String.join(" ", update.value());
        }
        return String.join(" ", method.getAnnotation(Insert.class).value());
    }
}
