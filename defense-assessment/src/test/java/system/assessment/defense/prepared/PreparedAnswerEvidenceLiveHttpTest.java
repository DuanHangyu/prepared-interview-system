package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.application.service.PasswordSecurityService;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Real cookie roles/HTTP/SQL and teacher browser reads. End metadata and frozen
 * audio assets are explicit synthetic fixtures, NOT cloud receipt or physical audio QA. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAnswerEvidenceLiveHttpTest {
    final PreparedAnswerEvidenceLiveTest f=new PreparedAnswerEvidenceLiveTest();final ObjectMapper json=new ObjectMapper();
    HttpClient teacher,student,unrelated,admin;
    final String base="http://127.0.0.1:"+System.getenv().getOrDefault("PREPARED_FORMAL_HTTP_PORT","13016");
    @BeforeEach void setup()throws Exception{
        f.setup();var q=f.f.q;var password=Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));var encoder=new PasswordSecurityService();
        q.f.jdbc.update("UPDATE `user` SET password=? WHERE id IN (?,?)",encoder.encode(password),q.f.actor,q.student);teacher=login(q.f.actor,password);student=login(q.student,password);
        for(int role:new int[]{1,2}){int id=f.user(role);q.f.jdbc.update("UPDATE `user` SET password=? WHERE id=?",encoder.encode(password),id);if(role==1)unrelated=login(id,password);else admin=login(id,password);}
    }
    @AfterEach void cleanup()throws Exception{
        for(var c:Arrays.asList(teacher,student,unrelated,admin))if(c!=null)request(c,"POST","/logout",Map.of());f.cleanup();
    }
    String path(){var q=f.f.q;return "/backend/prepared-reviews/"+q.f.assessment+"/attempts/"+q.attempt.id()+"/answers/"+f.answer+"/evidence";}
    HttpClient login(int id,String password)throws Exception{
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
        String account=f.f.q.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,id);success(request(client,"POST","/login",Map.of("account",account,"password",password)));return client;
    }
    HttpResponse<String> request(HttpClient client,String method,String path,Object input)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("Origin","http://127.0.0.1:13015").header("Content-Type","application/json");
        return client.send(b.method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode success(HttpResponse<String> response)throws Exception{
        assertThat(response.statusCode()).as("actual scoped declaration evidence HTTP: %s",response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");var b=json.readTree(response.body());assertThat(b.path("code").asInt()).isEqualTo(200);return b.path("data");
    }
    @Test void actualCookieRolesInvitationRevocationAndBoundedParametersProtectOriginalEvidence()throws Exception{
        f.close(f.interrupted(2));var q=f.f.q;
        assertThat(request(HttpClient.newHttpClient(),"GET",path(),null).statusCode()).isEqualTo(401);
        for(var c:List.of(student,unrelated))assertThat(request(c,"GET",path(),null).statusCode()).isEqualTo(403);
        q.f.jdbc.update("INSERT INTO assessment_invite(assessment_id,invitee_teacher_id,inviter_teacher_id) VALUES (?,?,?)",q.f.assessment,f.users.getFirst(),q.f.actor);
        assertThat(success(request(unrelated,"GET",path(),null)).path("actorId").asInt()).isEqualTo(f.users.getFirst());
        q.f.jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=?",q.f.assessment);assertThat(request(unrelated,"GET",path(),null).statusCode()).isEqualTo(403);
        assertThat(success(request(admin,"GET",path(),null)).path("actorId").asInt()).isEqualTo(f.users.getLast());
        for(String suffix:List.of("?size=0","?size=51","?afterSegmentOrdinal=-1","?afterSegmentOrdinal=1001","?size=1.5"))assertThat(request(teacher,"GET",path()+suffix,null).statusCode()).isEqualTo(400);
        assertThat(request(teacher,"GET",path()+"?afterSegmentOrdinal=3",null).statusCode()).isEqualTo(409);
        assertThat(request(teacher,"GET",path().replace(f.answer,UUID.randomUUID().toString()),null).statusCode()).isEqualTo(403);
        assertThat(request(teacher,"GET",path().replace(q.attempt.id(),UUID.randomUUID().toString()),null).statusCode()).isEqualTo(403);
        assertThat(request(teacher,"GET",path().replace(f.answer,"bad"),null).statusCode()).isEqualTo(400);
        assertThat(request(teacher,"POST",path(),Map.of("continue",true)).statusCode()).isEqualTo(405);
        q.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",q.f.actor);try{assertThat(request(teacher,"GET",path(),null).statusCode()).isEqualTo(403);}finally{q.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",q.f.actor);}
        q.f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",q.f.assessment);try{assertThat(request(teacher,"GET",path(),null).statusCode()).isEqualTo(403);}finally{q.f.jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",q.f.assessment);}
        assertThat(f.f.count("prepared_submission")).isZero();assertThat(f.f.count("prepared_supplement_grant")).isZero();
    }
    @Test void undeclaredAndCorruptedEvidenceNeverReturnACompleteMediaClaim()throws Exception{
        var before=success(request(teacher,"GET",path(),null));assertThat(before.path("declaration").isNull()).isTrue();assertThat(before.path("segments").size()).isZero();
        f.close(f.interrupted(1));var original=success(request(teacher,"GET",path(),null));assertThat(original.path("declaration").path("reviewReasons").get(0).asText()).isEqualTo("CAPTURE_INTERRUPTED");
        f.f.q.f.jdbc.update("UPDATE prepared_answer_end SET manifest_sha256=? WHERE answer_id=?","c".repeat(64),f.answer);
        var rejected=request(teacher,"GET",path(),null);assertThat(rejected.statusCode()).isEqualTo(500);assertThat(rejected.body()).doesNotContain("rubric","manifest_json","objectKey",f.f.q.client);
        assertThat(f.f.count("prepared_answer_end")).isEqualTo(1);assertThat(f.f.count("prepared_submission")).isZero();
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void actualTeacherEvidenceRefreshPaginationAndOfflineRecoveryDoNotMutateOriginal(int round)throws Exception{
        var q=f.f.q;var end=f.close(f.interrupted(25));int jobs=f.f.count("prepared_job"),audits=f.f.count("prepared_audit");
        var first=success(request(teacher,"GET",path(),null));assertThat(first.path("segments").size()).isEqualTo(20);assertThat(first.path("nextSegmentOrdinal").asInt()).isEqualTo(20);
        var last=success(request(teacher,"GET",path()+"?afterSegmentOrdinal=20",null));assertThat(last.path("segments").size()).isEqualTo(5);assertThat(last.path("declaration")).isEqualTo(first.path("declaration"));
        assertThat(first.toString()).doesNotContain("objectKey","captureClientId","submissionReceipt","serverSaved","chunks");
        Path root=Path.of("").toAbsolutePath().getParent(),log=root.resolve(".runtime/answer-evidence-browser-"+round+".log");
        var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-answer-evidence.spec.ts","--workers=1","--retries=0");builder.directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13017");env.put("PREPARED_EVIDENCE_ASSESSMENT",Integer.toString(q.f.assessment));
        env.put("PREPARED_EVIDENCE_ATTEMPT",q.attempt.id());env.put("PREPARED_EVIDENCE_ANSWER",f.answer);env.put("PREPARED_EVIDENCE_HASH",end.manifestSha256());env.put("PREPARED_EVIDENCE_ACCOUNT",q.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,q.f.actor));
        var process=builder.start();try{assertThat(process.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as("teacher declaration evidence browser "+log.getFileName()).isZero();}
        finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
        assertThat(f.f.count("prepared_audit")).isEqualTo(audits);assertThat(f.f.count("prepared_job")).isEqualTo(jobs);
        assertThat(f.f.count("prepared_answer_end")).isEqualTo(1);assertThat(f.f.count("prepared_submission")).isZero();assertThat(f.f.count("prepared_answer_chunk")).isZero();assertThat(f.f.count("prepared_supplement_grant")).isZero();
        assertThat(f.read(0,20).declaration().manifestSha256()).isEqualTo(end.manifestSha256());assertThat(f.read(0,20).answerState()).isEqualTo("REQUIRES_REVIEW");
    }
}
