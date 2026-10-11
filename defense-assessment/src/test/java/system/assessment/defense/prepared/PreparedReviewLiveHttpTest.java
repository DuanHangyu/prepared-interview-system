package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.prepared.processing.PreparedProcessing;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind;

/** Actual teacher/student cookie auth, HTTP, independent MySQL/Redis and native original
 * receipt. Model results and private storage remain explicitly synthetic doubles. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedReviewLiveHttpTest {
    final PreparedProcessingLiveTest f=new PreparedProcessingLiveTest();@TempDir Path folder;
    final ObjectMapper json=new ObjectMapper();HttpClient teacher,student,unrelated,admin;
    final List<String> historicalFixtures=new ArrayList<>();
    String base="http://127.0.0.1:"+System.getenv().getOrDefault("PREPARED_FORMAL_HTTP_PORT","13016");
    // Keep this actual JUnit test class discoverable: a second constructor
    // prevents Jupiter from creating it, even though direct fixture calls work.
    static PreparedReviewLiveHttpTest forSummary(){var fixture=new PreparedReviewLiveHttpTest();fixture.base="http://127.0.0.1:13018";return fixture;}
    @BeforeEach void setup()throws Exception{
        f.folder=folder;f.setup();String password=Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));var encoder=new PasswordSecurityService();
        f.jdbc.update("UPDATE `user` SET password=? WHERE id IN (?,?)",encoder.encode(password),f.actor,f.student);
        teacher=login(f.actor,password);student=login(f.student,password);
        for(int role:new int[]{1,2}){String account="prepared-review-access-"+UUID.randomUUID();f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('评阅权限验证',?,?,?)",account,encoder.encode(password),role);
            int id=f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);f.extraUsers.add(id);if(role==1)unrelated=login(id,password);else admin=login(id,password);}
    }
    @AfterEach void cleanup()throws Exception{for(var c:Arrays.asList(teacher,student,unrelated,admin))if(c!=null)request(c,"POST","/logout",Map.of());
        if(!historicalFixtures.isEmpty()){f.jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",f.attempt,f.assessment,f.student);
            for(String id:historicalFixtures)f.jdbc.update("DELETE FROM prepared_attempt WHERE id=? AND assessment_id=? AND student_id=?",id,f.assessment,f.student);}
        f.cleanup();}
    String root(){return "/backend/prepared-reviews/"+f.assessment;}
    String interview(){return root()+"/attempts/"+f.attempt;}
    String answer(String answer){return interview()+"/answers/"+answer;}
    HttpClient login(int id,String password)throws Exception{
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
        String account=f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,id);success(request(client,"POST","/login",Map.of("account",account,"password",password)));return client;
    }
    HttpResponse<String> request(HttpClient c,String method,String path,Object input)throws Exception{return raw(c,method,path,input==null?null:json.writeValueAsString(input),"http://127.0.0.1:13015","application/json",null);}
    HttpResponse<String> raw(HttpClient c,String method,String path,String body,String origin,String type,String encoding)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("Content-Type",type);if(origin!=null)b.header("Origin",origin);if(encoding!=null)b.header("Content-Encoding",encoding);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));return c.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode success(HttpResponse<String> r)throws Exception{assertThat(r.statusCode()).as("scoped review HTTP response: %s",r.body()).isEqualTo(200);assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");var b=json.readTree(r.body());assertThat(b.path("code").asInt()).isEqualTo(200);return b.path("data");}
    @Test void actualReviewRoutesRequireCurrentManagerScopeAndStrictBoundedOriginalCommands()throws Exception{
        String answer=f.jdbc.queryForObject("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal LIMIT 1",String.class,f.assessment),path=answer(answer);
        assertThat(request(HttpClient.newHttpClient(),"GET",root(),null).statusCode()).isEqualTo(401);
        for(var c:List.of(student,unrelated))for(String target:List.of(root(),interview(),path,path+"/audio"))assertThat(request(c,"GET",target,null).statusCode()).isEqualTo(403);
        f.jdbc.update("INSERT INTO assessment_invite(assessment_id,invitee_teacher_id,inviter_teacher_id) VALUES (?,?,?)",f.assessment,f.extraUsers.getFirst(),f.actor);
        try{for(String target:List.of(root(),interview(),path))success(request(unrelated,"GET",target,null));}finally{f.jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=? AND invitee_teacher_id=?",f.assessment,f.extraUsers.getFirst());}
        assertThat(request(unrelated,"GET",path,null).statusCode()).isEqualTo(403);
        assertThat(request(unrelated,"GET",path+"/audio",null).statusCode()).isEqualTo(403);
        for(var c:List.of(teacher,admin)){var denied=request(c,"GET",path+"/audio",null);assertThat(denied.statusCode()).isEqualTo(503);assertThat(denied.headers().firstValue("Cache-Control").orElse("")).contains("no-store");assertThat(denied.body()).doesNotContain("Signature=","objectKey");}
        assertThat(success(request(admin,"GET",root(),null)).path("actorId").asInt()).isEqualTo(f.extraUsers.getLast());
        assertThat(success(request(teacher,"GET",path,null)).path("score").isNull()).isTrue();
        assertThat(request(teacher,"GET",interview().replace(f.attempt,UUID.randomUUID().toString()),null).statusCode()).isEqualTo(403);
        assertThat(request(teacher,"GET",answer(UUID.randomUUID().toString()),null).statusCode()).isEqualTo(403);
        assertThat(request(teacher,"GET",answer(UUID.randomUUID().toString())+"/audio",null).statusCode()).isEqualTo(403);
        for(String query:List.of("?size=0","?size=51","?afterRecordId=-1","?size=1.5"))assertThat(request(teacher,"GET",root()+query,null).statusCode()).isEqualTo(400);
        var command=new PreparedProcessing.ConfirmCommand(UUID.randomUUID().toString(),0,UUID.randomUUID().toString(),"原核验");String body=json.writeValueAsString(command);
        for(String origin:Arrays.asList(null,"https://untrusted.invalid"))assertThat(raw(teacher,"POST",path+"/confirm",body,origin,"application/json",null).statusCode()).isEqualTo(403);
        for(String invalid:List.of(body.replace("\"expectedRevision\":0","\"expectedRevision\":0.5"),body.substring(0,body.length()-1)+",\"actorId\":1}","{}"))assertThat(raw(teacher,"POST",path+"/confirm",invalid,"http://127.0.0.1:13015","application/json",null).statusCode()).isEqualTo(400);
        assertThat(raw(teacher,"POST",path+"/confirm","x".repeat(16385),"http://127.0.0.1:13015","application/json",null).statusCode()).isEqualTo(413);
        assertThat(raw(teacher,"POST",path+"/confirm",body,"http://127.0.0.1:13015","application/json","gzip").statusCode()).isEqualTo(415);
        f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.actor);try{assertThat(request(teacher,"GET",root(),null).statusCode()).isEqualTo(403);assertThat(request(teacher,"GET",path+"/audio",null).statusCode()).isEqualTo(403);}finally{f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.actor);}
        assertThat(f.count("prepared_result_command")).isZero();
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void realReviewConfirmLostResponseRefreshRegradeAndConflictRemainOriginalAndRepeatable(int round)throws Exception{
        var transcript=f.transcribe(f.claim(Kind.TRANSCRIBE));var score=f.score(f.claim(Kind.SCORE),"8.5");String path=answer(transcript.answerId());
        var detail=success(request(teacher,"GET",path,null));assertThat(detail.path("question").path("rubric").asText()).startsWith("不应泄漏的教师标准");
        assertThat(detail.path("score").path("score").decimalValue()).isEqualByComparingTo("8.5");
        var confirm=new PreparedProcessing.ConfirmCommand(UUID.randomUUID().toString(),detail.path("revision").asLong(),score.id(),"实际 HTTP 核分依据");
        var confirmed=success(request(teacher,"POST",path+"/confirm",confirm));for(int n=0;n<3;n++)assertThat(success(request(teacher,"POST",path+"/confirm",confirm))).isEqualTo(confirmed);
        assertThat(success(request(teacher,"GET",path,null)).path("confirmed").asBoolean()).isTrue();
        assertThat(request(teacher,"POST",path+"/confirm",new PreparedProcessing.ConfirmCommand(UUID.randomUUID().toString(),confirm.expectedRevision(),score.id(),"过期页面")).statusCode()).isEqualTo(409);
        var regrade=new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),confirmed.path("revision").asLong(),transcript.id(),"实际 HTTP 重评依据");
        var queued=success(request(teacher,"POST",path+"/regrade",regrade));assertThat(success(request(teacher,"POST",path+"/regrade",regrade))).isEqualTo(queued);
        assertThat(success(request(teacher,"GET",path+"?size=1",null)).path("nextResultId").asText()).isNotBlank();
        assertThat(f.count("prepared_result_command")).isEqualTo(2);assertThat(f.count("prepared_score_request")).isEqualTo(1);assertThat(f.f.status().submitted()).isTrue();
        Path root=Path.of("").toAbsolutePath().getParent(),log=root.resolve(".runtime/teacher-review-browser-"+round+".log");
        var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-review.spec.ts","--workers=1","--retries=0");builder.directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13015");env.put("PREPARED_REVIEW_ASSESSMENT",Integer.toString(f.assessment));
        env.put("PREPARED_REVIEW_ACCOUNT",f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,f.actor));env.put("PREPARED_REVIEW_ANSWER",transcript.answerId());
        // Recreate the actual canonical original from the same native source and verify its full
        // proof before giving it to browser tests. Cloud URL/authorization success is an explicit
        // route double; it cannot count as real private-cloud integration.
        var media=f.service.reviewMedia(f.actor,f.assessment,f.attempt,transcript.answerId());
        int ordinal=f.jdbc.queryForObject("SELECT ordinal FROM prepared_answer WHERE id=?",Integer.class,transcript.answerId());
        String mediaJob=f.jdbc.queryForObject("SELECT job_id FROM prepared_answer_media WHERE answer_id=?",String.class,transcript.answerId());
        var sourceProof=json.readValue(f.f.m.jobs.get(mediaJob).orElseThrow().outputJson(),system.assessment.defense.prepared.media.PreparedAnswerMedia.Proof.class);
        Path wave=folder.toRealPath().resolve("browser-original-"+transcript.answerId()+".wav");var nativeCodec=new system.assessment.defense.prepared.media.PreparedAnswerMedia(Path.of("/Users/duanhangyu/.homebrew/bin/ffmpeg").toRealPath(),Path.of("/Users/duanhangyu/.homebrew/bin/ffprobe").toRealPath());
        assertThat(nativeCodec.verify(List.of(new system.assessment.defense.prepared.media.PreparedAnswerMedia.SegmentInput(sourceProof.segments().getFirst().id(),1,"audio/webm;codecs=opus",folder.resolve("q"+ordinal).resolve("native.webm").toRealPath(),1000)),120,wave).audio()).isEqualTo(media.proof());
        env.put("PREPARED_REVIEW_AUDIO_FILE",wave.toString());env.put("PREPARED_REVIEW_AUDIO_SCOPE",json.writeValueAsString(Map.of("actorId",f.actor,"assessmentId",f.assessment,"studentId",f.student,"attemptId",f.attempt,"answerId",transcript.answerId(),"sha256",media.proof().sha256(),"byteSize",media.proof().byteSize(),"durationMs",media.proof().durationMs(),"objectKey",media.objectKey())));
        // This spec contains two independent scenarios, each still limited to 30 seconds.
        // The outer watchdog also bounds browser startup/reporting; it must not kill a
        // valid second scenario merely because the first used its own allowed budget.
        var process=builder.start();try{assertThat(process.waitFor(90,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as("scoped teacher review browser "+log.getFileName()).isZero();}finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
        assertThat(f.count("prepared_manual_score")).isEqualTo(2);
        assertThat(f.service.manualHistory(f.actor,f.assessment,f.attempt,transcript.answerId(),null,10).current().score()).isEqualByComparingTo("9");
    }
    @Test void realManualRoutesRequireCurrentCookieManagerAndPreserveOriginalReceiptWithoutModels()throws Exception{
        String answer=f.jdbc.queryForObject("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal LIMIT 1",String.class,f.assessment),path=answer(answer);
        var command=new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),0,new java.math.BigDecimal("8.5"),"真实 HTTP 人工核验原录音依据");
        for(var c:List.of(student,unrelated)){assertThat(request(c,"POST",path+"/adjust",command).statusCode()).isEqualTo(403);assertThat(request(c,"GET",path+"/manual-history",null).statusCode()).isEqualTo(403);}
        String raw=json.writeValueAsString(command);for(String origin:Arrays.asList(null,"https://untrusted.invalid"))assertThat(raw(teacher,"POST",path+"/adjust",raw,origin,"application/json",null).statusCode()).isEqualTo(403);
        for(String invalid:List.of(raw.replace("\"score\":8.5","\"score\":\"8.5\""),raw.replace("\"score\":8.5","\"score\":10.01"),raw.replace("\"score\":8.5","\"score\":8.001"),raw.replace("\"expectedRevision\":0,",""),raw.substring(0,raw.length()-1)+",\"actorId\":1}"))
            assertThat(raw(teacher,"POST",path+"/adjust",invalid,"http://127.0.0.1:13015","application/json",null).statusCode()).isEqualTo(400);
        for(String query:List.of("?size=0","?size=21","?afterManualScoreId=bad"))assertThat(request(teacher,"GET",path+"/manual-history"+query,null).statusCode()).isEqualTo(400);
        assertThat(success(request(teacher,"GET",path+"/manual-history",null)).path("history")).isEmpty();var receipt=success(request(teacher,"POST",path+"/adjust",command));
        for(int n=0;n<3;n++)assertThat(success(request(teacher,"POST",path+"/adjust",command))).isEqualTo(receipt);
        var manual=success(request(teacher,"GET",path,null));assertThat(manual.path("transcript").isNull()).isTrue();assertThat(manual.path("score").isNull()).isTrue();assertThat(manual.path("manualScore").path("recordedBy").asInt()).isEqualTo(f.actor);
        assertThat(manual.path("manualScore").path("score").decimalValue()).isEqualByComparingTo("8.5");
        assertThat(request(teacher,"POST",path+"/adjust",new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),0,java.math.BigDecimal.ONE,"过期版本")).statusCode()).isEqualTo(409);
        success(request(admin,"POST",path+"/adjust",new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),1,new java.math.BigDecimal("9"),"管理员按原标准核验")));
        assertThat(success(request(teacher,"POST",path+"/adjust",command))).isEqualTo(receipt);
        var history=success(request(teacher,"GET",path+"/manual-history?size=1",null));assertThat(history.path("history")).hasSize(1);assertThat(history.path("nextManualScoreId").asText()).isNotBlank();
        assertThat(history.toString()).doesNotContain("objectKey","inputJson","provider","model");assertThat(f.count("prepared_manual_score")).isEqualTo(2);assertThat(f.count("prepared_processing_result")).isZero();assertThat(f.f.status().submitted()).isTrue();
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void historicalOriginalReviewUsesActualCookiesAndBoundedPagesWithoutAnyHistoricalWrite(int round)throws Exception{
        var transcript=f.transcribe(f.claim(Kind.TRANSCRIBE));var score=f.score(f.claim(Kind.SCORE),"7.5");String answer=transcript.answerId(),path=answer(answer);
        var manual=success(request(teacher,"POST",path+"/adjust",new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),2,new java.math.BigDecimal("8.75"),"原录音历史人工依据")));
        String submission=f.jdbc.queryForObject("SELECT id FROM prepared_submission WHERE attempt_id=?",String.class,f.attempt);int jobs=f.count("prepared_job");
        // Only a scoped history fixture. This does not prove real retake authorization.
        for(int number=2;number<=12;number++){String id=UUID.randomUUID().toString();
            f.jdbc.update("INSERT INTO prepared_attempt(id,record_id,assessment_id,student_id,attempt_no,paper_version_id,started_at) SELECT ?,record_id,assessment_id,student_id,?,paper_version_id,CURRENT_TIMESTAMP(3) FROM prepared_attempt WHERE id=?",id,number,f.attempt);historicalFixtures.add(id);}
        String active=historicalFixtures.getLast();f.jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",active,f.assessment,f.student);
        String listing=root()+"/students/"+f.student+"/attempts";
        assertThat(request(HttpClient.newHttpClient(),"GET",listing,null).statusCode()).isEqualTo(401);
        for(var c:List.of(student,unrelated))for(String target:List.of(listing,interview(),path,path+"/manual-history",path+"/audio"))assertThat(request(c,"GET",target,null).statusCode()).isEqualTo(403);
        for(String query:List.of("?size=0","?size=21","?afterAttemptNo=-1","?afterAttemptNo=1.5"))assertThat(request(teacher,"GET",listing+query,null).statusCode()).isEqualTo(400);
        assertThat(request(teacher,"GET",listing+"?afterAttemptNo=13",null).statusCode()).isEqualTo(409);
        assertThat(request(teacher,"GET",listing.replace("/students/"+f.student,"/students/"+f.extraUsers.getLast()),null).statusCode()).isEqualTo(403);
        var first=success(request(teacher,"GET",listing,null));assertThat(first.path("attempts")).hasSize(10);assertThat(first.path("activeAttemptId").asText()).isEqualTo(active);assertThat(first.path("nextAttemptNo").asInt()).isEqualTo(3);
        var last=success(request(teacher,"GET",listing+"?afterAttemptNo=3",null));assertThat(last.path("attempts")).hasSize(2);assertThat(last.path("attempts").get(1).path("id").asText()).isEqualTo(f.attempt);assertThat(last.path("attempts").get(1).path("current").asBoolean()).isFalse();
        for(var c:List.of(teacher,admin)){assertThat(success(request(c,"GET",interview(),null)).path("current").asBoolean()).isFalse();
            assertThat(success(request(c,"GET",path,null)).path("manualScore").path("score").decimalValue()).isEqualByComparingTo("8.75");success(request(c,"GET",listing,null));
            assertThat(request(c,"GET",path+"/audio",null).statusCode()).isEqualTo(503);}
        f.jdbc.update("INSERT INTO assessment_invite(assessment_id,invitee_teacher_id,inviter_teacher_id) VALUES (?,?,?)",f.assessment,f.extraUsers.getFirst(),f.actor);
        try{for(String target:List.of(listing,interview(),path,path+"/manual-history"))success(request(unrelated,"GET",target,null));}
        finally{f.jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=? AND invitee_teacher_id=?",f.assessment,f.extraUsers.getFirst());}
        assertThat(request(unrelated,"GET",path+"/audio",null).statusCode()).isEqualTo(403);
        for(String target:List.of("adjust","confirm","regrade")){Object command=switch(target){
            case "adjust"->new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),manual.path("revision").asLong(),java.math.BigDecimal.ONE,"不可改写原历史");
            case "confirm"->new PreparedProcessing.ConfirmCommand(UUID.randomUUID().toString(),manual.path("revision").asLong(),score.id(),"不可重新确认历史");
            default->new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),manual.path("revision").asLong(),transcript.id(),"不可重启历史任务");};
            assertThat(request(teacher,"POST",path+"/"+target,command).statusCode()).isEqualTo(403);}
        f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.actor);try{assertThat(request(teacher,"GET",listing,null).statusCode()).isEqualTo(403);assertThat(request(teacher,"GET",path,null).statusCode()).isEqualTo(403);}
        finally{f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.actor);}
        Path root=Path.of("").toAbsolutePath().getParent(),log=root.resolve(".runtime/historical-review-browser-"+round+".log");
        var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-history.spec.ts","--workers=1","--retries=0");builder.directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13015");env.put("PREPARED_REVIEW_ASSESSMENT",Integer.toString(f.assessment));env.put("PREPARED_REVIEW_ACCOUNT",f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,f.actor));env.put("PREPARED_REVIEW_ANSWER",answer);
        env.put("PREPARED_REVIEW_OLD_ATTEMPT",f.attempt);env.put("PREPARED_REVIEW_ACTIVE_ATTEMPT",active);
        var media=f.service.reviewMedia(f.actor,f.assessment,f.attempt,answer);int ordinal=f.jdbc.queryForObject("SELECT ordinal FROM prepared_answer WHERE id=?",Integer.class,answer);
        String mediaJob=f.jdbc.queryForObject("SELECT job_id FROM prepared_answer_media WHERE answer_id=?",String.class,answer);var proof=json.readValue(f.f.m.jobs.get(mediaJob).orElseThrow().outputJson(),system.assessment.defense.prepared.media.PreparedAnswerMedia.Proof.class);
        Path wave=folder.toRealPath().resolve("browser-original-"+answer+".wav");var codec=new system.assessment.defense.prepared.media.PreparedAnswerMedia(Path.of("/Users/duanhangyu/.homebrew/bin/ffmpeg").toRealPath(),Path.of("/Users/duanhangyu/.homebrew/bin/ffprobe").toRealPath());
        assertThat(codec.verify(List.of(new system.assessment.defense.prepared.media.PreparedAnswerMedia.SegmentInput(proof.segments().getFirst().id(),1,"audio/webm;codecs=opus",folder.resolve("q"+ordinal).resolve("native.webm").toRealPath(),1000)),120,wave).audio()).isEqualTo(media.proof());
        env.put("PREPARED_REVIEW_AUDIO_FILE",wave.toString());env.put("PREPARED_REVIEW_AUDIO_SCOPE",json.writeValueAsString(Map.of("actorId",f.actor,"assessmentId",f.assessment,"studentId",f.student,"attemptId",f.attempt,"answerId",answer,"sha256",media.proof().sha256(),"byteSize",media.proof().byteSize(),"durationMs",media.proof().durationMs(),"objectKey",media.objectKey())));
        var process=builder.start();try{assertThat(process.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as("historical review browser "+log.getFileName()).isZero();}finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
        assertThat(f.count("prepared_attempt")).isEqualTo(12);assertThat(f.count("prepared_job")).isEqualTo(jobs);assertThat(f.count("prepared_manual_score")).isEqualTo(1);assertThat(f.count("prepared_result_command")).isEqualTo(1);assertThat(f.count("prepared_processing_result")).isEqualTo(2);assertThat(f.count("prepared_score_request")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT id FROM prepared_submission WHERE attempt_id=?",String.class,f.attempt)).isEqualTo(submission);
    }
}
