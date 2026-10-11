package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Actual session/DB/Redis/streaming route negative checks. Disabled storage is NOT a positive OSS integration. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAnswerUploadLiveHttpTest {
    final PreparedStudentAttemptLiveHttpTest http=new PreparedStudentAttemptLiveHttpTest();
    String path,answer;
    @BeforeEach void setup()throws Exception {
        http.setup();var grant=http.g.grant(1);answer=grant.answerId();
        path=http.attemptPath("/answers/"+answer+"/chunks/"+UUID.randomUUID()+"/0")+"?captureClientId="+http.g.client
                +"&segmentOrdinal=1&mimeType=audio%2Fwebm%3Bcodecs%3Dopus&byteSize=3&sha256="+"a".repeat(64);
    }
    @AfterEach void cleanup()throws Exception {http.cleanup();}
    HttpResponse<String> put(HttpClient client,byte[] bytes,String origin)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(http.base+path)).timeout(Duration.ofSeconds(8)).header("Content-Type","application/octet-stream");
        if(origin!=null)request.header("Origin",origin);
        return client.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void realByteRouteRejectsAnonymousTeacherOtherStudentAndCsrfWithoutReservation()throws Exception {
        assertThat(put(HttpClient.newHttpClient(),new byte[3],PreparedStudentAttemptLiveHttpTest.origin()).statusCode()).isEqualTo(401);
        assertThat(put(http.teacher,new byte[3],PreparedStudentAttemptLiveHttpTest.origin()).statusCode()).isEqualTo(403);
        assertThat(put(http.outsider,new byte[3],PreparedStudentAttemptLiveHttpTest.origin()).statusCode()).isEqualTo(403);
        assertThat(put(http.student,new byte[3],"https://untrusted.invalid").statusCode()).isEqualTo(403);
        assertThat(put(http.student,new byte[3],null).statusCode()).isEqualTo(403);
        assertThat(http.g.count("prepared_answer_chunk")).isZero();
    }
    @Test void unavailableIndependentStoreIsExplicit503NotAFakeSuccessfulUpload()throws Exception {
        var response=put(http.student,new byte[3],PreparedStudentAttemptLiveHttpTest.origin());
        assertThat(response.statusCode()).isEqualTo(503);PreparedStudentAttemptLiveHttpTest.assertNoCache(response);
        assertThat(http.json.readTree(response.body()).path("code").asInt()).isEqualTo(503);
        assertThat(http.g.count("prepared_answer_chunk")).isZero();
        assertThat(http.g.f.jdbc.queryForObject("SELECT state FROM prepared_answer WHERE id=?",String.class,answer)).isEqualTo("RECORDING");
    }
    @Test void realReceiptQueryIsScopedAndDoesNotExposeReservationsOrObjects()throws Exception {
        String query=http.attemptPath("/answers/"+answer+"/chunks");
        assertThat(http.success(http.request(http.student,"GET",query,null)).isEmpty()).isTrue();
        assertThat(http.request(http.outsider,"GET",query,null).statusCode()).isEqualTo(403);
        assertThat(http.request(http.teacher,"GET",query,null).statusCode()).isEqualTo(403);
    }
    @Test void actualOversizeAndInvalidOriginalClaimsAreRejectedWithoutCloudMutation()throws Exception {
        assertThat(put(http.student,new byte[2097153],PreparedStudentAttemptLiveHttpTest.origin()).statusCode()).isEqualTo(413);
        path=path.replace("byteSize=3","byteSize=0");assertThat(put(http.student,new byte[0],PreparedStudentAttemptLiveHttpTest.origin()).statusCode()).isEqualTo(400);
        assertThat(http.g.count("prepared_answer_chunk")).isZero();
    }
    @Test void actualBundlePreparationHasSameSessionOwnershipCsrfAndUnavailableStorageBoundary()throws Exception {
        String route=http.attemptPath("/answers/"+answer+"/upload-bundles");
        var command=new system.assessment.defense.prepared.attempt.PreparedAnswerBundle.Command(http.g.client,UUID.randomUUID().toString(),1,"audio/webm",
            List.of(new system.assessment.defense.prepared.attempt.PreparedAnswerBundle.Chunk(0,3,"a".repeat(64))),3,"a".repeat(64));
        assertThat(http.request(HttpClient.newHttpClient(),"POST",route,command).statusCode()).isEqualTo(401);
        assertThat(http.request(http.teacher,"POST",route,command).statusCode()).isEqualTo(403);
        assertThat(http.request(http.outsider,"POST",route,command).statusCode()).isEqualTo(403);
        var unavailable=http.request(http.student,"POST",route,command);assertThat(unavailable.statusCode()).isEqualTo(503);PreparedStudentAttemptLiveHttpTest.assertNoCache(unavailable);
        var csrf=HttpRequest.newBuilder(URI.create(http.base+route)).header("Content-Type","application/json").header("Origin","https://untrusted.invalid").POST(HttpRequest.BodyPublishers.ofString(http.json.writeValueAsString(command))).build();
        assertThat(http.student.send(csrf,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(http.g.count("prepared_answer_upload_bundle")).isZero();assertThat(http.g.count("prepared_answer_chunk")).isZero();
    }
    @Test void actualBundleBinaryLookupAndMetadataOversizeCannotCreateFalseReceipt()throws Exception {
        String base=http.attemptPath("/answers/"+answer+"/upload-bundles");path=base+"/"+"a".repeat(64);
        assertThat(put(http.student,new byte[3],PreparedStudentAttemptLiveHttpTest.origin()).statusCode()).isEqualTo(403);
        assertThat(put(http.student,new byte[8388609],PreparedStudentAttemptLiveHttpTest.origin()).statusCode()).isEqualTo(413);
        assertThat(http.request(http.student,"GET",path,null).statusCode()).isEqualTo(404);
        assertThat(http.request(http.outsider,"GET",path,null).statusCode()).isEqualTo(403);
        assertThat(http.raw(http.student,"POST",base," ".repeat(32769),PreparedStudentAttemptLiveHttpTest.origin()).statusCode()).isEqualTo(413);
        assertThat(http.g.count("prepared_answer_upload_bundle")).isZero();
    }
    @Test void realWholeMediaEndpointRechecksSessionResourceCsrfAndDoesNotFakeUnavailableVerification()throws Exception {
        String media=http.attemptPath("/answers/"+answer+"/media");
        var command=new system.assessment.defense.prepared.attempt.PreparedAnswerMediaService.Command(http.g.client,"a".repeat(64));
        assertThat(http.request(HttpClient.newHttpClient(),"GET",media,null).statusCode()).isEqualTo(401);
        assertThat(http.request(http.teacher,"GET",media,null).statusCode()).isEqualTo(403);assertThat(http.request(http.outsider,"GET",media,null).statusCode()).isEqualTo(403);
        var initial=http.success(http.request(http.student,"GET",media,null));assertThat(initial.path("phase").asText()).isEqualTo("NOT_QUEUED");assertThat(initial.path("serverSaved").asBoolean()).isFalse();
        assertThat(http.request(http.student,"POST",media,command).statusCode()).isEqualTo(409);
        var manifest=new system.assessment.defense.prepared.attempt.PreparedAnswerEnd.Manifest(1,List.of(new system.assessment.defense.prepared.attempt.PreparedAnswerEnd.Segment(UUID.randomUUID().toString(),1,"audio/webm",1000L,2000L,"SEALED",null,1,3,List.of(new system.assessment.defense.prepared.attempt.PreparedAnswerEnd.Chunk(0,3,"a".repeat(64))))));
        var end=new system.assessment.defense.prepared.attempt.PreparedAnswerEnd.Command(UUID.randomUUID().toString(),http.g.client,manifest);
        var receipt=http.success(http.request(http.student,"POST",http.attemptPath("/answers/"+answer+"/end"),end));
        var original=new system.assessment.defense.prepared.attempt.PreparedAnswerMediaService.Command(http.g.client,receipt.path("manifestSha256").asText());
        var unavailable=http.request(http.student,"POST",media,original);assertThat(unavailable.statusCode()).isEqualTo(503);PreparedStudentAttemptLiveHttpTest.assertNoCache(unavailable);
        assertThat(http.raw(http.student,"POST",media,http.json.writeValueAsString(original),"https://untrusted.invalid").statusCode()).isEqualTo(403);
        assertThat(http.g.count("prepared_answer_media")).isZero();
    }
}
