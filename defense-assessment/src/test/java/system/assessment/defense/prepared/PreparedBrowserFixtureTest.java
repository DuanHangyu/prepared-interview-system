package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import system.assessment.defense.application.service.PasswordSecurityService;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** Creates only named, non-personal browser fixtures in the dedicated loopback test database. */
@EnabledIfEnvironmentVariable(named = "PREPARED_BROWSER_FIXTURES", matches = "true")
class PreparedBrowserFixtureTest {
    @Test void provisionIsolatedBrowserFixtures() {
        provisionForScope(PreparedBrowserFixtureScope.namespace());
    }

    void provisionForScope(String namespace) {
        String password = required("PREPARED_TEST_LOGIN_PASSWORD");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                PreparedLiveDatabase.jdbcUrl(),
                required("PREPARED_DB_USERNAME"), required("PREPARED_DB_PASSWORD")));
        jdbc.setQueryTimeout(5);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("prepared_interview");
        var passwords = new PasswordSecurityService();
        String hash = passwords.encode(password);
        int teacher = provisionUser(jdbc, passwords, password, hash, PreparedBrowserFixtureScope.account("teacher",namespace), 1);
        int student = provisionUser(jdbc, passwords, password, hash, PreparedBrowserFixtureScope.account("student",namespace), 0);
        int unrelated = provisionUser(jdbc, passwords, password, hash, PreparedBrowserFixtureScope.account("unrelated",namespace), 1);
        String theme = "预置题目 · 独立浏览器联调";
        var existing = jdbc.queryForList("SELECT id FROM assessment WHERE theme=? AND create_id=?", Integer.class, theme, teacher);
        if (existing.isEmpty()) {
            jdbc.update("""
                    INSERT INTO assessment(theme,assessment_criteria,total_score,pass_score,question_count,answer_time,
                      follow_up,defense,question,show_result,create_id,need_appoint,deleted)
                    VALUES (?,'浏览器联调考核标准',30,18,3,120,0,0,1,0,?,0,0)
                    """, theme, teacher);
        }
        int assessment = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM assessment WHERE theme=? AND create_id=?", Integer.class, theme, teacher));
        jdbc.update("INSERT IGNORE INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)", assessment, student);
        PreparedBrowserFixtureScope.record(namespace,teacher,student,unrelated,assessment);
        System.out.println("PREPARED_BROWSER_ASSESSMENT_ID=" + assessment);
    }

    private int provisionUser(JdbcTemplate jdbc, PasswordSecurityService passwords, String password, String hash, String account, int role) {
        var existing = jdbc.queryForList("SELECT id FROM `user` WHERE account=?", Integer.class, account);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('预置系统浏览器测试账号',?,?,?)", account, hash, role);
        } else {
            assertThat(jdbc.queryForObject("SELECT type FROM `user` WHERE account=?", Integer.class, account)).isEqualTo(role);
            assertThat(passwords.verify(password, jdbc.queryForObject("SELECT password FROM `user` WHERE account=?", String.class, account)).matched()).isTrue();
        }
        return Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM `user` WHERE account=?", Integer.class, account));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required for browser fixtures");
        return value;
    }
}
