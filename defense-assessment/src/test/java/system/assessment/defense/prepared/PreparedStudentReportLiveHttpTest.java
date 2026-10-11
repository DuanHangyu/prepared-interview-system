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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import system.assessment.defense.application.service.PasswordSecurityService;
import system.assessment.defense.prepared.attempt.PreparedAnswerMediaWorker;
import system.assessment.defense.prepared.processing.PreparedProcessing.AdjustCommand;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Actual cookie auth, application HTTP, independent DB and browser downloads. Original
 * media/providers are synthetic fixtures, never a cloud/physical/final-QA claim. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prepared-local")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PreparedStudentReportLiveHttpTest {
    @MockitoBean PreparedPrivateOssStore storage;
    @MockitoBean PreparedAnswerMediaWorker scheduledWorker;
    @LocalServerPort int port;
    @DynamicPropertySource static void isolatedRuntime(DynamicPropertyRegistry properties){PreparedQueuedMediaLiveHttpTest.isolatedRuntime(properties);}
    final PreparedStudentResultLiveTest f=new PreparedStudentResultLiveTest();@TempDir Path folder;
    final ObjectMapper json=new ObjectMapper();HttpClient student,teacher,other;int otherId;
    @BeforeEach void setup()throws Exception {
        f.folder=folder;f.setup();String password=Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));String hash=new PasswordSecurityService().encode(password);
        f.f.jdbc.update("UPDATE `user` SET password=? WHERE id IN (?,?)",hash,f.f.student,f.f.actor);
        String account="prepared-report-other-"+UUID.randomUUID();f.f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('独立报告权限夹具',?,?,0)",account,hash);
        otherId=f.f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);f.f.extraUsers.add(otherId);
        student=login(f.f.student,password);teacher=login(f.f.actor,password);other=login(otherId,password);
        var answers=f.answers();int n=0;for(String score:List.of("0","7.25","8.5"))f.f.service.adjust(f.f.actor,f.f.assessment,f.f.attempt,answers.get(n++),
            new AdjustCommand(UUID.randomUUID().toString(),0,new BigDecimal(score),"教师内部评分理由，不进入学生报告"));
    }
    @AfterEach void cleanup()throws Exception {
        try{for(var client:Arrays.asList(student,teacher,other))if(client!=null)request(client,"POST","/logout",Map.of());}
        finally {if(otherId>0)f.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",f.f.assessment,otherId);f.cleanup();}
    }
    String root(){return "/backend/prepared-results/"+f.f.assessment;}
    int exports(){return f.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='STUDENT_REPORT_EXPORT_AUTHORIZED'",Integer.class,f.f.assessment);}
    HttpClient login(int id,String password)throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
        String account=f.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,id);success(request(client,"POST","/login",Map.of("account",account,"password",password)));return client;
    }
    HttpResponse<byte[]> request(HttpClient client,String method,String path,Object input)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Origin","http://127.0.0.1:13015").header("Content-Type","application/json")
            .method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build();
        return client.send(request,HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonNode success(HttpResponse<byte[]> response)throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var payload=json.readTree(response.body());assertThat(payload.path("code").asInt()).isEqualTo(200);return payload.path("data");
    }
    void rejection(HttpClient client,String path,int status)throws Exception {
        var response=request(client,"GET",path,null);assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void actualCurrentReportDownloadCannotBypassIdentityVisibilityVersionOrIncompleteResult(int round)throws Exception {
        String version=success(request(student,"GET",root(),null)).path("resultVersion").asText();String report=root()+"/report?resultVersion="+version,export=root()+"/export.xlsx?resultVersion="+version;
        for(String path:List.of(report,export)) {
            rejection(HttpClient.newHttpClient(),path,401);rejection(teacher,path,403);rejection(other,path,403);
            rejection(student,path.replace(version,"bad"),400);rejection(student,path.replace(version,"a".repeat(64)),409);
        }
        var complete=success(request(student,"GET",report+"&studentId="+otherId,null));assertThat(complete.path("summary").path("studentId").asInt()).isEqualTo(f.f.student);
        assertThat(complete.path("answers").size()).isEqualTo(3);assertThat(complete.toString()).doesNotContain("教师内部评分理由","rubric","objectKey","jobId","provider","url");
        var workbook=request(student,"GET",export,null);assertThat(workbook.statusCode()).isEqualTo(200);
        assertThat(workbook.headers().firstValue("Content-Type").orElse("")).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(workbook.headers().firstValue("X-Prepared-Result-Version").orElse("")).isEqualTo(version);
        assertThat(workbook.headers().firstValue("Content-Length").orElse("")).isEqualTo(Integer.toString(workbook.body().length));
        checkWorkbook(new java.io.ByteArrayInputStream(workbook.body()));
        f.f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",f.f.assessment,otherId);
        for(String path:List.of(report,export))rejection(other,path,409);
        String first=f.answers().getFirst();f.f.service.adjust(f.f.actor,f.f.assessment,f.f.attempt,first,new AdjustCommand(UUID.randomUUID().toString(),1,new BigDecimal("1.25"),"原版本之后的内部核验"));
        for(String path:List.of(report,export))rejection(student,path,409);
        String current=success(request(student,"GET",root(),null)).path("resultVersion").asText();
        f.f.jdbc.update("UPDATE assessment SET show_result=0 WHERE id=?",f.f.assessment);
        for(String path:List.of(report.replace(version,current),export.replace(version,current)))rejection(student,path,403);
        assertThat(exports()).isEqualTo(1);assertThat(f.modelCalls()).isZero();verifyNoInteractions(storage,scheduledWorker);
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    @EnabledIfEnvironmentVariable(named="PREPARED_BROWSER_FIXTURES",matches="true")
    void nativeStudentReportAndRealDownloadRepeatWithRefreshOfflineRecoveryAndNoExtraTasks(int round)throws Exception {
        var root=Path.of("").toAbsolutePath().getParent();assertThat(root.getFileName().toString()).isEqualTo("prepared-interview-system");
        // Per-invocation private artifacts survive later rounds instead of overwriting
        // the failure evidence. No login/cookie traces or DB/admin env in the child.
        Path scope=Files.createTempDirectory(root.resolve(".runtime"),"student-report-browser-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-student-report.spec.ts","--workers=1","--retries=0","--output",scope.resolve("artifacts").toString());
        builder.directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(scope.resolve("browser.log").toFile());
        var inherited=new HashMap<>(builder.environment());var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);env.clear();
        for(String name:List.of("PATH","HOME","TMPDIR","LANG","LC_ALL","PLAYWRIGHT_BROWSERS_PATH","PREPARED_VALIDATION_BROWSER_DESCRIPTOR","PREPARED_VALIDATION_BROWSER_RUN_ID"))if(inherited.containsKey(name))env.put(name,inherited.get(name));
        env.put("PREPARED_TEST_LOGIN_PASSWORD",Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD")));env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13015");
        env.put("PREPARED_REPORT_ASSESSMENT",Integer.toString(f.f.assessment));env.put("PREPARED_REPORT_HTTP_PORT",Integer.toString(port));
        env.put("PREPARED_REPORT_ACCOUNT",f.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,f.f.student));env.put("PREPARED_REPORT_DOWNLOAD",scope.resolve("report.xlsx").toString());
        int jobs=f.f.count("prepared_job");var process=builder.start();
        try {assertThat(process.waitFor(90,TimeUnit.SECONDS)).as("scoped report browser finished").isTrue();assertThat(process.exitValue()).as("report browser round %s; protected artifact %s",round,scope.getFileName()).isZero();}
        finally {if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
        try(var input=Files.newInputStream(scope.resolve("report.xlsx"))){checkWorkbook(input);}
        assertThat(exports()).isEqualTo(1);assertThat(f.modelCalls()).isZero();assertThat(f.f.count("prepared_job")).isEqualTo(jobs);assertThat(f.f.f.status().submitted()).isTrue();verifyNoInteractions(storage,scheduledWorker);
    }
    void checkWorkbook(java.io.InputStream input)throws Exception {
        try(var workbook=new XSSFWorkbook(input)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);assertThat(workbook.getSheet("逐题结果").getLastRowNum()).isEqualTo(3);
            assertThat(workbook.getSheet("逐题结果").getRow(1).getCell(3).getStringCellValue()).isEqualTo("0");
            assertThat(workbook.getSheet("逐题结果").getRow(2).getCell(3).getStringCellValue()).isEqualTo("7.25");
            assertThat(workbook.getSheet("回答转写").getRow(1).getCell(2).getStringCellValue()).contains("暂无有效转写","不表示未回答");
        }
    }
}
