package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import system.assessment.defense.application.service.PasswordSecurityService;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Actual teacher creation, allocation, draft and role/list HTTP through the browser.
 * No publication, supplier, recording or complete M01 acceptance claim. */
@EnabledIfEnvironmentVariable(named="PREPARED_BROWSER_FIXTURES",matches="true")
class PreparedAssessmentCreationBrowserLiveTest {
    private record Account(int id, String account, int role) {}
    private final Map<String,Account> accounts = new LinkedHashMap<>();
    private JdbcTemplate jdbc;
    private String theme, schoolClass;
    private int created;

    @BeforeEach void setup() {
        var db = PreparedLiveDatabase.checked();
        assertThat(db.pid()).as("creation writes require a disposable owned native database").isPositive();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(db.url(), db.username(), db.password()));
        jdbc.setQueryTimeout(5);
        String scope = UUID.randomUUID().toString();
        theme = "预置创建分配联调-" + scope;
        schoolClass = "创建分配班-" + scope;
        String password = Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));
        String hash = new PasswordSecurityService().encode(password);
        for (var role : Map.of("teacher",1,"invited",1,"unrelated",1,"student",0,"otherStudent",0,"admin",2).entrySet()) {
            String account = "prepared-create-" + role.getKey() + "-" + scope;
            String name = role.getKey().equals("student") ? schoolClass : account;
            String assignedClass = role.getKey().equals("student") ? schoolClass : null;
            jdbc.update("INSERT INTO `user`(name,account,password,type,school_class) VALUES (?,?,?,?,?)", name, account, hash, role.getValue(), assignedClass);
            int id = Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM `user` WHERE account=?", Integer.class, account));
            accounts.put(role.getKey(), new Account(id, account, role.getValue()));
        }
    }

    @AfterEach void cleanupOnlyOwnedFixture() {
        if (jdbc == null || !accounts.containsKey("teacher")) return;
        var ids = jdbc.queryForList("SELECT id FROM assessment WHERE create_id=? AND theme=?", Integer.class,
                accounts.get("teacher").id(), theme);
        for (int id : ids) {
            // No fixture teardown may delete an unexpected started/published interview.
            for (String table : List.of("prepared_attempt", "prepared_paper_version", "prepared_job"))
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE assessment_id=?", Integer.class, id)).isZero();
            jdbc.update("DELETE FROM prepared_audit WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_question_review WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_question_draft WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_paper_draft WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_paper_gate WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM assessment_school_class WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=?", id);
            assertThat(jdbc.update("DELETE FROM assessment WHERE id=? AND create_id=? AND theme=?", id, accounts.get("teacher").id(), theme)).isEqualTo(1);
        }
        for (var account : accounts.values())
            assertThat(jdbc.update("DELETE FROM `user` WHERE id=? AND account=? AND type=?", account.id(), account.account(), account.role())).isEqualTo(1);
    }

    @RepeatedTest(3)
    void realTeacherCreatesAndAllocatesThenEachRoleReadsOnlyItsAllowedResources(RepetitionInfo repetition) throws Exception {
        Path root = Path.of("..").toRealPath();
        assertThat(root.getFileName().toString()).isEqualTo("prepared-interview-system");
        Path log = root.resolve(".runtime/assessment-creation-browser-" + UUID.randomUUID() + ".log");
        var builder = new ProcessBuilder("npx", "playwright", "test", "tests/e2e/live-prepared-assessment-creation.spec.ts", "--workers=1", "--retries=0");
        builder.directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env = builder.environment(); PreparedLiveDatabase.browserEnvironment(env);
        env.put("LIVE_E2E", "1"); env.put("E2E_BASE_URL", "http://127.0.0.1:13015");
        env.put("PREPARED_CREATION_THEME", theme); env.put("PREPARED_CREATION_CLASS", schoolClass);
        env.put("PREPARED_CREATION_ROUND", Integer.toString(repetition.getCurrentRepetition()));
        for (var role : accounts.entrySet()) {
            env.put("PREPARED_CREATION_" + role.getKey().toUpperCase(Locale.ROOT) + "_ACCOUNT", role.getValue().account());
            env.put("PREPARED_CREATION_" + role.getKey().toUpperCase(Locale.ROOT) + "_ID", Integer.toString(role.getValue().id()));
        }
        var process = builder.start();
        try {
            assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("creation browser finished; inspect scoped artifact").isTrue();
            assertThat(process.exitValue()).as("creation browser passed; inspect " + log.getFileName()).isZero();
        } finally {
            if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroy); process.destroy(); }
        }
        var ids = jdbc.queryForList("SELECT id FROM assessment WHERE theme=? AND create_id=?", Integer.class, theme, accounts.get("teacher").id());
        assertThat(ids).hasSize(1); created = ids.getFirst();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM assessment WHERE id=? AND defense=0 AND question=1 AND follow_up=0
                  AND question_count=3 AND total_score=30 AND pass_score=18 AND need_appoint=0 AND duration IS NULL
                """, Integer.class, created)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT student_id FROM assessment_student_relation WHERE assessment_id=?", Integer.class, created))
                .containsExactly(accounts.get("student").id());
        assertThat(jdbc.queryForList("SELECT invitee_teacher_id FROM assessment_invite WHERE assessment_id=?", Integer.class, created))
                .containsExactly(accounts.get("invited").id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_question_draft WHERE assessment_id=?", Integer.class, created)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_question_draft WHERE assessment_id=? AND approved=1", Integer.class, created)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_paper_version WHERE assessment_id=?", Integer.class, created)).isZero();
    }
}
