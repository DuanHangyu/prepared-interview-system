package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import system.assessment.defense.prepared.processing.PreparedProcessing;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Actual owned DB, cookies, HTTP and browser downloads. Original media, scores and
 * historical pointer are synthetic fixtures, NOT real providers, devices or retakes. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedReviewReportLiveHttpTest {
    final PreparedReviewSummaryLiveHttpTest f=new PreparedReviewSummaryLiveHttpTest();@TempDir Path folder;
    @BeforeEach void setup()throws Exception{f.folder=folder;f.setup();}
    @AfterEach void cleanup()throws Exception{f.cleanup();}
    String path(String kind,String version){return f.http.interview()+"/"+kind+"?resultVersion="+version;}
    int exports(){return f.http.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='TEACHER_REPORT_EXPORT_AUTHORIZED'",Integer.class,f.http.f.assessment);}
    String version()throws Exception{return f.http.success(f.http.request(f.http.teacher,"GET",f.summary(),null)).path("resultVersion").asText();}
    HttpResponse<byte[]> request(HttpClient client,String target)throws Exception{
        return client.send(HttpRequest.newBuilder(URI.create(f.http.base+target)).timeout(Duration.ofSeconds(15)).header("Origin","http://127.0.0.1:13019").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    com.fasterxml.jackson.databind.JsonNode report(HttpClient client,String version)throws Exception{
        var response=request(client,path("report",version));assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var payload=f.http.json.readTree(response.body());assertThat(payload.path("code").asInt()).isEqualTo(200);return payload.path("data");
    }
    void rejected(HttpClient client,String target,int status)throws Exception{
        rejected(client,target,status,status);
    }
    void permission(HttpClient client,String target)throws Exception{
        // Preserve the reused object's exact NO_PERMISSION business code; transport
        // remains strictly 403. Foreign-original and annotation denials use 403.
        rejected(client,target,403,system.assessment.defense.infrastructure.emuns.ErrorCodeEnums.NO_PERMISSION.getCode());
    }
    void rejected(HttpClient client,String target,int status,int businessCode)throws Exception{
        int before=exports();var response=request(client,target);assertThat(response.statusCode()).as("scoped report rejection").isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();assertThat(f.http.json.readTree(response.body()).path("code").asInt()).isEqualTo(businessCode);
        assertThat(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("真实 HTTP 人工核验测试依据","objectKey","snapshot_sha256","StackTrace");assertThat(exports()).isEqualTo(before);
    }
    String historical(){
        var h=f.http;String active=UUID.randomUUID().toString();h.f.jdbc.update("INSERT INTO prepared_attempt(id,record_id,assessment_id,student_id,attempt_no,paper_version_id,started_at) SELECT ?,record_id,assessment_id,student_id,2,paper_version_id,CURRENT_TIMESTAMP(3) FROM prepared_attempt WHERE id=?",active,h.f.attempt);
        h.historicalFixtures.add(active);h.f.jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",active,h.f.assessment,h.f.student);return active;
    }
    void workbook(java.io.InputStream input,boolean current,String version,int actor)throws Exception{
        try(var book=new XSSFWorkbook(input)){
            assertThat(book.getNumberOfSheets()).isEqualTo(3);var values=new HashMap<String,String>();for(var row:book.getSheet("结果摘要"))values.put(row.getCell(0).getStringCellValue(),row.getCell(1).getStringCellValue());
            assertThat(values).containsEntry("学生标识",Integer.toString(f.http.f.student)).containsEntry("管理操作者",Integer.toString(actor)).containsEntry("原尝试标识",f.http.f.attempt)
                .containsEntry("尝试序号","1").containsEntry("尝试范围",current?"当前尝试":"历史尝试，只读").containsEntry("学生结果设置","尚未开放").containsEntry("结果读取版本",version).containsEntry("总分","15.75");
            assertThat(values.get("说明")).contains("不表示向学生公开").doesNotContain("仅包含本次公开结果");
            assertThat(book.getSheet("逐题结果").getLastRowNum()).isEqualTo(3);assertThat(book.getSheet("逐题结果").getRow(1).getCell(3).getStringCellValue()).isEqualTo("0");
            assertThat(book.getSheet("回答转写").getRow(1).getCell(2).getStringCellValue()).contains("暂无有效转写","不表示未回答");
            for(var sheet:book)for(var row:sheet)for(var cell:row){assertThat(cell.getCellType()).isEqualTo(CellType.STRING);assertThat(cell.getHyperlink()).isNull();assertThat(cell.getStringCellValue()).doesNotContain("真实 HTTP 人工核验测试依据","不应泄漏的教师标准","objectKey");}
        }
    }
    void download(HttpClient client,String version,boolean current,int actor)throws Exception{
        int before=exports();var response=request(client,path("export.xlsx",version));assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.headers().firstValue("Content-Disposition").orElse("")).isEqualTo("attachment; filename=\"prepared-review-"+f.http.f.assessment+"-"+f.http.f.attempt+".xlsx\"");
        assertThat(response.headers().firstValue("X-Prepared-Result-Version").orElse("")).isEqualTo(version);assertThat(response.headers().firstValue("Content-Length").orElse("")).isEqualTo(Integer.toString(response.body().length));
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");workbook(new java.io.ByteArrayInputStream(response.body()),current,version,actor);assertThat(exports()).isEqualTo(before+1);
        var audit=f.http.f.jdbc.queryForMap("SELECT actor_id,original_student_id,original_attempt_id,resource_id,resource_sha256,resource_version FROM prepared_audit WHERE assessment_id=? AND action='TEACHER_REPORT_EXPORT_AUTHORIZED' ORDER BY id DESC LIMIT 1",f.http.f.assessment);
        assertThat(((Number)audit.get("actor_id")).intValue()).isEqualTo(actor);assertThat(audit.get("resource_id")).isNull();
        assertThat(((Number)audit.get("original_student_id")).intValue()).isEqualTo(f.http.f.student);assertThat(audit.get("original_attempt_id")).isEqualTo(f.http.f.attempt);
        assertThat(audit.get("resource_sha256")).isEqualTo(version);assertThat(((Number)audit.get("resource_version")).intValue()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void actualManagerReportAndBinaryExportRequireFreshScopeExactVersionAndCoherentOriginal(int round)throws Exception{
        var h=f.http;for(String kind:List.of("report","export.xlsx"))rejected(h.teacher,path(kind,"a".repeat(64)),409);
        f.human();h.f.jdbc.update("UPDATE assessment SET show_result=0 WHERE id=?",h.f.assessment);String version=version();int jobs=h.f.count("prepared_job");
        for(String kind:List.of("report","export.xlsx")){
            String target=path(kind,version);rejected(HttpClient.newHttpClient(),target,401);rejected(h.student,target,403);permission(h.unrelated,target);
            rejected(h.teacher,path(kind,"bad"),400);rejected(h.teacher,path(kind,"a".repeat(64)),409);rejected(h.teacher,target.replace(h.f.attempt,UUID.randomUUID().toString()),403);
            rejected(h.teacher,h.interview()+"/"+kind,400);
        }
        for(var client:List.of(h.teacher,h.admin)){var value=report(client,version);assertThat(value.path("schemaVersion").asInt()).isEqualTo(1);assertThat(value.path("answers")).hasSize(3);
            assertThat(value.path("summary").path("actorId").asInt()).isEqualTo(client==h.teacher?h.f.actor:h.f.extraUsers.getLast());assertThat(value.path("summary").path("showResult").asBoolean()).isFalse();
            assertThat(value.toString()).doesNotContain("真实 HTTP 人工核验测试依据","不应泄漏的教师标准","rubric","objectKey","provider","inputJson","url");download(client,version,true,client==h.teacher?h.f.actor:h.f.extraUsers.getLast());}
        assertThat(report(h.teacher,version).path("summary").path("studentId").asInt()).isEqualTo(h.f.student);
        var selected=request(h.teacher,path("report",version)+"&actorId="+h.f.extraUsers.getLast()+"&studentId=1");assertThat(f.http.json.readTree(selected.body()).path("data").path("summary").path("actorId").asInt()).isEqualTo(h.f.actor);
        int invitee=h.f.extraUsers.getFirst();h.f.jdbc.update("INSERT INTO assessment_invite(assessment_id,invitee_teacher_id,inviter_teacher_id) VALUES (?,?,?)",h.f.assessment,invitee,h.f.actor);
        try{assertThat(report(h.unrelated,version).path("summary").path("actorId").asInt()).isEqualTo(invitee);download(h.unrelated,version,true,invitee);}finally{h.f.jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=? AND invitee_teacher_id=?",h.f.assessment,invitee);}
        for(String kind:List.of("report","export.xlsx"))permission(h.unrelated,path(kind,version));
        String original=h.f.jdbc.queryForObject("SELECT snapshot_sha256 FROM prepared_submission WHERE attempt_id=?",String.class,h.f.attempt);h.f.jdbc.update("UPDATE prepared_submission SET snapshot_sha256=REPEAT('f',64) WHERE attempt_id=?",h.f.attempt);
        try{for(String kind:List.of("report","export.xlsx"))rejected(h.teacher,path(kind,version),500);}finally{h.f.jdbc.update("UPDATE prepared_submission SET snapshot_sha256=? WHERE attempt_id=?",original,h.f.attempt);}
        h.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",h.f.actor);try{for(String kind:List.of("report","export.xlsx"))rejected(h.teacher,path(kind,version),403);}finally{h.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",h.f.actor);}
        h.f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",h.f.assessment);try{for(String kind:List.of("report","export.xlsx"))permission(h.teacher,path(kind,version));}finally{h.f.jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",h.f.assessment);}
        h.success(h.request(h.teacher,"POST",h.answer(f.answers().getFirst())+"/adjust",new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),1,new BigDecimal("1.25"),"后续内部依据")));
        for(String kind:List.of("report","export.xlsx"))rejected(h.teacher,path(kind,version),409);String changed=version();h.f.jdbc.update("UPDATE assessment SET show_result=1 WHERE id=?",h.f.assessment);
        for(String kind:List.of("report","export.xlsx"))rejected(h.teacher,path(kind,changed),409);h.f.jdbc.update("UPDATE assessment SET show_result=0 WHERE id=?",h.f.assessment);String current=version();historical();
        for(String kind:List.of("report","export.xlsx"))rejected(h.teacher,path(kind,current),409);String history=version();
        h.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",h.f.assessment,h.f.student);h.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",h.f.student);
        try{var old=report(h.teacher,history);assertThat(old.path("summary").path("current").asBoolean()).isFalse();assertThat(old.path("summary").path("totalScore").decimalValue()).isEqualByComparingTo("17");
            var bytes=request(h.teacher,path("export.xlsx",history));assertThat(bytes.statusCode()).isEqualTo(200);
        }finally{h.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",h.f.student);h.f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",h.f.assessment,h.f.student);}
        assertThat(h.f.count("prepared_job")).isEqualTo(jobs);assertThat(h.f.count("prepared_processing_result")).isZero();assertThat(h.f.count("prepared_manual_score")).isEqualTo(4);
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    @EnabledIfEnvironmentVariable(named="PREPARED_BROWSER_FIXTURES",matches="true")
    void actualCurrentAndHistoricalReportsDownloadAndRecoverRepeatedlyWithoutBusinessWrites(int round)throws Exception{
        var h=f.http;f.human();h.f.jdbc.update("UPDATE assessment SET show_result=0 WHERE id=?",h.f.assessment);int jobs=h.f.count("prepared_job");
        String submission=h.f.jdbc.queryForObject("SELECT id FROM prepared_submission WHERE attempt_id=?",String.class,h.f.attempt),completed= h.f.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,h.f.attempt).toInstant().toString();
        browser(round,"current",null,version());h.teacher=h.login(h.f.actor,System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));String active=historical();browser(round,"historical",active,version());
        assertThat(exports()).isEqualTo(2);assertThat(h.f.count("prepared_job")).isEqualTo(jobs);assertThat(h.f.count("prepared_manual_score")).isEqualTo(3);assertThat(h.f.count("prepared_result_command")).isEqualTo(3);assertThat(h.f.count("prepared_attempt")).isEqualTo(2);
        assertThat(h.f.jdbc.queryForObject("SELECT id FROM prepared_submission WHERE attempt_id=?",String.class,h.f.attempt)).isEqualTo(submission);assertThat(h.f.jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=?",java.sql.Timestamp.class,h.f.attempt).toInstant().toString()).isEqualTo(completed);
    }
    void browser(int round,String mode,String active,String version)throws Exception{
        Path root=Path.of("").toAbsolutePath().getParent();Path scope=Files.createTempDirectory(root.resolve(".runtime"),"review-report-browser-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-review-report.spec.ts","--grep",mode,"--workers=1","--retries=0","--output",scope.resolve("artifacts").toString()).directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(scope.resolve("browser.log").toFile());
        var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13019");env.put("PREPARED_REVIEW_ASSESSMENT",Integer.toString(f.http.f.assessment));env.put("PREPARED_REVIEW_ACCOUNT",f.http.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,f.http.f.actor));env.put("PREPARED_REVIEW_OLD_ATTEMPT",f.http.f.attempt);
        env.put("PREPARED_REVIEW_REPORT_DOWNLOAD",scope.resolve("report.xlsx").toString());if(active!=null)env.put("PREPARED_REVIEW_ACTIVE_ATTEMPT",active);
        var process=builder.start();try{assertThat(process.waitFor(90,TimeUnit.SECONDS)).as("bounded manager report browser %s round %s",mode,round).isTrue();assertThat(process.exitValue()).as("manager report browser %s round %s: %s",mode,round,scope.getFileName()).isZero();}
        finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
        try(var input=Files.newInputStream(scope.resolve("report.xlsx"))){workbook(input,"current".equals(mode),version,f.http.f.actor);}
    }
}
