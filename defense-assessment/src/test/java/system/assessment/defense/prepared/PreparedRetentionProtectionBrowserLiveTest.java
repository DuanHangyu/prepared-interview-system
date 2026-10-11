package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Three genuine browser/cookie/HTTP/database rounds with network faults only.
 * Historical pointer and TTS rows are explicitly synthetic, not an implemented
 * retake flow, actual recording, cloud storage, cleanup or final Agent QA. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
@EnabledIfEnvironmentVariable(named="PREPARED_BROWSER_FIXTURES",matches="true")
class PreparedRetentionProtectionBrowserLiveTest {
    final PreparedRetentionProtectionLiveHttpTest h=new PreparedRetentionProtectionLiveHttpTest();
    @BeforeEach void setup()throws Exception{h.setup();}
    @AfterEach void cleanup()throws Exception{h.cleanup();}
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void realTeacherCanProtectHistoricalOriginalAndRecoverWithoutChangingCurrentStudentAttempt(int round)throws Exception{
        var q=h.f.f;var jdbc=q.f.jdbc;var first=q.grant(1);String second=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO prepared_attempt(id,record_id,assessment_id,student_id,attempt_no,paper_version_id,started_at) VALUES (?,?,?,?,2,?,?)",second,q.attempt.recordId(),q.f.assessment,q.student,q.attempt.paperVersionId(),java.sql.Timestamp.from(q.now.get()));
        jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",second,q.f.assessment,q.student);
        var attempts=jdbc.queryForList("SELECT * FROM prepared_attempt WHERE assessment_id=? ORDER BY attempt_no",q.f.assessment);
        var answer=jdbc.queryForMap("SELECT * FROM prepared_answer WHERE id=?",first.answerId());var anchor=jdbc.queryForMap("SELECT * FROM prepared_attempt_anchor WHERE assessment_id=? AND student_id=?",q.f.assessment,q.student);int jobs=q.count("prepared_job");
        Path root=Path.of("").toAbsolutePath().getParent(),log=Files.createTempFile(root.resolve(".runtime"),"retention-browser-"+round+"-",".log",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-retention.spec.ts","--workers=1","--retries=0");builder.directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        // Browser processes receive fixture-only login metadata, never DB/cloud keys.
        var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);var inherited=new HashMap<>(env);env.clear();for(String name:List.of("HOME","PATH","JAVA_HOME","CI","PREPARED_TEST_LOGIN_PASSWORD","PREPARED_VALIDATION_BROWSER_DESCRIPTOR","PREPARED_VALIDATION_BROWSER_RUN_ID"))if(inherited.containsKey(name))env.put(name,inherited.get(name));
        env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13017");env.put("PREPARED_RETENTION_ASSESSMENT",Integer.toString(q.f.assessment));env.put("PREPARED_RETENTION_STUDENT",Integer.toString(q.student));env.put("PREPARED_RETENTION_ATTEMPT",q.attempt.id());env.put("PREPARED_RETENTION_ACTIVE_ATTEMPT",second);
        env.put("PREPARED_RETENTION_ACCOUNT",jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,q.f.actor));env.put("PREPARED_RETENTION_ADMIN_ACCOUNT",jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,h.f.users.getLast()));
        var process=builder.start();try{assertThat(process.waitFor(120,TimeUnit.SECONDS)).as("owned retention browser completed "+log.getFileName()).isTrue();assertThat(process.exitValue()).as("real retention browser "+log.getFileName()).isZero();}finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
        assertThat(q.count("prepared_retention_command")).isEqualTo(5);assertThat(q.count("prepared_retention_guard")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT revision FROM prepared_retention_guard WHERE attempt_id=?",Long.class,q.attempt.id())).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT * FROM prepared_attempt WHERE assessment_id=? ORDER BY attempt_no",q.f.assessment)).isEqualTo(attempts);assertThat(jdbc.queryForMap("SELECT * FROM prepared_answer WHERE id=?",first.answerId())).isEqualTo(answer);assertThat(jdbc.queryForMap("SELECT * FROM prepared_attempt_anchor WHERE assessment_id=? AND student_id=?",q.f.assessment,q.student)).isEqualTo(anchor);
        assertThat(q.count("prepared_job")).isEqualTo(jobs);assertThat(q.count("prepared_submission")).isZero();assertThat(q.count("prepared_answer_chunk")).isZero();
        assertThat(h.request(h.teacher,"GET",h.path(),null).statusCode()).isEqualTo(401);h.teacher=h.login(q.f.actor,Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD")));
        assertThat(h.success(h.request(h.teacher,"GET",h.path(),null)).path("revision").asLong()).isEqualTo(5);for(var forbidden:List.of(h.student,h.stranger))assertThat(h.request(forbidden,"GET",h.path(),null).statusCode()).isEqualTo(403);
    }
}
