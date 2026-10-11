package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.prepared.attempt.PreparedAnswerMediaWorker;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Actual independent cookie/HTTP/DB approval and SAME-job resumption. The initial
 * paper, native synthetic source and private SDK are explicit fixtures. No real
 * cloud/model/device/capacity or complete student-interview acceptance is claimed. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prepared-local")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PreparedQueuedMediaLiveHttpTest {
    // Only this test context uses fixture storage configuration. All SDK IO and
    // scheduled media execution are replaced; production unavailable-store gates stay intact.
    @MockitoBean PreparedPrivateOssStore httpStore;
    @MockitoBean PreparedAnswerMediaWorker scheduledWorker;
    @LocalServerPort int port;
    @DynamicPropertySource static void isolatedRuntime(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",()->PreparedLiveDatabase.jdbcUrl());
        properties.add("spring.datasource.hikari.jdbc-url",()->PreparedLiveDatabase.jdbcUrl());
        properties.add("spring.datasource.username",()->Objects.requireNonNull(System.getenv("PREPARED_DB_USERNAME")));
        properties.add("spring.datasource.password",()->Objects.requireNonNull(System.getenv("PREPARED_DB_PASSWORD")));
        properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        properties.add("spring.flyway.enabled",()->"true");
        properties.add("spring.data.redis.host",()->"127.0.0.1");properties.add("spring.data.redis.port",()->"16389");properties.add("spring.data.redis.database",()->"1");
        properties.add("server.address",()->"127.0.0.1");
        properties.add("aliyun.oss.accessKeyId",()->"disabled-local-client");properties.add("aliyun.oss.accessKeySecret",()->"disabled-local-client");
        properties.add("dashscope.api-key",()->"disabled-local-client");
        properties.add("security.websocket.allowed-origins",()->"http://127.0.0.1:13015");
        properties.add("prepared.interview.workflow-enabled",()->"true");properties.add("prepared.deployment-id",()->"prepared-fixture");
        properties.add("prepared.storage.enabled",()->"true");properties.add("prepared.storage.endpoint",()->"https://oss-cn-beijing.aliyuncs.com");
        properties.add("prepared.storage.bucket",()->"prepared-interview-fixture");properties.add("prepared.storage.access-key-id",()->"fixture-id");properties.add("prepared.storage.access-key-secret",()->"fixture-secret");
        properties.add("prepared.answer-media.worker-enabled",()->"true");
        properties.add("prepared.answer-media.spool-root",()->"/Users/duanhangyu/Code/prepared-interview-system/.runtime/prepared-answer-spool");
        properties.add("prepared.answer-media.ffmpeg",()->"/Users/duanhangyu/.homebrew/Cellar/ffmpeg/8.1.1/bin/ffmpeg");properties.add("prepared.answer-media.ffprobe",()->"/Users/duanhangyu/.homebrew/Cellar/ffmpeg/8.1.1/bin/ffprobe");
        for(String property:List.of("prepared.models.enabled","prepared.generation.worker-enabled","prepared.speech.worker-enabled","prepared.speech.assembly-worker-enabled","prepared.asr.worker-enabled","prepared.scoring.worker-enabled","data-retention.enabled"))properties.add(property,()->"false");
        properties.add("scheduler.start",()->"stop");
    }
    final PreparedAnswerMediaLiveTest f=new PreparedAnswerMediaLiveTest();@TempDir Path folder;
    final ObjectMapper json=new ObjectMapper();HttpClient student,teacher;String hash,job;
    String base;
    @BeforeEach void setup()throws Exception {
        base="http://127.0.0.1:"+port;
        f.folder=folder;f.setup();hash=f.nativeEnd();f.stagedCloudDouble();job=f.request(hash).jobId();f.finishOriginalQuestions(true);f.worker().runOnce();
        var q=f.f.f.ends.q;String password=Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));
        q.f.jdbc.update("UPDATE `user` SET password=? WHERE id IN (?,?)",new PasswordSecurityService().encode(password),q.student,q.f.actor);
        student=login(q.student,password);teacher=login(q.f.actor,password);
    }
    @AfterEach void cleanup()throws Exception {try{for(var client:Arrays.asList(student,teacher))if(client!=null)request(client,"POST","/logout",Map.of());}finally{f.cleanup();}}
    String media(){var q=f.f.f.ends.q;return "/backend/prepared-interviews/"+q.f.assessment+"/attempts/"+q.attempt.id()+"/answers/"+f.f.f.grant.answerId()+"/media";}
    String approval(){var q=f.f.f.ends.q;return "/backend/prepared-reviews/"+q.f.assessment+"/students/"+q.student+"/attempts/"+q.attempt.id()+"/answers/"+f.f.f.grant.answerId()+"/supplement";}
    HttpClient login(int id,String password)throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
        var q=f.f.f.ends.q;String account=q.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,id);
        success(request(client,"POST","/login",Map.of("account",account,"password",password)));return client;
    }
    HttpResponse<String> request(HttpClient client,String method,String path,Object input)throws Exception {
        return raw(client,method,path,input,"http://127.0.0.1:13015");
    }
    HttpResponse<String> raw(HttpClient client,String method,String path,Object input,String origin)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if(origin!=null)builder.header("Origin",origin);
        return client.send(builder.method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode success(HttpResponse<String> response)throws Exception {
        assertThat(response.statusCode()).as("actual original media HTTP: %s",response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");var body=json.readTree(response.body());assertThat(body.path("code").asInt()).isEqualTo(200);return body.path("data");
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void actualTeacherApprovalResumesOnlyOneOriginalMediaJobAndNeverAuthorizesCapture(int round)throws Exception {
        var q=f.f.f.ends.q;var original=f.f.f.ends.attempt();var command=Map.of("captureClientId",q.client,"manifestSha256",hash);
        var waiting=success(request(student,"GET",media(),null));assertThat(waiting.path("phase").asText()).isEqualTo("WAITING_APPROVAL");assertThat(waiting.path("jobId").asText()).isEqualTo(job);assertThat(waiting.path("serverSaved").asBoolean()).isFalse();
        assertThat(request(HttpClient.newHttpClient(),"GET",media(),null).statusCode()).isEqualTo(401);assertThat(request(teacher,"GET",media(),null).statusCode()).isEqualTo(403);
        var expired=request(student,"POST",media(),command);assertThat(expired.statusCode()).isEqualTo(409);assertThat(json.readTree(expired.body()).path("data").path("reasonCode").asText()).isEqualTo("UPLOAD_WINDOW_EXPIRED");
        var wrong=request(student,"POST",media(),Map.of("captureClientId",UUID.randomUUID().toString(),"manifestSha256",hash));assertThat(wrong.statusCode()).isEqualTo(409);assertThat(json.readTree(wrong.body()).path("data").isNull()).isTrue();
        for(String origin:Arrays.asList(null,"https://untrusted.invalid"))assertThat(raw(student,"POST",media(),command,origin).statusCode()).isEqualTo(403);
        var scope=success(request(teacher,"GET",approval(),null));var input=Map.of("idempotencyKey",UUID.randomUUID().toString(),"expectedRevision",scope.path("approvalRevision").asLong(),"expectedAttemptRevision",scope.path("attemptRevision").asLong(),"manifestSha256",hash,"uploadDeadline",Instant.parse(scope.path("serverNow").asText()).plusSeconds(3600).toString(),"reason","原任务超期后核验原清单，不重答");
        assertThat(request(student,"POST",approval(),input).statusCode()).isEqualTo(403);var approved=success(request(teacher,"POST",approval(),input));
        for(int n=0;n<10;n++){var resumed=success(request(student,"POST",media(),command));assertThat(resumed.path("phase").asText()).isEqualTo("QUEUED");assertThat(resumed.path("jobId").asText()).isEqualTo(job);assertThat(resumed.path("serverSaved").asBoolean()).isFalse();}
        assertThat(q.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='ANSWER_MEDIA_RESUMED'",Integer.class,q.f.assessment)).isEqualTo(1);
        q.now.set(Instant.parse(approved.path("approvedAt").asText()).plusMillis(1));f.worker().runOnce();
        var saved=success(request(student,"GET",media(),null));assertThat(saved.path("phase").asText()).isEqualTo("SAVED");assertThat(saved.path("jobId").asText()).isEqualTo(job);assertThat(saved.path("serverSaved").asBoolean()).isTrue();assertThat(saved.path("durationMs").asLong()).isBetween(900L,1200L);
        for(int n=0;n<3;n++)assertThat(success(request(student,"POST",media(),command))).isEqualTo(saved);
        assertThat(saved.toString()).doesNotContain("objectKey","captureClientId","inputJson","errorCode");assertThat(f.f.f.ends.count("prepared_answer_media")).isEqualTo(1);assertThat(f.f.f.ends.count("prepared_answer_end")).isEqualTo(3);assertThat(f.f.f.ends.count("prepared_submission")).isZero();
        assertThat(f.f.f.ends.attempt().answeringEndedAt()).isEqualTo(original.answeringEndedAt());assertThat(f.f.f.ends.attempt().supplementalUploadDeadline()).isEqualTo(original.supplementalUploadDeadline());
        assertThat(q.grants.current(q.student,q.f.assessment,q.attempt.id(),q.client).orElseThrow().mayRecord()).isFalse();
        verifyNoInteractions(httpStore);
    }
}
