package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.prepared.processing.PreparedProcessing;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Actual scoped cookies, HTTP, MySQL/Redis and browser. Original media and grades
 * are explicit synthetic fixtures; this does NOT accept cloud, devices or retakes. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedReviewSummaryLiveHttpTest {
    final PreparedReviewLiveHttpTest http=PreparedReviewLiveHttpTest.forSummary();@TempDir Path folder;
    @BeforeEach void setup()throws Exception{http.folder=folder;http.setup();}
    @AfterEach void cleanup()throws Exception{http.cleanup();}
    String summary(){return http.interview()+"/summary";}
    List<String> answers(){return http.f.jdbc.queryForList("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal",String.class,http.f.assessment);}
    void human()throws Exception{int n=0;for(String id:answers())http.success(http.request(http.teacher,"POST",http.answer(id)+"/adjust",new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),0,new BigDecimal(List.of("0","7.25","8.5").get(n++)),"真实 HTTP 人工核验测试依据")));}
    @Test void actualSummaryRequiresFreshManagerScopeAndNeverPublishesOrInventsGrades()throws Exception{
        assertThat(http.request(HttpClient.newHttpClient(),"GET",summary(),null).statusCode()).isEqualTo(401);
        for(var client:List.of(http.student,http.unrelated))assertThat(http.request(client,"GET",summary(),null).statusCode()).isEqualTo(403);
        var pending=http.success(http.request(http.teacher,"GET",summary(),null));assertThat(pending.path("state").asText()).isEqualTo("PROCESSING");assertThat(pending.path("totalScore").isNull()).isTrue();assertThat(pending.path("answers")).isEmpty();
        human();http.f.jdbc.update("UPDATE assessment SET show_result=0 WHERE id=?",http.f.assessment);
        for(var client:List.of(http.teacher,http.admin)){var result=http.success(http.request(client,"GET",summary(),null));assertThat(result.path("totalScore").decimalValue()).isEqualByComparingTo("15.75");assertThat(result.path("totalMaxScore").asInt()).isEqualTo(30);assertThat(result.path("showResult").asBoolean()).isFalse();assertThat(result.path("answers").get(0).path("score").decimalValue()).isEqualByComparingTo("0");assertThat(result.toString()).doesNotContain("真实 HTTP 人工核验测试依据","rubric","transcript","objectKey","provider","inputJson");}
        String studentResult="/backend/prepared-results/"+http.f.assessment;
        var hidden=http.success(http.request(http.student,"GET",studentResult,null));assertThat(hidden.path("state").asText()).isEqualTo("HIDDEN");assertThat(hidden.path("totalScore").isNull()).isTrue();assertThat(hidden.path("resultVersion").isNull()).isTrue();assertThat(hidden.path("answers")).isEmpty();
        assertThat(http.request(http.student,"GET",studentResult+"/answers/"+answers().getFirst()+"?resultVersion="+"a".repeat(64),null).statusCode()).isEqualTo(403);
        int invited=http.f.extraUsers.getFirst();http.f.jdbc.update("INSERT INTO assessment_invite(assessment_id,invitee_teacher_id,inviter_teacher_id) VALUES (?,?,?)",http.f.assessment,invited,http.f.actor);
        try{http.success(http.request(http.unrelated,"GET",summary(),null));}finally{http.f.jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=? AND invitee_teacher_id=?",http.f.assessment,invited);}
        assertThat(http.request(http.unrelated,"GET",summary(),null).statusCode()).isEqualTo(403);
        http.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",http.f.actor);try{assertThat(http.request(http.teacher,"GET",summary(),null).statusCode()).isEqualTo(403);}finally{http.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",http.f.actor);}
        http.f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",http.f.assessment);try{assertThat(http.request(http.teacher,"GET",summary(),null).statusCode()).isEqualTo(403);}finally{http.f.jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",http.f.assessment);}
        assertThat(http.request(http.teacher,"GET",summary().replace(http.f.attempt,UUID.randomUUID().toString()),null).statusCode()).isEqualTo(403);
        assertThat(http.request(http.teacher,"POST",summary(),Map.of()).statusCode()).isEqualTo(405);
        assertThat(http.f.count("prepared_manual_score")).isEqualTo(3);assertThat(http.f.count("prepared_result_command")).isEqualTo(3);assertThat(http.f.count("prepared_processing_result")).isZero();
        String hash=http.f.jdbc.queryForObject("SELECT snapshot_sha256 FROM prepared_submission WHERE attempt_id=?",String.class,http.f.attempt);
        http.f.jdbc.update("UPDATE prepared_submission SET snapshot_sha256=REPEAT('f',64) WHERE attempt_id=?",http.f.attempt);
        try{var failed=http.request(http.teacher,"GET",summary(),null);assertThat(failed.statusCode()).isEqualTo(500);assertThat(failed.body()).doesNotContain("snapshot_sha256","objectKey","StackTrace","真实 HTTP 人工核验测试依据");}finally{http.f.jdbc.update("UPDATE prepared_submission SET snapshot_sha256=? WHERE attempt_id=?",hash,http.f.attempt);}
        assertThat(http.success(http.request(http.teacher,"GET",summary(),null)).path("state").asText()).isEqualTo("READY");
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void actualCurrentAndHistoricalSummariesRepeatThroughRefreshAndTransportRecovery(int round)throws Exception{
        human();String submission=http.f.jdbc.queryForObject("SELECT id FROM prepared_submission WHERE attempt_id=?",String.class,http.f.attempt);
        String completed=http.f.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,http.f.attempt).toInstant().toString();int jobs=http.f.count("prepared_job");
        browser(round,"current",null);
        assertThat(http.f.service.manualHistory(http.f.actor,http.f.assessment,http.f.attempt,answers().getFirst(),null,10).current().score()).isEqualByComparingTo("1.25");
        String active=UUID.randomUUID().toString();http.f.jdbc.update("INSERT INTO prepared_attempt(id,record_id,assessment_id,student_id,attempt_no,paper_version_id,started_at) SELECT ?,record_id,assessment_id,student_id,2,paper_version_id,CURRENT_TIMESTAMP(3) FROM prepared_attempt WHERE id=?",active,http.f.attempt);http.historicalFixtures.add(active);http.f.jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",active,http.f.assessment,http.f.student);
        // Browser login replaces this account's original cookie; reacquire actual auth.
        http.teacher=http.login(http.f.actor,System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));
        var old=http.success(http.request(http.teacher,"GET",summary(),null));assertThat(old.path("current").asBoolean()).isFalse();assertThat(old.path("totalScore").decimalValue()).isEqualByComparingTo("17");assertThat(old.path("completedAt").asText()).isEqualTo(completed);
        var current=http.success(http.request(http.teacher,"GET",summary().replace(http.f.attempt,active),null));assertThat(current.path("state").asText()).isEqualTo("IN_PROGRESS");assertThat(current.path("totalScore").isNull()).isTrue();
        browser(round,"historical",active);
        assertThat(http.f.count("prepared_manual_score")).isEqualTo(4);assertThat(http.f.count("prepared_result_command")).isEqualTo(4);assertThat(http.f.count("prepared_job")).isEqualTo(jobs);assertThat(http.f.count("prepared_attempt")).isEqualTo(2);
        assertThat(http.f.jdbc.queryForObject("SELECT id FROM prepared_submission WHERE attempt_id=?",String.class,http.f.attempt)).isEqualTo(submission);assertThat(http.f.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,http.f.attempt).toInstant().toString()).isEqualTo(completed);
    }
    void browser(int round,String mode,String active)throws Exception{
        Path root=Path.of("").toAbsolutePath().getParent(),log=root.resolve(".runtime/review-summary-browser-"+mode+"-"+round+".log");
        var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-review-summary.spec.ts","--grep",mode,"--workers=1","--retries=0").directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13019");env.put("PREPARED_REVIEW_ASSESSMENT",Integer.toString(http.f.assessment));env.put("PREPARED_REVIEW_ACCOUNT",http.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,http.f.actor));env.put("PREPARED_REVIEW_OLD_ATTEMPT",http.f.attempt);env.put("PREPARED_REVIEW_ANSWER",answers().getFirst());if(active!=null)env.put("PREPARED_REVIEW_ACTIVE_ATTEMPT",active);
        var process=builder.start();try{assertThat(process.waitFor(60,TimeUnit.SECONDS)).as("bounded summary browser "+mode+" round "+round).isTrue();assertThat(process.exitValue()).as("summary browser "+mode+" round "+round).isZero();}finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
    }
}
