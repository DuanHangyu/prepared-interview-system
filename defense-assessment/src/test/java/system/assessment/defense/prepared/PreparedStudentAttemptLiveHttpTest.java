package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.prepared.attempt.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP/session/MySQL/Redis. Published media and end metadata are explicitly synthetic, NOT cloud audio QA. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedStudentAttemptLiveHttpTest {
    final PreparedQuestionGrantLiveTest g=new PreparedQuestionGrantLiveTest();
    final ObjectMapper json=new ObjectMapper();
    HttpClient student,teacher,outsider;
    int outsiderId;
    final String base="http://127.0.0.1:"+System.getenv().getOrDefault("PREPARED_FORMAL_HTTP_PORT","13016");

    @BeforeEach void setup() throws Exception {
        g.setup();String password=Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));String hash=new PasswordSecurityService().encode(password);
        g.f.jdbc.update("UPDATE `user` SET password=? WHERE id IN (?,?)",hash,g.student,g.f.actor);
        String account="prepared-http-outsider-"+UUID.randomUUID();g.f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('无关联调学生',?,?,0)",account,hash);
        outsiderId=g.f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);
        student=login(g.student,password);teacher=login(g.f.actor,password);outsider=login(outsiderId,password);
    }
    @AfterEach void cleanup() throws Exception {
        for(var client:Arrays.asList(student,teacher,outsider))if(client!=null)request(client,"POST","/logout",Map.of());
        g.f.jdbc.update("DELETE FROM prepared_answer_end_receipt WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("DELETE FROM prepared_answer_end WHERE assessment_id=?",g.f.assessment);
        g.cleanupOnlyThisFixture();g.f.jdbc.update("DELETE FROM `user` WHERE id=?",outsiderId);
    }
    @Test void actualIdentityRoleResourceCsrfAndNoCacheAreEnforced() throws Exception {
        String path=path("/attempts");var command=Map.of("idempotencyKey",key());
        assertThat(request(HttpClient.newHttpClient(),"POST",path,command).statusCode()).isEqualTo(401);
        assertThat(request(teacher,"POST",path,command).statusCode()).isEqualTo(403);
        assertThat(request(outsider,"POST",path,command).statusCode()).isEqualTo(403);
        var badOrigin=raw(student,"POST",path,json.writeValueAsString(command),"https://untrusted.invalid");assertThat(badOrigin.statusCode()).isEqualTo(403);assertNoCache(badOrigin);
        assertThat(raw(student,"POST",path,json.writeValueAsString(command),null).statusCode()).isEqualTo(403);
        var result=success(request(student,"POST",path,Map.of("idempotencyKey",key(),"studentId",outsiderId)));
        assertThat(result.path("id").asText()).isEqualTo(g.attempt.id());assertThat(g.count("prepared_attempt")).isEqualTo(1);
    }
    @Test void originalStartGrantAndEndAreRecoveredThroughRealHttpWithoutDuplicateRows() throws Exception {
        String startKey=key();var first=success(request(student,"POST",path("/attempts"),Map.of("idempotencyKey",startKey)));
        for(int i=0;i<5;i++)assertThat(success(request(student,"POST",path("/attempts"),Map.of("idempotencyKey",startKey)))).isEqualTo(first);
        assertThat(success(request(student,"GET",path("/attempts/current"),null))).isEqualTo(first);
        String questionKey=key();var cmd=new PreparedQuestionGrant.Command(questionKey,g.client,1);
        var grant=success(request(student,"POST",attemptPath("/questions/start"),cmd));
        assertThat(grant.path("mayRecord").asBoolean()).isTrue();
        var recovered=success(request(student,"GET",attemptPath("/questions/current?captureClientId="+g.client),null));
        assertThat(recovered.path("answerId")).isEqualTo(grant.path("answerId"));assertThat(recovered.path("answerDeadline")).isEqualTo(grant.path("answerDeadline"));
        String answer=grant.path("answerId").asText();var end=ending(key());String endPath=attemptPath("/answers/"+answer+"/end");
        var receipt=success(request(student,"POST",endPath,end));
        for(int i=0;i<5;i++)assertThat(success(request(student,"POST",endPath,end))).isEqualTo(receipt);
        assertThat(receipt.path("serverSaved").asBoolean()).isFalse();assertThat(receipt.path("declarationState").asText()).isEqualTo("UPLOAD_PENDING");
        assertThat(g.count("prepared_answer_end")).isEqualTo(1);assertThat(g.count("prepared_answer_end_receipt")).isEqualTo(1);assertThat(g.count("prepared_answer")).isEqualTo(1);
    }
    @Test void twoRealConcurrentFirstStartRequestsCreateOneAnchorAndFreezeTheSamePaper() throws Exception {
        // Remove only the synthetic fixture's original start, leaving its reviewed publication in place.
        g.f.jdbc.update("DELETE FROM prepared_audit WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("DELETE FROM prepared_attempt_start_receipt WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("DELETE FROM prepared_attempt_anchor WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("DELETE FROM prepared_attempt WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("DELETE FROM student_assessment_record WHERE assessment_id=?",g.f.assessment);
        g.f.jdbc.update("UPDATE prepared_paper_gate SET first_started_at=NULL WHERE assessment_id=?",g.f.assessment);
        var empty=success(request(student,"GET",path("/attempts/current"),null));assertThat(empty.isNull()||empty.isMissingNode()).isTrue();
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->success(request(student,"POST",path("/attempts"),Map.of("idempotencyKey",key()))));
            var second=pool.submit(()->success(request(student,"POST",path("/attempts"),Map.of("idempotencyKey",key()))));
            var original=first.get(8,java.util.concurrent.TimeUnit.SECONDS);assertThat(second.get(8,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(original);
            assertThat(original.path("paperVersionId").asText()).isEqualTo(g.attempt.paperVersionId());assertThat(original.path("id").asText()).isNotEqualTo(g.attempt.id());
        }
        assertThat(g.count("prepared_attempt")).isEqualTo(1);assertThat(g.count("prepared_attempt_anchor")).isEqualTo(1);
        assertThat(g.f.jdbc.queryForObject("SELECT first_started_at IS NOT NULL FROM prepared_paper_gate WHERE assessment_id=?",Boolean.class,g.f.assessment)).isTrue();
    }
    @Test void actualOldTeacherWriteRoutesCannotChangeThePreparedAnchorOrTriggerOldAnalysis() throws Exception {
        assertThat(request(teacher,"POST","/backend/study-record/change-score",Map.of("id",g.attempt.recordId(),"checkScore",7)).statusCode()).isEqualTo(409);
        assertThat(request(teacher,"POST","/backend/study-record/retake",Map.of("recordId",g.attempt.recordId())).statusCode()).isEqualTo(409);
        assertThat(request(teacher,"POST","/backend/study-record/reanalyze",Map.of("recordId",g.attempt.recordId())).statusCode()).isEqualTo(409);
        assertThat(g.f.jdbc.queryForObject("SELECT state FROM student_assessment_record WHERE id=?",Integer.class,g.attempt.recordId())).isZero();
        assertThat(g.f.jdbc.queryForObject("SELECT check_score FROM student_assessment_record WHERE id=?",Integer.class,g.attempt.recordId())).isNull();
        assertThat(g.f.jdbc.queryForObject("SELECT re_analysis FROM student_assessment_record WHERE id=?",Boolean.class,g.attempt.recordId())).isFalse();
    }
    @Test void sequentialThreeQuestionDeclarationsSetOneFixedWindowButDoNotSubmitOrClaimAudioSaved() throws Exception {
        JsonNode last=null;
        for(int ordinal=1;ordinal<=3;ordinal++) {
            var grant=success(request(student,"POST",attemptPath("/questions/start"),new PreparedQuestionGrant.Command(key(),g.client,ordinal)));
            last=success(request(student,"POST",attemptPath("/answers/"+grant.path("answerId").asText()+"/end"),ending(key())));
            assertThat(last.path("serverSaved").asBoolean()).isFalse();
            if(ordinal<3)assertThat(last.path("answeringEndedAt").isNull()).isTrue();
        }
        assertThat(last.path("attemptState").asText()).isEqualTo("UPLOAD_PENDING");
        assertThat(Instant.parse(last.path("supplementalUploadDeadline").asText())).isEqualTo(Instant.parse(last.path("answeringEndedAt").asText()).plus(Duration.ofHours(24)));
        assertThat(g.count("prepared_answer")).isEqualTo(3);assertThat(g.count("prepared_answer_end")).isEqualTo(3);
        assertThat(g.f.jdbc.queryForObject("SELECT state FROM student_assessment_record WHERE id=?",Integer.class,g.attempt.recordId())).isZero();
        assertThat(request(student,"POST",attemptPath("/questions/start"),new PreparedQuestionGrant.Command(key(),g.client,4)).statusCode()).isEqualTo(409);
    }
    @Test void unauthorizedSequenceForeignClientChangedCommandAndRevokedAssignmentCannotCreateOrOverwriteAnswers() throws Exception {
        String command=key();var original=new PreparedQuestionGrant.Command(command,g.client,1);
        assertThat(request(student,"POST",attemptPath("/questions/start"),new PreparedQuestionGrant.Command(key(),g.client,2)).statusCode()).isEqualTo(409);
        success(request(student,"POST",attemptPath("/questions/start"),original));
        assertThat(request(student,"POST",attemptPath("/questions/start"),new PreparedQuestionGrant.Command(command,key(),1)).statusCode()).isEqualTo(409);
        assertThat(request(student,"POST",attemptPath("/questions/start"),new PreparedQuestionGrant.Command(key(),key(),1)).statusCode()).isEqualTo(409);
        assertThat(success(request(student,"GET",attemptPath("/questions/current?captureClientId="+key()),null)).path("mayRecord").asBoolean()).isFalse();
        assertThat(request(outsider,"GET",attemptPath("/questions/current?captureClientId="+g.client),null).statusCode()).isEqualTo(403);
        g.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",g.f.assessment,g.student);
        assertThat(request(student,"POST",attemptPath("/questions/start"),original).statusCode()).isEqualTo(403);assertThat(g.count("prepared_answer")).isEqualTo(1);
    }
    @Test void malformedAndOversizedBodiesNeverReachMutationAndSuccessfulProjectionHidesPrivateFields() throws Exception {
        assertThat(raw(student,"POST",path("/attempts"),"{",origin()).statusCode()).isEqualTo(400);
        assertThat(request(student,"POST",path("/attempts"),Map.of("idempotencyKey","invalid")).statusCode()).isEqualTo(400);
        var large=raw(student,"POST",attemptPath("/answers/"+key()+"/end")," ".repeat(PreparedStudentBodyLimitFilter.MAX_BYTES+1),origin());assertThat(large.statusCode()).isEqualTo(413);assertNoCache(large);
        var grant=success(request(student,"POST",attemptPath("/questions/start"),new PreparedQuestionGrant.Command(key(),g.client,1)));
        assertThat(grant.toString()).doesNotContain("rubric","maxScore","objectKey","audioObjectKey",g.client,"题目 2","题目 3");
        assertThat(g.count("prepared_answer_end")).isZero();
    }
    @Test void actualEncodedAndMatrixRoutesUseTheSameBodyProtectionAndAuthentication() throws Exception {
        String encoded="/backend/%70repared-interviews/"+g.f.assessment+"/attempts";
        var oversized=raw(student,"POST",encoded," ".repeat(PreparedStudentBodyLimitFilter.MAX_BYTES+1),origin());assertThat(oversized.statusCode()).isEqualTo(413);assertNoCache(oversized);
        assertThat(request(HttpClient.newHttpClient(),"POST",encoded,Map.of("idempotencyKey",key())).statusCode()).isEqualTo(401);
        assertThat(success(request(student,"POST",encoded,Map.of("idempotencyKey",key()))).path("id").asText()).isEqualTo(g.attempt.id());
        // Keep the framework's forbidden path policy; don't report its old plain-text 200 as business success.
        for(String prefix:new String[]{"/backend/prepared-interviews;v=1/","/backend/prepared-interviews%3Bv=1/"}) {
            String path=prefix+g.f.assessment+"/attempts";
            for(var client:List.of(student,HttpClient.newHttpClient())) {
                var response=request(client,"POST",path,Map.of("idempotencyKey",key()));assertThat(response.statusCode()).isEqualTo(400);assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(400);assertNoCache(response);
                assertThat(request(client,"GET",path+"/current",null).statusCode()).isEqualTo(400);
            }
        }
        assertThat(g.count("prepared_attempt")).isEqualTo(1);
    }
    @Test void actualSharedRedisReadLimitIsPerAccountAndDoesNotConsumeMutationOrAnotherStudentsQuota() throws Exception {
        for(int i=0;i<120;i++)success(request(student,"GET",path("/attempts/current"),null));
        var limited=request(student,"GET",path("/attempts/current"),null);assertThat(limited.statusCode()).isEqualTo(429);assertNoCache(limited);
        var encodedLimited=request(student,"GET","/backend/%70repared-interviews/"+g.f.assessment+"/attempts/current",null);assertThat(encodedLimited.statusCode()).isEqualTo(429);assertNoCache(encodedLimited);
        success(request(student,"POST",path("/attempts"),Map.of("idempotencyKey",key())));
        assertThat(request(outsider,"GET",path("/attempts/current"),null).statusCode()).isEqualTo(403);
    }
    @Test void defaultClosedRuntimeKeepsOriginalReadRecoveryButRefusesNewMutation() throws Exception {
        var command=Map.of("idempotencyKey",key());String account=g.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,g.student);
        var closed=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var login=HttpRequest.newBuilder(URI.create("http://127.0.0.1:13014/login")).header("Origin",origin()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("account",account,"password",Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD")))))).build();
        assertThat(closed.send(login,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        var start=HttpRequest.newBuilder(URI.create("http://127.0.0.1:13014"+path("/attempts"))).header("Origin",origin()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
        var response=closed.send(start,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(503);assertNoCache(response);
        var current=HttpRequest.newBuilder(URI.create("http://127.0.0.1:13014"+path("/attempts/current"))).GET().build();
        assertThat(success(closed.send(current,HttpResponse.BodyHandlers.ofString())).path("id").asText()).isEqualTo(g.attempt.id());
        closed.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:13014/logout")).header("Origin",origin()).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    PreparedAnswerEnd.Command ending(String key) {
        long now=System.currentTimeMillis();var chunk=new PreparedAnswerEnd.Chunk(0,1,"a".repeat(64));
        var segment=new PreparedAnswerEnd.Segment(key(),1,"audio/webm;codecs=opus",now,now,"SEALED",null,1,1,List.of(chunk));
        return new PreparedAnswerEnd.Command(key,g.client,new PreparedAnswerEnd.Manifest(1,List.of(segment)));
    }
    HttpClient login(int id,String password) throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
        String account=g.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,id);
        success(request(client,"POST","/login",Map.of("account",account,"password",password)));return client;
    }
    HttpResponse<String> request(HttpClient client,String method,String path,Object body) throws Exception {return raw(client,method,path,body==null?null:json.writeValueAsString(body),origin());}
    HttpResponse<String> raw(HttpClient client,String method,String path,String body,String origin) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json");if(origin!=null)builder.header("Origin",origin);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode success(HttpResponse<String> response) throws Exception {assertThat(response.statusCode()).as("HTTP response: %s",response.body()).isEqualTo(200);assertNoCache(response);var body=json.readTree(response.body());assertThat(body.path("code").asInt()).isEqualTo(200);return body.path("data");}
    static void assertNoCache(HttpResponse<?> response){assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");}
    String path(String suffix){return "/backend/prepared-interviews/"+g.f.assessment+suffix;}
    String attemptPath(String suffix){return path("/attempts/"+g.attempt.id()+suffix);}
    static String key(){return UUID.randomUUID().toString();}
    static String origin(){return "http://127.0.0.1:13015";}
}
