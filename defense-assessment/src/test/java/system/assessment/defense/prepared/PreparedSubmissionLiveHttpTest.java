package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.prepared.attempt.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Real student/teacher HTTP sessions, MySQL/Redis and local codecs. Original private
 * storage proof uses an explicitly labelled SDK double, NOT actual OSS acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedSubmissionLiveHttpTest {
    final PreparedSubmissionLiveTest f=new PreparedSubmissionLiveTest();@TempDir Path folder;
    HttpClient student,teacher;final ObjectMapper json=new ObjectMapper();
    final String base="http://127.0.0.1:"+System.getenv().getOrDefault("PREPARED_FORMAL_HTTP_PORT","13016");
    @BeforeEach void setup()throws Exception {
        f.folder=folder;f.setup();var q=f.m.f.f.ends.q;String password=Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));
        q.f.jdbc.update("UPDATE `user` SET password=? WHERE id IN (?,?)",new PasswordSecurityService().encode(password),q.student,q.f.actor);
        student=login(q.student,password);teacher=login(q.f.actor,password);
    }
    @AfterEach void cleanup()throws Exception{for(var c:Arrays.asList(student,teacher))if(c!=null)request(c,"POST","/logout",Map.of());f.cleanup();}
    String path(){var q=f.m.f.f.ends.q;return "/backend/prepared-interviews/"+q.f.assessment+"/attempts/"+q.attempt.id()+"/submission";}
    HttpClient login(int id,String password)throws Exception {
        var q=f.m.f.f.ends.q;String account=q.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,id);
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();var response=request(client,"POST","/login",Map.of("account",account,"password",password));success(response);
        String cookie=System.getenv("SA_TOKEN_TOKEN_NAME");
        if(cookie!=null)assertThat(response.headers().allValues("Set-Cookie").stream().map(x->x.substring(0,x.indexOf('='))).toList()).contains(cookie);
        return client;
    }
    HttpResponse<String> request(HttpClient c,String method,String path,Object input)throws Exception{return raw(c,method,path,input,"http://127.0.0.1:13015");}
    HttpResponse<String> raw(HttpClient c,String method,String path,Object input,String origin)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");if(origin!=null)b.header("Origin",origin);
        b.method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)));return c.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode success(HttpResponse<String> response)throws Exception {assertThat(response.statusCode()).as("scoped response: %s",response.body()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");var body=json.readTree(response.body());assertThat(body.path("code").asInt()).isEqualTo(200);return body.path("data");}
    @Test void actualSubmissionRoutesEnforceSessionRoleOriginScopeAndNeverClaimMissingAnswersSaved()throws Exception {
        var command=f.command();assertThat(request(HttpClient.newHttpClient(),"GET",path(),null).statusCode()).isEqualTo(401);
        assertThat(request(teacher,"GET",path(),null).statusCode()).isEqualTo(403);assertThat(request(teacher,"POST",path(),command).statusCode()).isEqualTo(403);
        assertThat(raw(student,"POST",path(),command,"https://untrusted.invalid").statusCode()).isEqualTo(403);assertThat(raw(student,"POST",path(),command,null).statusCode()).isEqualTo(403);
        assertThat(request(student,"GET",path().replace(f.m.f.f.ends.q.attempt.id(),UUID.randomUUID().toString()),null).statusCode()).isEqualTo(403);
        var state=success(request(student,"GET",path(),null));assertThat(state.path("submitted").asBoolean()).isFalse();assertThat(state.path("pending").size()).isEqualTo(3);
        var missing=request(student,"POST",path(),command);assertThat(missing.statusCode()).isEqualTo(409);assertThat(missing.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(request(student,"POST",path(),Map.of("idempotencyKey",UUID.randomUUID().toString())).statusCode()).isEqualTo(400);
        assertThat(f.m.f.f.ends.count("prepared_submission")).isZero();
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void actualFinalReceiptCanBeQueriedAfterLostResponseAndClosedModelGateWithoutDuplicateOutbox(int round)throws Exception {
        f.savedAll();var command=f.command();var original=success(request(student,"POST",path(),command));
        assertThat(original.path("submitted").asBoolean()).isTrue();assertThat(original.path("receipt").path("answerCount").asInt()).isEqualTo(3);
        for(int n=0;n<5;n++)assertThat(success(request(student,"POST",path(),command))).isEqualTo(original);
        assertThat(success(request(student,"GET",path(),null))).isEqualTo(original);
        assertThat(request(student,"POST",path(),new PreparedSubmission.Command(command.idempotencyKey(),command.expectedRevision()+1)).statusCode()).isEqualTo(409);
        assertThat(f.m.f.f.ends.count("prepared_submission")).isEqualTo(1);
        assertThat(f.m.f.f.ends.q.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=? AND kind IN ('TRANSCRIBE','SCORE')",Integer.class,f.m.f.f.ends.q.f.assessment)).isEqualTo(6);
        // Real UI read-only recovery; native media fixtures do NOT certify a physical device or actual cloud.
        Path root=Path.of("").toAbsolutePath().getParent(),log=Files.createTempFile(root.resolve(".runtime"),"submission-receipt-browser-"+round+"-",".log",
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-submission-receipt.spec.ts","--workers=1","--retries=0");
        builder.directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);
        env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13015");env.put("PREPARED_SUBMISSION_ASSESSMENT",Integer.toString(f.m.f.f.ends.q.f.assessment));
        env.put("PREPARED_SUBMISSION_ACCOUNT",f.m.f.f.ends.q.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,f.m.f.f.ends.q.student));
        env.put("PREPARED_SUBMISSION_RECEIPT",original.path("receipt").path("id").asText());
        var process=builder.start();try{assertThat(process.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as("scoped receipt browser artifact "+log.getFileName()).isZero();}finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
    }
}
