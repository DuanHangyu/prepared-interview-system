package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind;

/** Three actual login/HTTP/DB/browser rounds in both audio exit states. Native
 * media, grades and lifecycle states are fixtures, not physical/cloud/deletion QA. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
@EnabledIfEnvironmentVariable(named="PREPARED_BROWSER_FIXTURES",matches="true")
class PreparedAudioRetirementHttpLiveTest {
    final PreparedReviewLiveHttpTest h=new PreparedReviewLiveHttpTest();@TempDir Path folder;
    @BeforeEach void setup()throws Exception {h.folder=folder;h.setup();}
    @AfterEach void cleanup()throws Exception {h.f.jdbc.update("DELETE FROM prepared_retention_guard WHERE assessment_id=?",h.f.assessment);h.cleanup();}
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void exitedAudioHasExplicitHttpAndUiStateWhileOriginalGradesRemainReadable(int round)throws Exception {
        for(int n=0;n<3;n++){h.f.transcribe(h.f.claim(Kind.TRANSCRIBE));h.f.score(h.f.claim(Kind.SCORE),"8.5");}
        String answer=h.f.jdbc.queryForObject("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal LIMIT 1",String.class,h.f.assessment),path=h.answer(answer);
        var original=h.success(h.request(h.teacher,"GET",path,null));var submission=h.f.f.status();var results=h.f.jdbc.queryForList("SELECT * FROM prepared_processing_result WHERE assessment_id=? ORDER BY id",h.f.assessment);
        for(String state:List.of("RETIRING","RETIRED")){
            h.f.jdbc.update("""
                INSERT INTO prepared_retention_guard(attempt_id,assessment_id,student_id,audio_state,created_at,updated_at)
                VALUES (?,?,?,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE audio_state=VALUES(audio_state),revision=revision+1,updated_at=CURRENT_TIMESTAMP(3)
                """,h.f.attempt,h.f.assessment,h.f.student,state);
            for(var permitted:List.of(h.teacher,h.admin)){
                var denied=h.request(permitted,"GET",path+"/audio",null);assertThat(denied.statusCode()).isEqualTo(state.equals("RETIRED")?410:409);
                assertThat(denied.headers().firstValue("Cache-Control").orElse("")).contains("no-store");assertThat(denied.body()).doesNotContain("Signature","objectKey","url");
            }
            for(var forbidden:List.of(h.student,h.unrelated))assertThat(h.request(forbidden,"GET",path+"/audio",null).statusCode()).isEqualTo(403);
            assertThat(h.success(h.request(h.teacher,"GET",path,null))).isEqualTo(original);
            Path root=Path.of("").toAbsolutePath().getParent(),log=Files.createTempFile(root.resolve(".runtime"),"audio-exit-browser-"+round+"-",".log",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-audio-retirement.spec.ts","--workers=1","--retries=0");builder.directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);var inherited=new HashMap<>(env);env.clear();for(String name:List.of("HOME","PATH","JAVA_HOME","CI","PREPARED_TEST_LOGIN_PASSWORD","PREPARED_VALIDATION_BROWSER_DESCRIPTOR","PREPARED_VALIDATION_BROWSER_RUN_ID"))if(inherited.containsKey(name))env.put(name,inherited.get(name));
            env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13017");env.put("PREPARED_AUDIO_EXIT_STATE",state);env.put("PREPARED_AUDIO_EXIT_ASSESSMENT",Integer.toString(h.f.assessment));env.put("PREPARED_AUDIO_EXIT_ANSWER",answer);
            env.put("PREPARED_AUDIO_EXIT_ACCOUNT",h.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,h.f.actor));
            var process=builder.start();try{assertThat(process.waitFor(120,TimeUnit.SECONDS)).as("owned audio-exit browser completed "+log.getFileName()).isTrue();assertThat(process.exitValue()).as("actual audio-exit browser "+log.getFileName()).isZero();}finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
            // Browser login replaces this teacher's cookie. Fresh authorization is
            // required for the next state, not an invented cached permission.
            h.teacher=h.login(h.f.actor,Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD")));
        }
        assertThat(h.f.f.status()).isEqualTo(submission);assertThat(h.f.jdbc.queryForList("SELECT * FROM prepared_processing_result WHERE assessment_id=? ORDER BY id",h.f.assessment)).isEqualTo(results);
        assertThat(h.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_AUDIO_AUTHORIZED'",Integer.class,h.f.assessment)).isZero();
    }
}
