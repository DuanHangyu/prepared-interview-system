package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Actual cookies/routes, independent DB and native original receipts. Storage/model upstream
 * doubles remain explicit; this is not real cloud/model/device/capacity/final QA. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedRecordBridgeLiveHttpTest {
    final PreparedReviewLiveHttpTest h=new PreparedReviewLiveHttpTest();@TempDir Path folder;
    int legacyAssessment,legacyRecord,preparedRecord;String legacyTheme;
    @BeforeEach void setup()throws Exception{
        h.folder=folder;h.setup();var f=h.f;legacyTheme="prepared-legacy-report-"+UUID.randomUUID();
        f.jdbc.update("INSERT INTO assessment(theme,create_id,deleted,total_score,pass_score,show_result,question_count) VALUES (?,?,0,10,5,1,0)",legacyTheme,f.actor);
        legacyAssessment=f.jdbc.queryForObject("SELECT id FROM assessment WHERE theme=?",Integer.class,legacyTheme);
        f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",legacyAssessment,f.student);
        String evaluation=h.json.writeValueAsString(Map.of("summary","明确标注的合成旧报告兼容测试","analysis",Map.of("analysis",List.of(Map.of("name","原旧指标","score",6,"description","合成旧结果")),"suggestions",List.of(),"strengths",List.of(),"weaknesses",List.of())));
        f.jdbc.update("INSERT INTO student_assessment_record(assessment_id,student_id,state,score,check_score,re_analysis,defense_result,create_time,update_time) VALUES (?,?,1,6,0,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",legacyAssessment,f.student,evaluation);
        legacyRecord=f.jdbc.queryForObject("SELECT id FROM student_assessment_record WHERE assessment_id=?",Integer.class,legacyAssessment);
        preparedRecord=f.jdbc.queryForObject("SELECT record_id FROM prepared_attempt WHERE id=?",Integer.class,f.attempt);
    }
    @AfterEach void cleanup()throws Exception{
        h.f.jdbc.update("DELETE FROM student_assessment_record WHERE assessment_id=?",legacyAssessment);h.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?",legacyAssessment);h.f.jdbc.update("DELETE FROM assessment WHERE id=?",legacyAssessment);h.cleanup();
    }
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void actualRecordListOriginalModeAndLegacyReportBoundariesAreRepeatable(int recordState)throws Exception{
        var f=h.f;var original=f.f.status();f.jdbc.update("UPDATE student_assessment_record SET state=?,score=0,check_score=8 WHERE id=?",recordState,preparedRecord);
        var rows=h.success(h.request(h.teacher,"POST","/backend/study-record/list",Map.of("page",1,"size",20))).path("records");
        var prepared=java.util.stream.StreamSupport.stream(rows.spliterator(),false).filter(r->r.path("id").asInt()==preparedRecord).findFirst().orElseThrow();
        assertThat(prepared.path("assessmentId").asInt()).isEqualTo(f.assessment);assertThat(prepared.path("prepared").asBoolean()).isTrue();assertThat(prepared.path("preparedState").asText()).isEqualTo("SUBMITTED");assertThat(prepared.path("score").isNull()).isTrue();assertThat(prepared.path("checkScore").isNull()).isTrue();
        var legacy=java.util.stream.StreamSupport.stream(rows.spliterator(),false).filter(r->r.path("id").asInt()==legacyRecord).findFirst().orElseThrow();assertThat(legacy.path("prepared").asBoolean()).isFalse();assertThat(legacy.path("score").asInt()).isEqualTo(6);assertThat(legacy.path("checkScore").asInt()).isZero();
        String detail="/backend/study-record/assessment-detail?id=";
        assertThat(h.request(h.teacher,"GET",detail+preparedRecord,null).statusCode()).isEqualTo(409);
        assertThat(h.request(h.student,"GET","/front/student-assessment/assessment-detail?id="+f.assessment,null).statusCode()).isEqualTo(409);
        assertThat(h.success(h.request(h.teacher,"GET",detail+legacyRecord,null)).path("score").asInt()).isZero();
        assertThat(h.success(h.request(h.student,"GET","/front/student-assessment/assessment-detail?id="+legacyAssessment,null)).path("score").asInt()).isZero();
        for(var c:List.of(h.unrelated,h.student))assertThat(h.request(c,"GET",detail+preparedRecord,null).statusCode()).isEqualTo(403);
        for(Object input:List.of(Map.of("recordIds",List.of(preparedRecord)),Map.of("recordIds",List.of(legacyRecord,preparedRecord)),Map.of("theme",f.jdbc.queryForObject("SELECT theme FROM assessment WHERE id=?",String.class,f.assessment))))
            assertThat(h.request(h.teacher,"POST","/backend/study-record/export-record",input).statusCode()).isEqualTo(409);
        assertThat(h.request(h.unrelated,"POST","/backend/study-record/export-record",Map.of("recordIds",List.of(preparedRecord))).statusCode()).isEqualTo(403);
        String body=h.json.writeValueAsString(Map.of("recordIds",List.of(legacyRecord)));var request=HttpRequest.newBuilder(URI.create(h.base+"/backend/study-record/export-record")).timeout(java.time.Duration.ofSeconds(15)).header("Origin","http://127.0.0.1:13015").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var export=h.teacher.send(request,HttpResponse.BodyHandlers.ofByteArray());assertThat(export.statusCode()).isEqualTo(200);assertThat(export.headers().firstValue("Content-Type").orElse("")).contains("spreadsheetml.sheet");assertThat(export.body()).startsWith((byte)'P',(byte)'K');
        f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.actor);try{assertThat(h.request(h.teacher,"POST","/backend/study-record/list",Map.of("page",1,"size",20)).statusCode()).isEqualTo(403);}finally{f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.actor);}
        assertThat(f.f.status()).isEqualTo(original);assertThat(f.count("prepared_result_command")).isZero();
        browser(recordState);
    }
    void browser(int round)throws Exception{
        Path root=Path.of("").toAbsolutePath().getParent(),log=root.resolve(".runtime/record-bridge-browser-"+round+".log");
        var builder=new ProcessBuilder("npx","playwright","test","tests/e2e/live-prepared-records.spec.ts","--workers=1","--retries=0");builder.directory(root.resolve("defense-system").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();PreparedLiveDatabase.browserEnvironment(env);env.put("LIVE_E2E","1");env.put("E2E_BASE_URL","http://127.0.0.1:13015");env.put("PREPARED_RECORD_ASSESSMENT",Integer.toString(h.f.assessment));env.put("PREPARED_RECORD_ID",Integer.toString(preparedRecord));env.put("PREPARED_LEGACY_RECORD_ID",Integer.toString(legacyRecord));env.put("PREPARED_REVIEW_ACCOUNT",h.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,h.f.actor));
        env.put("PREPARED_RECORD_STUDENT_ACCOUNT",h.f.jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,h.f.student));
        var process=builder.start();try{assertThat(process.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as("actual record browser "+log.getFileName()).isZero();}finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}}
    }
}
