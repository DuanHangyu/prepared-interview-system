package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.prepared.attempt.PreparedQuestionGrant;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Free local metadata preflight, NOT M13 acceptance. Real distinct cookies/HTTP/MySQL/Redis
 * share ONE frozen paper. Audio assets are synthetic; no recording, upload, model or cloud calls.
 * Three simultaneous rounds test original-command replay, identity and the local 2s P95 target;
 * they do not represent three full 120-second recorded interviews or production capacity. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedConcurrentMetadataLiveTest {
    private final PreparedQuestionGrantLiveTest fixture=new PreparedQuestionGrantLiveTest();
    private final ObjectMapper json=new ObjectMapper();
    private final List<Student> students=new ArrayList<>();
    private final List<Integer> ownedUsers=new ArrayList<>();
    private final String base="http://127.0.0.1:13016";
    private ExecutorService network;
    private HttpClient http;
    private boolean fixtureReady;

    private static final class Student {
        final int id;
        final String startKey,clientId=key(),questionKey=key();
        String cookie,attempt,answer,deadline;
        Student(int id,String startKey){this.id=id;this.startKey=startKey;}
        @Override public String toString(){return "Student[temporary identity; cookie redacted]";}
    }
    private record Sample(double milliseconds,JsonNode data) {}
    @FunctionalInterface private interface Request { Sample send(Student student)throws Exception; }

    @BeforeEach void setup(){
        fixture.setup();fixtureReady=true;
        network=Executors.newVirtualThreadPerTaskExecutor();
        http=HttpClient.newBuilder().executor(network).connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach void cleanup()throws Exception{
        try{
            if(http!=null)for(var student:students)if(student.cookie!=null){
                try{send(student,"POST","/logout",Map.of(),200);}catch(Exception ignored){/* Owned fixture cleanup continues. */}
                student.cookie=null;
            }
        }finally{
            try{if(http!=null)http.close();}finally{
                try{if(network!=null)network.close();}finally{
                    if(fixtureReady)fixture.cleanupOnlyThisFixture();
                    for(int id:ownedUsers)fixture.f.jdbc.update("DELETE FROM `user` WHERE id=?",id);
                }
            }
        }
    }

    @ParameterizedTest(name="{0} distinct students, one frozen paper, three metadata rounds")
    @ValueSource(ints={100,120})
    void sharedPaperMetadataIsMeasuredWithoutWeakeningOriginalReceiptsOrIdentity(int count)throws Exception{
        createAndLogin(count);
        for(int round=1;round<=3;round++){
            var starts=burst(count,round,"start",s->send(s,"POST",path("/attempts"),Map.of("idempotencyKey",s.startKey),200));
            var seen=new HashSet<String>();
            for(int i=0;i<count;i++){
                var student=students.get(i);var result=starts.get(i).data();String attempt=result.path("id").asText();
                UUID.fromString(attempt);assertThat(seen.add(attempt)).as("no cross-student attempt reuse").isTrue();
                assertThat(result.path("assessmentId").asInt()).isEqualTo(fixture.f.assessment);
                assertThat(result.path("paperVersionId").asText()).isEqualTo(fixture.attempt.paperVersionId());
                assertThat(fixture.f.jdbc.queryForObject("SELECT student_id FROM prepared_attempt WHERE id=? AND assessment_id=?",Integer.class,attempt,fixture.f.assessment)).isEqualTo(student.id);
                if(student.attempt!=null)assertThat(attempt).isEqualTo(student.attempt);
                student.attempt=attempt;
            }
            var grants=burst(count,round,"question-start",s->send(s,"POST",attemptPath(s,"/questions/start"),
                    new PreparedQuestionGrant.Command(s.questionKey,s.clientId,1),200));
            seen.clear();
            for(int i=0;i<count;i++){
                var student=students.get(i);var result=grants.get(i).data();String answer=result.path("answerId").asText();
                assertThat(seen.add(answer)).as("no cross-student answer reuse").isTrue();
                assertThat(result.path("attemptId").asText()).isEqualTo(student.attempt);
                assertThat(result.path("paperVersionId").asText()).isEqualTo(fixture.attempt.paperVersionId());
                assertThat(result.path("ordinal").asInt()).isEqualTo(1);assertThat(result.path("answerSeconds").asInt()).isEqualTo(120);
                assertThat(result.has("rubric")||result.has("objectKey")||result.has("maxScore")).isFalse();
                assertThat(fixture.f.jdbc.queryForObject("SELECT student_id FROM prepared_answer WHERE id=? AND assessment_id=?",Integer.class,answer,fixture.f.assessment)).isEqualTo(student.id);
                if(student.answer!=null){assertThat(answer).isEqualTo(student.answer);assertThat(result.path("answerDeadline").asText()).isEqualTo(student.deadline);}
                student.answer=answer;student.deadline=result.path("answerDeadline").asText();
            }
            var reads=burst(count,round,"current-question",s->send(s,"GET",attemptPath(s,"/questions/current?captureClientId="+s.clientId),null,200));
            for(int i=0;i<count;i++){
                var student=students.get(i);var result=reads.get(i).data();
                assertThat(result.path("attemptId").asText()).isEqualTo(student.attempt);
                assertThat(result.path("answerId").asText()).isEqualTo(student.answer);
                assertThat(result.path("answerDeadline").asText()).isEqualTo(student.deadline);
            }
            for(String table:List.of("prepared_attempt","prepared_attempt_anchor","prepared_attempt_start_receipt","prepared_answer","prepared_question_start_receipt"))
                assertThat(fixture.count(table)).as("original rows remain unique across simultaneous replay").isEqualTo(count);
        }
        burst(count,4,"foreign-current-rejected",s->{
            var other=students.get((students.indexOf(s)+1)%count);
            return send(s,"GET",attemptPath(other,"/questions/current?captureClientId="+other.clientId),null,403);
        });
        assertThat(fixture.count("prepared_answer_end")).isZero();
        assertThat(fixture.count("prepared_submission")).isZero();
        assertThat(fixture.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_answer WHERE assessment_id=? AND state='SAVED'",Integer.class,fixture.f.assessment)).isZero();
    }

    private void createAndLogin(int count)throws Exception{
        String password=Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));
        String encoded=new PasswordSecurityService().encode(password);
        fixture.f.jdbc.update("UPDATE `user` SET password=? WHERE id=?",encoded,fixture.student);
        String originalKey=fixture.f.jdbc.queryForObject("SELECT idempotency_key FROM prepared_attempt_start_receipt WHERE assessment_id=? AND student_id=?",String.class,fixture.f.assessment,fixture.student);
        students.add(new Student(fixture.student,originalKey));
        for(int i=1;i<count;i++){
            String account="prepared-capacity-preflight-"+key();
            fixture.f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('本地并发预检',?,?,0)",account,encoded);
            int id=fixture.f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);ownedUsers.add(id);
            fixture.f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",fixture.f.assessment,id);
            students.add(new Student(id,key()));
        }
        for(var student:students){
            String account=fixture.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,student.id);
            var response=http.send(request(null,"POST","/login",Map.of("account",account,"password",password)),HttpResponse.BodyHandlers.ofString());
            data(response,200);
            var cookies=response.headers().allValues("Set-Cookie").stream().flatMap(h->HttpCookie.parse(h).stream())
                    .filter(c->c.getMaxAge()!=0).map(c->c.getName()+"="+c.getValue()).toList();
            assertThat(!cookies.isEmpty()).as("actual scoped login issued a cookie").isTrue();
            student.cookie=String.join("; ",cookies);
        }
    }

    private List<Sample> burst(int count,int round,String phase,Request request)throws Exception{
        var ready=new CountDownLatch(count);var go=new CountDownLatch(1);var tasks=new ArrayList<Future<Sample>>();
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            try{
                for(var student:students)tasks.add(workers.submit(()->{ready.countDown();go.await();return request.send(student);}));
                assertThat(ready.await(10,TimeUnit.SECONDS)).as("all distinct callers ready before simultaneous release").isTrue();go.countDown();
                var samples=new ArrayList<Sample>();for(var task:tasks)samples.add(task.get(15,TimeUnit.SECONDS));
                var times=samples.stream().mapToDouble(Sample::milliseconds).sorted().toArray();
                double p95=times[(int)Math.ceil(times.length*0.95)-1],max=times[times.length-1];
                System.out.printf(java.util.Locale.ROOT,"PREPARED_METADATA_PREFLIGHT students=%d round=%d phase=%s samples=%d p95_ms=%.3f max_ms=%.3f cloud=false capacity_acceptance=false%n",count,round,phase,times.length,p95,max);
                assertThat(p95).as("local simultaneous metadata P95; not full recording/capacity acceptance").isLessThanOrEqualTo(2000);
                return samples;
            }finally{go.countDown();}
        }
    }

    private Sample send(Student student,String method,String path,Object body,int status)throws Exception{
        long started=System.nanoTime();
        var response=http.send(request(student,method,path,body),HttpResponse.BodyHandlers.ofString());
        double elapsed=(System.nanoTime()-started)/1_000_000.0;
        return new Sample(elapsed,data(response,status));
    }
    private HttpRequest request(Student student,String method,String path,Object body)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(8))
                .header("Content-Type","application/json").header("Origin","http://127.0.0.1:13015");
        if(student!=null&&student.cookie!=null)builder.header("Cookie",student.cookie);
        return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    private JsonNode data(HttpResponse<String> response,int expected)throws Exception{
        // Do not render bodies, credentials, cookies or signed capabilities on assertion failure.
        assertThat(response.statusCode()).as("local metadata HTTP status").isEqualTo(expected);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var result=json.readTree(response.body());assertThat(result.path("code").asInt()).isEqualTo(expected);
        return result.path("data");
    }
    private String path(String suffix){return "/backend/prepared-interviews/"+fixture.f.assessment+suffix;}
    private String attemptPath(Student student,String suffix){return path("/attempts/"+student.attempt+suffix);}
    private static String key(){return UUID.randomUUID().toString();}
}
