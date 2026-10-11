package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.prepared.attempt.PreparedSupplement;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Actual cookies/HTTP/independent MySQL/Redis. Upload success is explicitly a private SDK double. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedSupplementLiveHttpTest {
    final PreparedSupplementLiveTest f=new PreparedSupplementLiveTest();final ObjectMapper json=new ObjectMapper();
    HttpClient teacher,student,unrelated,admin;final List<Integer> users=new ArrayList<>();
    final String base="http://127.0.0.1:"+System.getenv().getOrDefault("PREPARED_FORMAL_HTTP_PORT","13016");
    @BeforeEach void setup()throws Exception{
        f.setup();f.expired();var q=f.f.ends.q;var jdbc=q.f.jdbc;String password=Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));var encoder=new PasswordSecurityService();
        jdbc.update("UPDATE `user` SET password=? WHERE id IN (?,?)",encoder.encode(password),q.f.actor,q.student);teacher=login(q.f.actor,password);student=login(q.student,password);
        for(int role:new int[]{1,2}){String account="prepared-supplement-http-"+UUID.randomUUID();jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('补传审批权限验证',?,?,?)",account,encoder.encode(password),role);int id=jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);users.add(id);if(role==1)unrelated=login(id,password);else admin=login(id,password);}
    }
    @AfterEach void cleanup()throws Exception{
        for(var c:Arrays.asList(teacher,student,unrelated,admin))if(c!=null)request(c,"POST","/logout",Map.of());var q=f.f.ends.q;
        q.f.jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=?",q.f.assessment);f.cleanup();for(int id:users)q.f.jdbc.update("DELETE FROM `user` WHERE id=?",id);
    }
    String path(){var q=f.f.ends.q;return "/backend/prepared-reviews/"+q.f.assessment+"/students/"+q.student+"/attempts/"+q.attempt.id()+"/answers/"+f.answer+"/supplement";}
    HttpClient login(int id,String password)throws Exception{
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();String account=f.f.ends.q.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,id);
        assertThat(request(client,"POST","/login",Map.of("account",account,"password",password)).statusCode()).isEqualTo(200);return client;
    }
    HttpResponse<String> request(HttpClient client,String method,String path,Object input)throws Exception{return raw(client,method,path,input==null?null:json.writeValueAsString(input),"http://127.0.0.1:13015","application/json",null);}
    HttpResponse<String> raw(HttpClient client,String method,String path,String body,String origin,String type,String encoding)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("Content-Type",type);if(origin!=null)builder.header("Origin",origin);if(encoding!=null)builder.header("Content-Encoding",encoding);
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode success(HttpResponse<String> response)throws Exception{assertThat(response.statusCode()).as("actual scoped approval HTTP: %s",response.body()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");var data=json.readTree(response.body());assertThat(data.path("code").asInt()).isEqualTo(200);return data.path("data");}
    PreparedSupplement.Command command(JsonNode status,int seconds){return new PreparedSupplement.Command(UUID.randomUUID().toString(),status.path("approvalRevision").asLong(),status.path("attemptRevision").asLong(),status.path("manifestSha256").asText(),Instant.parse(status.path("serverNow").asText()).plusSeconds(seconds).toString(),"真实教师会话核验原清单后限时补传");}
    String windowPath(String answer){var q=f.f.ends.q;return "/backend/prepared-interviews/"+q.f.assessment+"/attempts/"+q.attempt.id()+"/answers/"+answer+"/upload-window?captureClientId="+q.client;}
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void realStudentWindowReadAndTypedExpiryCannotChangeOriginalAnswers(int round)throws Exception{
        var q=f.f.ends.q;var original=f.f.ends.attempt();var waiting=success(request(student,"GET",windowPath(f.answer),null));assertThat(waiting.path("phase").asText()).isEqualTo("AWAITING_APPROVAL");assertThat(waiting.path("mayUploadOriginal").asBoolean()).isFalse();
        assertThat(request(HttpClient.newHttpClient(),"GET",windowPath(f.answer),null).statusCode()).isEqualTo(401);for(var manager:List.of(teacher,admin))assertThat(request(manager,"GET",windowPath(f.answer),null).statusCode()).isEqualTo(403);
        var wrong=request(student,"GET",windowPath(f.answer).replace(q.client,UUID.randomUUID().toString()),null);assertThat(wrong.statusCode()).isEqualTo(409);assertThat(json.readTree(wrong.body()).path("data").isNull()).isTrue();
        var c=f.f.command();String chunk="/backend/prepared-interviews/"+q.f.assessment+"/attempts/"+q.attempt.id()+"/answers/"+f.answer+"/chunks/"+c.segmentId()+"/0?captureClientId="+q.client+"&segmentOrdinal=1&mimeType="+URLEncoder.encode(c.mimeType(),java.nio.charset.StandardCharsets.UTF_8)+"&byteSize=5&sha256="+c.sha256();
        var expired=raw(student,"PUT",chunk,new String(f.f.bytes,java.nio.charset.StandardCharsets.ISO_8859_1),"http://127.0.0.1:13015","application/octet-stream",null);assertThat(expired.statusCode()).isEqualTo(409);assertThat(json.readTree(expired.body()).path("data").path("reasonCode").asText()).isEqualTo("UPLOAD_WINDOW_EXPIRED");
        var approved=success(request(teacher,"POST",path(),command(success(request(teacher,"GET",path(),null)),3600)));var resumed=success(request(student,"GET",windowPath(f.answer),null));assertThat(resumed.path("phase").asText()).isEqualTo("APPROVED");assertThat(resumed.path("uploadDeadline")).isEqualTo(approved.path("uploadDeadline"));assertThat(resumed.path("answerDeadline")).isEqualTo(waiting.path("answerDeadline"));assertThat(resumed.path("manifestSha256")).isEqualTo(waiting.path("manifestSha256"));
        assertThat(resumed.toString()).doesNotContain("reason","actorId","idempotencyKey","objectKey","captureClientId");
        String other=q.f.jdbc.queryForObject("SELECT id FROM prepared_answer WHERE attempt_id=? AND ordinal=2",String.class,q.attempt.id());assertThat(success(request(student,"GET",windowPath(other),null)).path("mayUploadOriginal").asBoolean()).isFalse();
        assertThat(raw(student,"PUT",chunk,new String(f.f.bytes,java.nio.charset.StandardCharsets.ISO_8859_1),"http://127.0.0.1:13015","application/octet-stream",null).statusCode()).isEqualTo(503);
        assertThat(f.f.ends.attempt().answeringEndedAt()).isEqualTo(original.answeringEndedAt());assertThat(f.f.ends.attempt().supplementalUploadDeadline()).isEqualTo(original.supplementalUploadDeadline());assertThat(f.f.ends.count("prepared_answer_chunk")).isZero();assertThat(f.f.ends.count("prepared_submission")).isZero();
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    @EnabledIfEnvironmentVariable(named="PREPARED_STUDENT_SUPPLEMENT_BROWSER_FIXTURES",matches="true")
    void actualStudentBrowserResumesOriginalRequestAfterApprovalAndReloadWithoutCloudSuccess(int round)throws Exception{
        var q=f.f.ends.q;var original=f.f.ends.attempt();int jobs=f.f.ends.count("prepared_job");var fixture=new LinkedHashMap<String,Object>();
        fixture.put("assessmentId",q.f.assessment);fixture.put("studentId",q.student);fixture.put("attemptId",q.attempt.id());fixture.put("paperVersionId",q.attempt.paperVersionId());fixture.put("captureClientId",q.client);
        fixture.put("startKey",q.f.jdbc.queryForObject("SELECT idempotency_key FROM prepared_attempt_start_receipt WHERE attempt_id=?",String.class,q.attempt.id()));fixture.put("firstAnswerId",f.answer);fixture.put("firstManifest",f.f.manifest(f.f.command()));
        fixture.put("questions",q.f.jdbc.query("SELECT a.id,a.ordinal,s.idempotency_key AS question_key,e.idempotency_key AS end_key FROM prepared_answer a JOIN prepared_question_start_receipt s ON s.answer_id=a.id JOIN prepared_answer_end_receipt e ON e.answer_id=a.id WHERE a.attempt_id=? ORDER BY a.ordinal",(rs,n)->Map.of("answerId",rs.getString("id"),"ordinal",rs.getInt("ordinal"),"questionKey",rs.getString("question_key"),"endKey",rs.getString("end_key")),q.attempt.id()));
        Path root=Path.of("").toAbsolutePath().getParent(),log=root.resolve(".runtime/student-supplement-browser-"+round+".log");var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-student-supplement.spec.ts","--workers=1","--retries=0");builder.directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13017");env.put("PREPARED_STUDENT_SUPPLEMENT_FIXTURE",json.writeValueAsString(fixture));env.put("PREPARED_STUDENT_SUPPLEMENT_ACCOUNT",q.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,q.student));env.put("PREPARED_STUDENT_SUPPLEMENT_TEACHER",q.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,q.f.actor));
        var process=builder.start();try{assertThat(process.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as("student original supplement browser "+log.getFileName()).isZero();}finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
        assertThat(f.f.ends.count("prepared_supplement_grant")).isEqualTo(1);assertThat(f.f.ends.count("prepared_answer")).isEqualTo(3);assertThat(f.f.ends.count("prepared_answer_end")).isEqualTo(3);assertThat(f.f.ends.count("prepared_answer_chunk")).isZero();assertThat(f.f.ends.count("prepared_submission")).isZero();assertThat(f.f.ends.count("prepared_job")).isEqualTo(jobs);
        assertThat(f.f.ends.attempt().answeringEndedAt()).isEqualTo(original.answeringEndedAt());assertThat(f.f.ends.attempt().supplementalUploadDeadline()).isEqualTo(original.supplementalUploadDeadline());
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void realTeacherApprovesAndRestoresExactOriginalWhileStudentCannotApprove(int round)throws Exception{
        var q=f.f.ends.q;var before=f.f.ends.attempt();var view=success(request(teacher,"GET",path(),null));assertThat(view.path("mayApprove").asBoolean()).isTrue();
        var command=command(view,3600);assertThat(request(student,"POST",path(),command).statusCode()).isEqualTo(403);
        var approved=success(request(teacher,"POST",path(),command));for(int n=0;n<3;n++)assertThat(success(request(teacher,"POST",path(),command))).isEqualTo(approved);
        assertThat(success(request(teacher,"GET",path(),null)).path("latest")).isEqualTo(approved);
        assertThat(approved.path("answerDeadline").asText()).isEqualTo(f.f.grant.answerDeadline());assertThat(approved.path("automaticDeadline").asText()).isEqualTo(before.supplementalUploadDeadline());
        q.now.set(Instant.parse(approved.path("approvedAt").asText()).plusMillis(1));assertThat(f.f.upload(f.f.command(),f.f.bytes).confirmed()).isTrue();
        assertThat(f.f.ends.attempt().answeringEndedAt()).isEqualTo(before.answeringEndedAt());assertThat(f.f.ends.attempt().supplementalUploadDeadline()).isEqualTo(before.supplementalUploadDeadline());
        assertThat(f.f.ends.count("prepared_supplement_grant")).isEqualTo(1);assertThat(f.f.ends.count("prepared_submission")).isZero();assertThat(f.f.ends.count("prepared_answer_media")).isZero();
        assertThat(request(teacher,"POST",path(),new PreparedSupplement.Command(UUID.randomUUID().toString(),0,command.expectedAttemptRevision(),command.manifestSha256(),command.uploadDeadline(),"过期审批页面")).statusCode()).isEqualTo(409);
        assertThat(approved.toString()).doesNotContain("objectKey",q.client,"serverSaved","rubric");
    }
    @Test void actualPermissionCsrfBoundsAndInvitationRevocationCannotCreateAnApproval()throws Exception{
        assertThat(request(HttpClient.newHttpClient(),"GET",path(),null).statusCode()).isEqualTo(401);
        for(var client:List.of(student,unrelated))assertThat(request(client,"GET",path(),null).statusCode()).isEqualTo(403);
        var q=f.f.ends.q;var jdbc=q.f.jdbc;jdbc.update("INSERT INTO assessment_invite(assessment_id,invitee_teacher_id,inviter_teacher_id) VALUES (?,?,?)",q.f.assessment,users.getFirst(),q.f.actor);
        success(request(unrelated,"GET",path(),null));jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=?",q.f.assessment);assertThat(request(unrelated,"GET",path(),null).statusCode()).isEqualTo(403);
        success(request(admin,"GET",path(),null));var view=success(request(teacher,"GET",path(),null));var command=command(view,3600);String body=json.writeValueAsString(command);
        for(String origin:Arrays.asList(null,"https://untrusted.invalid"))assertThat(raw(teacher,"POST",path(),body,origin,"application/json",null).statusCode()).isEqualTo(403);
        for(String bad:List.of("{}",body.replace("\"expectedRevision\":0","\"expectedRevision\":\"0\""),body.substring(0,body.length()-1)+",\"actorId\":1}",body+"{}"))assertThat(raw(teacher,"POST",path(),bad,"http://127.0.0.1:13015","application/json",null).statusCode()).isEqualTo(400);
        assertThat(raw(teacher,"POST",path(),"x".repeat(16385),"http://127.0.0.1:13015","application/json",null).statusCode()).isEqualTo(413);
        assertThat(raw(teacher,"POST",path(),body,"http://127.0.0.1:13015","application/json","gzip").statusCode()).isEqualTo(415);
        assertThat(request(teacher,"GET",path().replace(f.answer,UUID.randomUUID().toString()),null).statusCode()).isEqualTo(403);
        assertThat(request(teacher,"GET",path().replace("/students/"+q.student,"/students/"+users.getFirst()),null).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE `user` SET type=0 WHERE id=?",q.f.actor);try{assertThat(request(teacher,"GET",path(),null).statusCode()).isEqualTo(403);assertThat(request(teacher,"POST",path(),command).statusCode()).isEqualTo(403);}finally{jdbc.update("UPDATE `user` SET type=1 WHERE id=?",q.f.actor);}
        assertThat(f.f.ends.count("prepared_supplement_grant")).isZero();
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    @EnabledIfEnvironmentVariable(named="PREPARED_SUPPLEMENT_BROWSER_FIXTURES",matches="true")
    void actualTeacherBrowserOriginalApprovalRecoveryDoesNotCreateDuplicateGrantsOrRecordingTime(int round)throws Exception{
        var q=f.f.ends.q;var original=f.f.ends.attempt();int originalJobs=f.f.ends.count("prepared_job");Path root=Path.of("").toAbsolutePath().getParent(),log=root.resolve(".runtime/supplement-browser-"+round+".log");
        var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-supplement.spec.ts","--workers=1","--retries=0");builder.directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13017");env.put("PREPARED_SUPPLEMENT_ASSESSMENT",Integer.toString(q.f.assessment));env.put("PREPARED_SUPPLEMENT_STUDENT",Integer.toString(q.student));env.put("PREPARED_SUPPLEMENT_ATTEMPT",q.attempt.id());env.put("PREPARED_SUPPLEMENT_ANSWER",f.answer);env.put("PREPARED_SUPPLEMENT_ACCOUNT",q.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,q.f.actor));
        var process=builder.start();try{assertThat(process.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as("scoped approval browser "+log.getFileName()).isZero();}finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
        assertThat(f.f.ends.count("prepared_supplement_grant")).isEqualTo(2);assertThat(f.f.ends.count("prepared_submission")).isZero();assertThat(f.f.ends.count("prepared_answer_media")).isZero();assertThat(f.f.ends.count("prepared_job")).isEqualTo(originalJobs);
        assertThat(f.f.ends.attempt().answeringEndedAt()).isEqualTo(original.answeringEndedAt());assertThat(f.f.ends.attempt().supplementalUploadDeadline()).isEqualTo(original.supplementalUploadDeadline());
        // The browser genuinely logged in again; the security baseline invalidates the
        // old client session. Do not weaken it to make a stale-cookie assertion green.
        assertThat(request(teacher,"GET",path(),null).statusCode()).isEqualTo(401);
        teacher=login(q.f.actor,Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD")));
        assertThat(success(request(teacher,"GET",path(),null)).path("approvalRevision").asLong()).isEqualTo(2);assertThat(f.status().answerState()).isEqualTo("UPLOAD_PENDING");
    }
}
