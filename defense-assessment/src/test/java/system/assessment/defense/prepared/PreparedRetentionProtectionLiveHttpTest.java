package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.prepared.retention.PreparedRetentionProtection;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Three real session/HTTP/NEW MySQL/Redis rounds. No browser, object cleanup, cloud
 * or actual recording claim; inherited paper/audio metadata are synthetic fixtures. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedRetentionProtectionLiveHttpTest {
    final PreparedRetentionProtectionLiveTest f=new PreparedRetentionProtectionLiveTest();
    final ObjectMapper json=new ObjectMapper();
    HttpClient teacher,student,stranger,admin;int unrelatedId;
    final String base="http://127.0.0.1:"+System.getenv().getOrDefault("PREPARED_FORMAL_HTTP_PORT","13016");
    @BeforeEach void setup()throws Exception{
        f.setup();String password=Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));var security=new PasswordSecurityService();
        f.f.f.jdbc.update("UPDATE `user` SET password=? WHERE id IN (?,?)",security.encode(password),f.f.f.actor,f.f.student);
        teacher=login(f.f.f.actor,password);student=login(f.f.student,password);unrelatedId=f.user(1);int administrator=f.user(2);
        f.f.f.jdbc.update("UPDATE `user` SET password=? WHERE id IN (?,?)",security.encode(password),unrelatedId,administrator);
        stranger=login(unrelatedId,password);admin=login(administrator,password);
    }
    @AfterEach void cleanup()throws Exception{
        try{for(var client:Arrays.asList(teacher,student,stranger,admin))if(client!=null){try{request(client,"POST","/logout",Map.of());}finally{client.close();}}}
        finally{f.cleanup();}
    }
    String path(){return "/backend/prepared-reviews/"+f.f.f.assessment+"/students/"+f.f.student+"/attempts/"+f.f.attempt.id()+"/retention-protection";}
    HttpClient login(int id,String password)throws Exception{
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
        var account=f.f.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,id);
        assertThat(request(client,"POST","/login",Map.of("account",account,"password",password)).statusCode()).isEqualTo(200);return client;
    }
    HttpResponse<String> request(HttpClient client,String method,String path,Object input)throws Exception{return raw(client,method,path,input==null?null:json.writeValueAsString(input),"http://127.0.0.1:13015",null);}
    HttpResponse<String> raw(HttpClient client,String method,String path,String body,String origin,String encoding)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if(origin!=null)request.header("Origin",origin);if(encoding!=null)request.header("Content-Encoding",encoding);
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode success(HttpResponse<String> response)throws Exception{
        // Never include response bodies (reasons, receipts or sessions) in failure messages.
        assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var responseJson=json.readTree(response.body());assertThat(responseJson.path("code").asInt()).isEqualTo(200);return responseJson.path("data");
    }
    PreparedRetentionProtection.Command command(long revision,String action){return new PreparedRetentionProtection.Command(UUID.randomUUID().toString(),revision,action,"真实管理会话核验原尝试");}
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void originalReceiptsAndProtectionSurviveResponseLossWithoutChangingExam(int round)throws Exception{
        var original=f.f.f.jdbc.queryForMap("SELECT * FROM prepared_attempt WHERE id=?",f.f.attempt.id());
        var view=success(request(teacher,"GET",path(),null));assertThat(view.path("revision").asLong()).isZero();
        var protect=command(0,"PROTECT");var receipt=success(request(teacher,"POST",path(),protect));assertThat(receipt.path("held").asBoolean()).isTrue();
        var release=success(request(admin,"POST",path(),command(1,"RELEASE")));assertThat(release.path("held").asBoolean()).isFalse();
        for(int n=0;n<3;n++)assertThat(success(request(teacher,"POST",path(),protect))).isEqualTo(receipt);
        assertThat(success(request(teacher,"GET",path(),null)).path("latest")).isEqualTo(release);
        assertThat(request(teacher,"POST",path(),command(0,"PROTECT")).statusCode()).isEqualTo(409);
        assertThat(f.f.f.jdbc.queryForMap("SELECT * FROM prepared_attempt WHERE id=?",f.f.attempt.id())).isEqualTo(original);
        assertThat(f.f.count("prepared_retention_command")).isEqualTo(2);assertThat(f.f.count("prepared_retention_guard")).isEqualTo(1);
        assertThat(receipt.toString()).doesNotContain("objectKey","rubric","password","transcript","captureClientId");
    }
    @Test void actualRoleScopeSourceSizeAndRevocationAreEnforcedBeforeWrites()throws Exception{
        try(var anonymous=HttpClient.newHttpClient()){assertThat(request(anonymous,"GET",path(),null).statusCode()).isEqualTo(401);}
        var command=command(0,"PROTECT");for(var client:List.of(student,stranger)){
            assertThat(request(client,"GET",path(),null).statusCode()).isEqualTo(403);assertThat(request(client,"POST",path(),command).statusCode()).isEqualTo(403);
        }
        f.f.f.jdbc.update("INSERT INTO assessment_invite(assessment_id,invitee_teacher_id,inviter_teacher_id) VALUES (?,?,?)",f.f.f.assessment,unrelatedId,f.f.f.actor);
        success(request(stranger,"GET",path(),null));f.f.f.jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=?",f.f.f.assessment);
        assertThat(request(stranger,"GET",path(),null).statusCode()).isEqualTo(403);
        for(String origin:Arrays.asList(null,"https://untrusted.invalid"))assertThat(raw(teacher,"POST",path(),json.writeValueAsString(command),origin,null).statusCode()).isEqualTo(403);
        for(String body:List.of("{}",json.writeValueAsString(command).replace("\"expectedRevision\":0","\"expectedRevision\":\"0\"")))assertThat(raw(teacher,"POST",path(),body,"http://127.0.0.1:13015",null).statusCode()).isEqualTo(400);
        assertThat(raw(teacher,"POST",path(),"x".repeat(16385),"http://127.0.0.1:13015",null).statusCode()).isEqualTo(413);
        assertThat(raw(teacher,"POST",path(),json.writeValueAsString(command),"http://127.0.0.1:13015","gzip").statusCode()).isEqualTo(415);
        assertThat(request(teacher,"GET",path().replace(f.f.attempt.id(),UUID.randomUUID().toString()),null).statusCode()).isEqualTo(403);
        f.f.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.f.f.actor);
        try{assertThat(request(teacher,"GET",path(),null).statusCode()).isEqualTo(403);assertThat(request(teacher,"POST",path(),command).statusCode()).isEqualTo(403);}
        finally{f.f.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.f.f.actor);}
        assertThat(f.f.count("prepared_retention_command")).isZero();assertThat(f.f.count("prepared_retention_guard")).isZero();
    }
}
