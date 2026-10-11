package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.prepared.paper.PreparedPaper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Real page/session/MySQL/Redis/IndexedDB/native virtual capture; synthetic paper audio proofs are NOT TTS/OSS.
 * Every scenario/round owns a new student/paper and deletes only those explicitly owned fixture rows. */
@EnabledIfEnvironmentVariable(named="PREPARED_FORMAL_BROWSER_FIXTURES",matches="true")
class PreparedStudentJourneyBrowserLiveTest {
    int questionSeconds=5;
    final PreparedQuestionGrantLiveTest g=new PreparedQuestionGrantLiveTest(){
        @Override PreparedPaper.QuestionInput input(int i){var p=super.input(i);return new PreparedPaper.QuestionInput(p.questionId(),p.body(),p.maxScore(),questionSeconds,p.rubric());}
    };
    @BeforeEach void setup(TestInfo info){
        questionSeconds=info.getDisplayName().startsWith("fault-continuation ")||info.getDisplayName().startsWith("storage isolation ")?30:5;
        g.setup();
        var password=Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));
        g.f.jdbc.update("UPDATE `user` SET password=? WHERE id=?",new PasswordSecurityService().encode(password),g.student);
        // Reset only this fixture, respecting the original-attempt audit FKs.
        g.f.jdbc.update("DELETE FROM prepared_audit WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("DELETE FROM prepared_attempt_start_receipt WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("DELETE FROM prepared_attempt_anchor WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("DELETE FROM prepared_attempt WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("DELETE FROM student_assessment_record WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("UPDATE prepared_paper_gate SET first_started_at=NULL WHERE assessment_id=?",g.f.assessment);
    }
    @AfterEach void cleanup(){
        g.f.jdbc.update("DELETE FROM prepared_answer_chunk WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("DELETE FROM prepared_answer_upload_bundle WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("DELETE FROM prepared_answer_segment WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("DELETE FROM prepared_answer_end_receipt WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("DELETE FROM prepared_answer_end WHERE assessment_id=?",g.f.assessment);
        g.cleanupOnlyThisFixture();
    }
    @ParameterizedTest(name="{0} round {1}") @CsvSource({"full,1","full,2","full,3","response-loss,1","response-loss,2","response-loss,3","deadline,1","deadline,2","deadline,3","upload-recovery,1","upload-recovery,2","upload-recovery,3","broadcast-failure,1","broadcast-failure,2","broadcast-failure,3"})
    void originalJourneyIsRepeatedInTheRealBrowser(String scenario,int round) throws Exception {
        runBrowser(scenario,round,0);
    }
    /** Keep the original three rounds and every browser assertion; expose a
     * targeted reproducer without changing the full-suite coverage or limits. */
    @ParameterizedTest(name="fault-continuation round {0}") @ValueSource(ints={1,2,3})
    void faultContinuationIsRepeatedInTheRealBrowser(int round) throws Exception {
        runBrowser("fault-continuation",round,0);
    }
    /** A runner-only slow bootstrap must neither consume the question workflow's
     * timeout nor extend the actual five-second authorized recording deadline. */
    @ParameterizedTest(name="slow bootstrap round {0}") @ValueSource(ints={1,2,3})
    void slowBrowserBootstrapDoesNotConsumeOriginalQuestionTime(int round) throws Exception {
        runBrowser("deadline",round+3,20000);
    }
    @RepeatedTest(value=3,name="storage isolation round {currentRepetition}")
    void unrelatedStorageDoesNotInterruptOriginalCapture(RepetitionInfo repetition) throws Exception {
        runBrowser("storage-isolation",repetition.getCurrentRepetition(),0);
    }
    private void runBrowser(String scenario,int round,int bootstrapDelay) throws Exception {
        Path root=Path.of("").toAbsolutePath().getParent();
        assertThat(root.getFileName().toString()).isEqualTo("prepared-interview-system");
        Path log=root.resolve(".runtime/student-journey-browser-"+scenario+"-"+round+".log");
        var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-student-journey.spec.ts","--grep",scenario+":","--workers=1","--retries=0");
        builder.directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13015");
        env.put("PREPARED_FORMAL_ASSESSMENT_ID",Integer.toString(g.f.assessment));
        env.put("PREPARED_FORMAL_STUDENT_ACCOUNT",g.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,g.student));
        env.put("PREPARED_FORMAL_STUDENT_ID",Integer.toString(g.student));env.put("PREPARED_FORMAL_TEST_PORT","13016");
        env.put("PREPARED_FORMAL_BOOTSTRAP_DELAY_MS",Integer.toString(bootstrapDelay));
        var process=builder.start();
        try{assertThat(process.waitFor(90,TimeUnit.SECONDS)).as("browser scenario finished; inspect its scoped local artifact").isTrue();
            assertThat(process.exitValue()).as("browser scenario passed; inspect "+log.getFileName()).isZero();}
        finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
        int answers=g.count("prepared_answer"),ends=g.count("prepared_answer_end");
        assertThat(g.count("prepared_attempt")).isEqualTo(1);assertThat(g.count("prepared_attempt_anchor")).isEqualTo(1);
        assertThat(answers).isEqualTo(scenario.equals("full")?3:scenario.equals("upload-recovery")?2:1);
        assertThat(ends).isEqualTo(scenario.equals("storage-isolation")?0:answers);
        assertThat(g.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_answer WHERE assessment_id=? AND state='SAVED'",Integer.class,g.f.assessment)).isZero();
    }
}
