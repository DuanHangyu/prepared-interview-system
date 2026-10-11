package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.prepared.attempt.PreparedAnswerMediaWorker;
import system.assessment.defense.prepared.audio.PreparedAudio;
import system.assessment.defense.prepared.jobs.PreparedJobLedger;
import system.assessment.defense.prepared.publication.*;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real cookie/role/assignment/HTTP/MySQL/Redis with explicit synthetic prepared
 * media and private-storage double. NOT actual TTS/cloud/device acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prepared-local")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PreparedQuestionAudioLiveHttpTest {
    @MockitoBean PreparedPrivateOssStore httpStore;
    @MockitoBean PreparedAnswerMediaWorker scheduledWorker;
    @LocalServerPort int port;
    @TempDir Path folder;
    @DynamicPropertySource static void isolatedRuntime(DynamicPropertyRegistry properties){PreparedQueuedMediaLiveHttpTest.isolatedRuntime(properties);}
    final PreparedAudioLiveTest f=new PreparedAudioLiveTest();final ObjectMapper json=new ObjectMapper();
    HttpClient student,teacher,outsider;int studentId,outsiderId;String attempt,answer,client=UUID.randomUUID().toString();
    JsonNode original;PreparedAudio.View asset;PreparedPrivateOssStore.Stored stored;
    @BeforeEach void setup()throws Exception {
        f.folder=folder;f.setup();asset=f.prepare();f.completeAllParts(asset);
        var lease=f.jobs.claim(PreparedJobLedger.Kind.ASSEMBLE,"question-broadcast-fixture",300).orElseThrow();stored=f.whole(asset);
        f.tx.executeWithoutResult(s->f.service.complete(lease,stored));
        var publications=new PreparedPublicationService(f.access,f.drafts,new PreparedPublicationRepository(f.jdbc),"prepared-fixture");
        var status=publications.status(f.actor,f.assessment);assertThat(status.publishable()).isTrue();
        f.tx.execute(s->publications.publish(f.actor,f.assessment,new PreparedPublication.Command(f.drafts.load(f.assessment).revision(),id(),status.preparedContentSha256())));
        String password=Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD")),hash=new PasswordSecurityService().encode(password);
        f.jdbc.update("UPDATE `user` SET password=? WHERE id=?",hash,f.actor);studentId=user(hash);outsiderId=user(hash);
        f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",f.assessment,studentId);
        student=login(studentId,password);teacher=login(f.actor,password);outsider=login(outsiderId,password);
        attempt=success(request(student,"POST",path("/attempts"),Map.of("idempotencyKey",id()))).path("id").asText();
        original=success(request(student,"POST",path("/attempts/"+attempt+"/questions/start"),Map.of("idempotencyKey",id(),"captureClientId",client,"ordinal",1)));
        answer=original.path("answerId").asText();
        doAnswer(invocation->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(invocation.getArgument(0,Object.class)).isEqualTo(stored);
            return new PreparedPrivateOssStore.Preview("https://prepared-interview-fixture.oss-cn-beijing.aliyuncs.com/"+stored.objectKey()+"?Signature=fixture",Instant.now().plusSeconds(300).toString());}).when(httpStore).preview(stored);
    }
    @AfterEach void cleanup()throws Exception {
        try{for(var http:Arrays.asList(student,teacher,outsider))if(http!=null)request(http,"POST","/logout",Map.of());}
        finally {
            if(f.jdbc!=null){for(String table:List.of("prepared_audit","prepared_answer_chunk","prepared_answer_upload_bundle","prepared_answer_segment","prepared_answer_end_receipt","prepared_answer_end","prepared_question_start_receipt","prepared_attempt_start_receipt","prepared_answer","prepared_attempt_anchor","prepared_attempt","student_assessment_record","prepared_publication_receipt","prepared_paper_gate","prepared_question_snapshot","prepared_paper_version","assessment_student_relation"))f.jdbc.update("DELETE FROM "+table+" WHERE assessment_id=?",f.assessment);
                for(int id:new int[]{studentId,outsiderId})if(id>0)f.jdbc.update("DELETE FROM `user` WHERE id=?",id);f.cleanOwnedRows();}
        }
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void originalFrozenBroadcastRepeatedlyChecksRealIdentityAndDoesNotChangeAnswerOrDeadline(int round)throws Exception {
        assertThat(request(HttpClient.newHttpClient(),"GET",audio(),null).statusCode()).isEqualTo(401);
        assertThat(request(teacher,"GET",audio(),null).statusCode()).isEqualTo(403);assertThat(request(outsider,"GET",audio(),null).statusCode()).isEqualTo(403);
        assertThat(request(student,"GET",audio().replace(answer,id()),null).statusCode()).isEqualTo(403);
        assertThat(request(student,"GET",audio().replace(client,id()),null).statusCode()).isEqualTo(409);
        assertThat(request(student,"GET",audio().replace(client,"invalid"),null).statusCode()).isEqualTo(400);verifyNoInteractions(httpStore);
        for(int i=0;i<5;i++){
            var response=request(student,"GET",audio()+"&studentId="+outsiderId,null);assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            var reply=success(response);assertThat(reply.path("studentId").asInt()).isEqualTo(studentId);assertThat(reply.path("answerId").asText()).isEqualTo(answer);assertThat(reply.path("assetId").asText()).isEqualTo(asset.id());
            assertThat(reply.path("paperVersionId")).isEqualTo(original.path("paperVersionId"));assertThat(reply.path("sha256").asText()).isEqualTo(stored.proof().sha256());
            assertThat(reply.toString()).doesNotContain("rubric","教师标准","orderedPartJobs","bodySha256","objectKey","accessKey");
        }
        doThrow(new IOException("secret-cloud-failure")).when(httpStore).preview(stored);
        var failed=request(student,"GET",audio(),null);assertThat(failed.statusCode()).isEqualTo(503);assertThat(failed.body()).contains("不阻止录音").doesNotContain("secret-cloud-failure","Signature");
        var unchanged=success(request(student,"GET",path("/attempts/"+attempt+"/questions/current?captureClientId="+client),null));assertThat(unchanged.path("answerDeadline")).isEqualTo(original.path("answerDeadline"));assertThat(unchanged.path("mayRecord").asBoolean()).isTrue();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_answer WHERE assessment_id=?",Integer.class,f.assessment)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=?",Integer.class,f.assessment)).isEqualTo(3);
        clearInvocations(httpStore);f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?",f.assessment);
        assertThat(request(student,"GET",audio(),null).statusCode()).isEqualTo(403);verifyNoInteractions(httpStore);
    }
    @Test void revokedAssignmentDuringPrivateIoAndDamagedFrozenAssetNeverDeliverALink()throws Exception {
        f.jdbc.update("UPDATE prepared_audio_asset SET content_sha256=? WHERE id=?","0".repeat(64),asset.id());
        assertThat(request(student,"GET",audio(),null).statusCode()).isEqualTo(503);verifyNoInteractions(httpStore);
        f.jdbc.update("UPDATE prepared_audio_asset SET content_sha256=? WHERE id=?",stored.proof().sha256(),asset.id());
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?",f.assessment);
            return new PreparedPrivateOssStore.Preview("https://prepared-interview-fixture.oss-cn-beijing.aliyuncs.com/"+stored.objectKey()+"?Signature=must-not-escape",Instant.now().plusSeconds(300).toString());}).when(httpStore).preview(stored);
        var denied=request(student,"GET",audio(),null);assertThat(denied.statusCode()).isEqualTo(403);assertThat(denied.body()).doesNotContain("must-not-escape","Signature");
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    @EnabledIfEnvironmentVariable(named="PREPARED_FORMAL_BROWSER_FIXTURES",matches="true")
    void authorizedSyntheticNativeBroadcastAndCaptureRunTogetherInTheRealBrowser(int round)throws Exception {
        // The browser owns its original start/question commands and journal; remove
        // only this setup fixture's unused synthetic attempt, never an actual recording.
        for(String table:List.of("prepared_audit","prepared_question_start_receipt","prepared_attempt_start_receipt","prepared_answer","prepared_attempt_anchor","prepared_attempt","student_assessment_record"))f.jdbc.update("DELETE FROM "+table+" WHERE assessment_id=?",f.assessment);
        f.jdbc.update("UPDATE prepared_paper_gate SET first_started_at=NULL WHERE assessment_id=?",f.assessment);
        var root=Path.of("").toAbsolutePath().getParent();assertThat(root.getFileName().toString()).isEqualTo("prepared-interview-system");
        var log=root.resolve(".runtime/question-audio-native-browser-"+round+".log");
        var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-question-audio.spec.ts","--workers=1","--retries=0");builder.directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13015");env.put("PREPARED_BROADCAST_HTTP_PORT",Integer.toString(port));env.put("PREPARED_BROADCAST_ASSESSMENT_ID",Integer.toString(f.assessment));
        env.put("PREPARED_BROADCAST_STUDENT_ACCOUNT",f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,studentId));env.put("PREPARED_BROADCAST_OBJECT_KEY",stored.objectKey());env.put("PREPARED_BROADCAST_WAVE_BASE64",Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(folder.resolve(asset.id()+".wav"))));
        var process=builder.start();try{assertThat(process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as("native browser fixture %s",log.getFileName()).isZero();}
        finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_answer WHERE assessment_id=?",Integer.class,f.assessment)).isEqualTo(1);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_answer_end WHERE assessment_id=?",Integer.class,f.assessment)).isEqualTo(1);
        verify(httpStore,atLeastOnce()).preview(stored);verify(httpStore,never()).putBroadcast(any(),any());verify(httpStore,never()).asrInput(any());
    }
    int user(String hash){String account="prepared-broadcast-"+id();f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('播报权限测试',?,?,0)",account,hash);return f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);}
    String path(String suffix){return "/backend/prepared-interviews/"+f.assessment+suffix;}
    String audio(){return path("/attempts/"+attempt+"/answers/"+answer+"/question-audio?captureClientId="+client);}
    HttpClient login(int id,String password)throws Exception {var http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String account=f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,id);success(request(http,"POST","/login",Map.of("account",account,"password",password)));return http;}
    HttpResponse<String> request(HttpClient http,String method,String path,Object input)throws Exception {return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).header("Origin","http://127.0.0.1:13015").header("Content-Type","application/json").method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode success(HttpResponse<String> response)throws Exception {assertThat(response.statusCode()).as("broadcast HTTP %s",response.body()).isEqualTo(200);var body=json.readTree(response.body());assertThat(body.path("code").asInt()).isEqualTo(200);return body.path("data");}
    static String id(){return UUID.randomUUID().toString();}
}
