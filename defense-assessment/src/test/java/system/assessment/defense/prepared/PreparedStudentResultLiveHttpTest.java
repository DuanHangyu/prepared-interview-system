package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.prepared.processing.PreparedProcessing.AdjustCommand;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Real cookie auth/HTTP/independent DB. Upstream original-media storage is an explicit SDK
 * double. Human grading needs no model call; this is not cloud/device/final-QA acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedStudentResultLiveHttpTest {
    final PreparedReviewLiveHttpTest f=new PreparedReviewLiveHttpTest();@TempDir Path folder;HttpClient otherStudent;int otherStudentId;
    @BeforeEach void setup()throws Exception{
        f.folder=folder;f.setup();assertThat(f.base).isEqualTo("http://127.0.0.1:13016");f.f.jdbc.update("UPDATE assessment SET show_result=1 WHERE id=?",f.f.assessment);
        String account="prepared-result-access-"+UUID.randomUUID(),password=Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));
        f.f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('独立结果权限验证',?,?,0)",account,new system.assessment.defense.application.service.PasswordSecurityService().encode(password));
        otherStudentId=f.f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);f.f.extraUsers.add(otherStudentId);otherStudent=f.login(otherStudentId,password);
    }
    @AfterEach void cleanup()throws Exception{if(otherStudent!=null)f.request(otherStudent,"POST","/logout",Map.of());if(otherStudentId>0)f.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",f.f.assessment,otherStudentId);f.cleanup();}
    String root(){return "/backend/prepared-results/"+f.f.assessment;}
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void originalCurrentStudentResultIsRepeatableHiddenAndVersionBoundWithoutAnyPaidCall(int round)throws Exception{
        assertThat(f.request(HttpClient.newHttpClient(),"GET",root(),null).statusCode()).isEqualTo(401);
        for(var client:List.of(f.teacher,f.unrelated,f.admin))assertThat(f.request(client,"GET",root(),null).statusCode()).isEqualTo(403);
        var pending=f.success(f.request(f.student,"GET",root(),null));assertThat(pending.path("state").asText()).isEqualTo("PROCESSING");assertThat(pending.path("totalScore").isNull()).isTrue();assertThat(pending.path("answers").size()).isZero();
        var answers=f.f.jdbc.queryForList("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal",String.class,f.f.assessment);
        int index=0;for(String value:List.of("0","7.25","8.5"))f.success(f.request(f.teacher,"POST",f.answer(answers.get(index++))+"/adjust",new AdjustCommand(UUID.randomUUID().toString(),0,new BigDecimal(value),"教师内部评分理由，不返回学生")));
        var original=f.success(f.request(f.student,"GET",root(),null));assertThat(original.path("state").asText()).isEqualTo("READY");assertThat(original.path("totalScore").decimalValue()).isEqualByComparingTo("15.75");assertThat(original.path("answers").size()).isEqualTo(3);
        String version=original.path("resultVersion").asText(),answer=answers.getFirst(),detail=root()+"/answers/"+answer+"?resultVersion="+version;
        assertThat(f.request(otherStudent,"GET",root(),null).statusCode()).isEqualTo(403);assertThat(f.request(otherStudent,"GET",detail,null).statusCode()).isEqualTo(403);
        f.f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",f.f.assessment,otherStudentId);
        var ownEmpty=f.success(f.request(otherStudent,"GET",root(),null));assertThat(ownEmpty.path("state").asText()).isEqualTo("NOT_STARTED");assertThat(ownEmpty.path("answers")).isEmpty();assertThat(ownEmpty.path("totalScore").isNull()).isTrue();assertThat(f.request(otherStudent,"GET",detail,null).statusCode()).isEqualTo(409);
        f.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",f.f.assessment,otherStudentId);assertThat(f.request(otherStudent,"GET",root(),null).statusCode()).isEqualTo(403);
        var result=f.success(f.request(f.student,"GET",detail,null));assertThat(result.path("score").decimalValue()).isEqualByComparingTo("0");assertThat(result.path("transcript").isNull()).isTrue();assertThat(result.path("scoringSource").asText()).isEqualTo("TEACHER");
        assertThat(result.toString()).doesNotContain("rubric","教师内部评分理由","objectKey","provider","jobId","inputJson");
        assertThat(f.request(f.student,"POST",root(),Map.of()).statusCode()).isEqualTo(405);
        assertThat(f.request(f.student,"GET",root()+"/answers/bad?resultVersion="+version,null).statusCode()).isEqualTo(400);
        assertThat(f.request(f.student,"GET",root()+"/answers/"+UUID.randomUUID()+"?resultVersion="+version,null).statusCode()).isEqualTo(403);
        assertThat(f.request(f.student,"GET",root()+"/answers/"+answer+"?resultVersion=bad",null).statusCode()).isEqualTo(400);
        var repo=Path.of("").toAbsolutePath().getParent();var log=repo.resolve(".runtime/student-result-browser-"+round+".log");
        var browser=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-student-result.spec.ts","--workers=1","--retries=0").directory(repo.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=browser.environment();PreparedLiveDatabase.browserEnvironment(env);env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13017");env.put("PREPARED_RESULT_ASSESSMENT",Integer.toString(f.f.assessment));
        env.put("PREPARED_RESULT_ACCOUNT",f.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,f.f.student));
        var process=browser.start();try{assertThat(process.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as("scoped student results browser round %s",round).isZero();}finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
        // One active login per account is intentional. The real browser has replaced
        // this student's earlier Java client session; never weaken session protection.
        assertThat(f.request(f.student,"GET",root(),null).statusCode()).isEqualTo(401);
        f.student=f.login(f.f.student,Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD")));
        f.success(f.request(f.teacher,"POST",f.answer(answer)+"/adjust",new AdjustCommand(UUID.randomUUID().toString(),1,new BigDecimal("1.5"),"重新人工核验，不覆盖原版本")));
        assertThat(f.request(f.student,"GET",detail,null).statusCode()).isEqualTo(409);
        var current=f.success(f.request(f.student,"GET",root(),null));assertThat(current.path("totalScore").decimalValue()).isEqualByComparingTo("17.25");
        f.f.jdbc.update("UPDATE assessment SET show_result=0 WHERE id=?",f.f.assessment);var hidden=f.success(f.request(f.student,"GET",root(),null));assertThat(hidden.path("state").asText()).isEqualTo("HIDDEN");assertThat(hidden.path("answers").size()).isZero();assertThat(hidden.path("totalScore").isNull()).isTrue();assertThat(f.request(f.student,"GET",detail,null).statusCode()).isEqualTo(403);
        f.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.f.student);try{assertThat(f.request(f.student,"GET",root(),null).statusCode()).isEqualTo(403);}finally{f.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.f.student);}
        assertThat(f.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_model_call c JOIN prepared_job j ON j.id=c.job_id WHERE j.assessment_id=?",Integer.class,f.f.assessment)).isZero();
        assertThat(f.f.f.status().submitted()).isTrue();
    }
}
