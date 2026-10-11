package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import system.assessment.defense.application.service.PasswordSecurityService;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly live: no Spring mocks, no embedded substitute for MySQL or Redis. */
@EnabledIfEnvironmentVariable(named = "PREPARED_LIVE_TESTS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreparedPaperLiveHttpTest {
    private static final String BASE = "http://127.0.0.1:13014";
    private final ObjectMapper json = new ObjectMapper();
    private final List<Integer> fixtureUsers = new ArrayList<>();
    private final List<Integer> fixtureAssessments = new ArrayList<>();
    private JdbcTemplate jdbc;
    private HttpClient owner, invited, unrelated, student, admin;
    private int ownerId, invitedId, studentId;
    private int assessmentId;

    @BeforeAll void initializeIsolatedFixtures() throws Exception {
        String password = required("PREPARED_DB_PASSWORD");
        String username = required("PREPARED_DB_USERNAME");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                PreparedLiveDatabase.jdbcUrl(),
                username, password));
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("prepared_interview");
        jdbc.setQueryTimeout(5);
        String loginPassword = required("PREPARED_TEST_LOGIN_PASSWORD");
        String hash = new PasswordSecurityService().encode(loginPassword);
        ownerId = createUser(1, hash);
        invitedId = createUser(1, hash);
        int unrelatedId = createUser(1, hash);
        studentId = createUser(0, hash);
        int adminId = createUser(2, hash);
        owner = login(ownerId, loginPassword);
        invited = login(invitedId, loginPassword);
        unrelated = login(unrelatedId, loginPassword);
        student = login(studentId, loginPassword);
        admin = login(adminId, loginPassword);
    }

    @BeforeEach void createAssessment() {
        assessmentId = newAssessment();
        jdbc.update("INSERT INTO assessment_invite(assessment_id,invitee_teacher_id,inviter_teacher_id) VALUES (?,?,?)",
                assessmentId, invitedId, ownerId);
        jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",
                assessmentId, studentId);
    }

    @AfterAll void removeOnlyOwnedFixtures() throws Exception {
        if (jdbc == null) return;
        for (HttpClient client : Arrays.asList(owner, invited, unrelated, student, admin)) {
            if (client != null) request(client, "POST", "/logout", Map.of());
        }
        for (int id : fixtureAssessments) {
            jdbc.update("DELETE FROM prepared_audio_command WHERE assessment_id=?",id);
            jdbc.update("DELETE FROM prepared_audio_part_ref WHERE assessment_id=?",id);
            jdbc.update("DELETE FROM prepared_audio_part WHERE assessment_id=?",id);
            jdbc.update("DELETE FROM prepared_publication_receipt WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_paper_gate WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_question_snapshot WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_paper_version WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_audio_asset WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_generation_adopted WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_generation_adoption WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_job_dependency WHERE assessment_id=?",id);
            jdbc.update("DELETE FROM prepared_job WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_question_review WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_audit WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_question_draft WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM prepared_paper_draft WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM student_assessment_appointment WHERE assessment_id=?",id);
            jdbc.update("DELETE FROM student_assessment_record WHERE assessment_id=?",id);
            jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?", id);
            jdbc.update("DELETE FROM assessment WHERE id=?", id);
        }
        for (int id : fixtureUsers) jdbc.update("DELETE FROM `user` WHERE id=?", id);
    }

    @Test void threeQuestionsPersistThroughSaveReviewEditReorderDeleteAndFreshRead() throws Exception {
        List<Map<String, Object>> questions = List.of(question("第一道题"), question("第二道题"), question("第三道题"));
        JsonNode saved = success(request(owner, "PUT", paperPath(), save(0, questions)));
        assertThat(saved.path("revision").asLong()).isEqualTo(1);
        assertThat(saved.path("questions").size()).isEqualTo(3);
        List<String> ids = questions.stream().map(q -> (String) q.get("questionId")).toList();
        JsonNode reviewed = success(request(invited, "POST", paperPath() + "/approve",
                Map.of("expectedRevision", 1, "questionIds", ids)));
        assertThat(reviewed.path("revision").asLong()).isEqualTo(2);
        for (JsonNode q : reviewed.path("questions")) assertThat(q.path("approved").asBoolean()).isTrue();
        Map<String, Object> changed = new HashMap<>(questions.get(0));
        changed.put("body", "第一道题已修订");
        changed.put("maxScore", 20);
        changed.put("answerSeconds", 180);
        changed.put("rubric", "修改后的评分要求");
        JsonNode edited = success(request(owner, "PUT", paperPath(), save(2, List.of(questions.get(2), changed))));
        assertThat(edited.path("revision").asLong()).isEqualTo(3);
        assertThat(edited.path("questions").size()).isEqualTo(2);
        assertThat(edited.path("questions").get(0).path("questionId").asText()).isEqualTo(ids.get(2));
        assertThat(edited.path("questions").get(0).path("ordinal").asInt()).isEqualTo(1);
        assertThat(edited.path("questions").get(0).path("approved").asBoolean()).isTrue();
        assertThat(edited.path("questions").get(1).path("approved").asBoolean()).isFalse();
        assertThat(edited.path("questions").get(1).path("audioState").asText()).isEqualTo("STALE");
        assertThat(success(request(admin, "GET", paperPath(), null))).isEqualTo(edited);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_question_draft WHERE assessment_id=?",
                Integer.class, assessmentId)).isEqualTo(2);
    }

    @Test void actualGenerationEndpointsEnforceRolesOwnershipAndDisabledPaidCalls() throws Exception {
        String path=paperPath()+"/generations";
        assertThat(request(HttpClient.newHttpClient(),"GET",path,null).statusCode()).isEqualTo(401);
        Map<String,Object> cmd=Map.of("expectedRevision",0,"idempotencyKey",UUID.randomUUID().toString(),"requirements","实际权限测试", "count",1,"maxScore",10,"answerSeconds",120);
        for(HttpClient client:List.of(student,unrelated)) {
            assertThat(request(client,"GET",path,null).statusCode()).isEqualTo(403);
            assertThat(request(client,"POST",path,cmd).statusCode()).isEqualTo(403);
        }
        for(HttpClient client:List.of(owner,invited,admin)) assertThat(success(request(client,"GET",path,null)).path("availability").path("enabled").asBoolean()).isFalse();
        assertThat(request(owner,"POST",path,cmd).statusCode()).isEqualTo(503);
        assertThat(request(owner,"POST",path,Map.of("idempotencyKey",UUID.randomUUID().toString())).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=?",Integer.class,assessmentId)).isZero();
    }
    @Test void actualAudioEndpointsDenyForeignUsersAndDoNotPretendDisabledModelsAreReady() throws Exception {
        String path=paperPath()+"/audio";String asset=UUID.randomUUID().toString();var q=question("真实权限测试，播报仍未启用");
        success(request(owner,"PUT",paperPath(),save(0,List.of(q))));
        success(request(owner,"POST",paperPath()+"/approve",Map.of("expectedRevision",1,"questionIds",List.of(q.get("questionId")))));
        assertThat(request(HttpClient.newHttpClient(),"GET",path,null).statusCode()).isEqualTo(401);
        for(HttpClient client:List.of(owner,invited,admin)){
            var result=success(request(client,"GET",path,null));assertThat(result.path("availability").path("enabled").asBoolean()).isFalse();
            assertThat(result.path("current").size()).isZero();assertThat(result.toString()).doesNotContain("api-key","access-key");
        }
        var command=Map.of("expectedRevision",2,"idempotencyKey",UUID.randomUUID().toString(),"questionId",q.get("questionId"),"expectedConfigurationSha256","a".repeat(64));
        for(HttpClient client:List.of(student,unrelated)){
            assertThat(request(client,"GET",path,null).statusCode()).isEqualTo(403);assertThat(request(client,"POST",path,command).statusCode()).isEqualTo(403);
            assertThat(request(client,"GET",path+"/"+asset+"/preview",null).statusCode()).isEqualTo(403);
            assertThat(request(client,"POST",path+"/"+asset+"/recover",Map.of("expectedRevision",2,"idempotencyKey",UUID.randomUUID().toString())).statusCode()).isEqualTo(403);
        }
        assertThat(request(owner,"POST",path,command).statusCode()).isEqualTo(503);
        assertThat(request(owner,"GET",path+"/"+asset+"/preview",null).statusCode()).isEqualTo(403);
        assertThat(request(owner,"POST",path,Map.of("idempotencyKey",UUID.randomUUID().toString())).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=?",Integer.class,assessmentId)).isZero();
    }

    @Test void publicationReadinessIsAnAuthorizedRealCheckNotAnAssumedSuccess() throws Exception {
        String path = paperPath() + "/publications";
        assertThat(request(HttpClient.newHttpClient(), "GET", path, null).statusCode()).isEqualTo(401);
        for (HttpClient client : List.of(student, unrelated)) {
            assertThat(request(client, "GET", path, null).statusCode()).isEqualTo(403);
            assertThat(request(client, "POST", path, Map.of("expectedRevision", 0,
                    "idempotencyKey", UUID.randomUUID().toString(), "expectedContentSha256", "a".repeat(64))).statusCode()).isEqualTo(403);
        }
        for (HttpClient client : List.of(owner, invited, admin)) {
            JsonNode status = success(request(client, "GET", path, null));
            assertThat(status.path("assessmentId").asInt()).isEqualTo(assessmentId);
            assertThat(status.path("draftRevision").asInt()).isZero();
            assertThat(status.path("publishable").asBoolean()).isFalse();
            assertThat(status.path("issues").toString()).contains("EMPTY_PAPER");
            assertThat(status.path("versions").size()).isZero();
        }
        assertThat(request(owner, "POST", path, Map.of("expectedRevision", 0,
                "idempotencyKey", UUID.randomUUID().toString(), "expectedContentSha256", "a".repeat(64))).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_paper_draft WHERE assessment_id=?",
                Integer.class, assessmentId)).isZero();
    }

    @Test void studentEligibilityRequiresActualStudentSessionAndCurrentAssignment() throws Exception {
        String path="/backend/prepared-interviews/"+assessmentId+"/eligibility";
        assertThat(request(HttpClient.newHttpClient(),"GET",path,null).statusCode()).isEqualTo(401);
        for(HttpClient client:List.of(owner,invited,unrelated,admin))
            assertThat(request(client,"GET",path,null).statusCode()).isEqualTo(403);
        var response=request(student,"GET",path,null);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var eligible=success(response);
        assertThat(eligible.path("phase").asText()).isEqualTo("PAPER_NOT_PUBLISHED");
        assertThat(eligible.path("questionCount").asInt()).isZero();
        assertThat(eligible.toString()).doesNotContain("rubric","questionId","objectKey","paperVersionId","attemptId");
        assertThat(request(student,"GET","/backend/prepared-interviews/"+newAssessment()+"/eligibility",null).statusCode()).isEqualTo(403);
        assertThat(request(student,"GET","/backend/prepared-interviews/0/eligibility",null).statusCode()).isEqualTo(400);
        jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",assessmentId,studentId);
        assertThat(request(student,"GET",path,null).statusCode()).isEqualTo(403);
    }

    @Test void studentPreflightContextBindsRealSessionAndDeploymentWithoutCreatingAnAttempt() throws Exception {
        String path="/backend/prepared-interviews/"+assessmentId+"/preflight-context";
        assertThat(request(HttpClient.newHttpClient(),"GET",path,null).statusCode()).isEqualTo(401);
        for(var client:List.of(owner,invited,unrelated,admin))assertThat(request(client,"GET",path,null).statusCode()).isEqualTo(403);
        var response=request(student,"GET",path+"?studentId="+ownerId+"&deploymentId=prepared-other",null);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var context=success(response);assertThat(context.path("studentId").asInt()).isEqualTo(studentId);
        assertThat(context.path("deploymentId").asText()).isEqualTo("prepared-local");
        assertThat(context.path("eligibility").path("assessmentId").asInt()).isEqualTo(assessmentId);
        assertThat(context.path("eligibility").path("phase").asText()).isEqualTo("PAPER_NOT_PUBLISHED");
        assertThat(context.size()).isEqualTo(4);
        assertThat(context.path("workflowEnabled").isBoolean()).isTrue();
        assertThat(context.path("workflowEnabled").asBoolean()).isFalse();
        assertThat(context.toString()).doesNotContain("rubric","questionId","objectKey","attemptId","apiKey","accessKey");
        assertThat(request(student,"GET","/backend/prepared-interviews/0/preflight-context",null).statusCode()).isEqualTo(400);
        assertThat(request(student,"GET","/backend/prepared-interviews/"+newAssessment()+"/preflight-context",null).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student_assessment_record WHERE assessment_id=?",Integer.class,assessmentId)).isZero();
        jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",assessmentId,studentId);
        assertThat(request(student,"GET",path,null).statusCode()).isEqualTo(403);
    }

    @Test void studentEligibilityProjectsOnlyVerifiedFrozenMetadataAndNeverFutureQuestions() throws Exception {
        var fixture=syntheticPublicationForEligibility();String path="/backend/prepared-interviews/"+assessmentId+"/eligibility";
        var before=success(request(student,"GET",path,null));
        assertThat(before.path("phase").asText()).isEqualTo("READY_FOR_PREFLIGHT");
        assertThat(before.path("message").asText()).contains("尚未授权正式开考");
        assertThat(before.path("publicationReady").asBoolean()).isTrue();assertThat(before.path("questionCount").asInt()).isEqualTo(1);
        assertThat(before.toString()).doesNotContain("题目秘密", "仅教师可见",fixture.questionId(),fixture.assetId(),fixture.versionId(),fixture.key());
        jdbc.update("UPDATE assessment SET theme='未重新发布的修改主题',question_count=9 WHERE id=?",assessmentId);
        success(request(owner,"PUT",paperPath(),save(2,List.of(question("尚未发布的新草稿题目秘密")))));
        var after=success(request(student,"GET",path,null));
        assertThat(after.path("theme")).isEqualTo(before.path("theme"));assertThat(after.path("questionCount").asInt()).isEqualTo(1);
        assertThat(after.toString()).doesNotContain("新草稿","修改主题");
        assertThat(jdbc.queryForObject("SELECT first_started_at IS NULL FROM prepared_paper_gate WHERE assessment_id=?",Boolean.class,assessmentId)).isTrue();
    }

    @Test void actualAppointmentsHaveExplicitPreflightStatesWithoutAutomaticPunishment() throws Exception {
        syntheticPublicationForEligibility();String path="/backend/prepared-interviews/"+assessmentId+"/eligibility";
        jdbc.update("UPDATE assessment SET need_appoint=1 WHERE id=?",assessmentId);
        assertThat(success(request(student,"GET",path,null)).path("phase").asText()).isEqualTo("APPOINTMENT_REQUIRED");
        var zone=java.time.ZoneId.of("Asia/Shanghai");var future=java.time.LocalDateTime.now(zone).plusHours(1).withNano(0);
        jdbc.update("INSERT INTO student_assessment_appointment(student_id,assessment_id,state,time_period,punish_state) VALUES (?,?,0,?,-1)",studentId,assessmentId,future);
        var waiting=success(request(student,"GET",path,null));
        assertThat(waiting.path("phase").asText()).isEqualTo("BEFORE_APPOINTMENT");
        assertThat(waiting.path("appointmentStartsAt").asText()).isEqualTo(future.atZone(zone).toInstant().toString());
        assertThat(waiting.path("latestFirstStartAt").asText()).isEqualTo(future.plusMinutes(10).atZone(zone).toInstant().toString());
        var recent=java.time.LocalDateTime.now(zone).minusMinutes(1).withNano(0);
        jdbc.update("UPDATE student_assessment_appointment SET time_period=?,state=NULL WHERE assessment_id=? AND student_id=?",recent,assessmentId,studentId);
        assertThat(success(request(student,"GET",path,null)).path("phase").asText()).isEqualTo("READY_FOR_PREFLIGHT");
        for(int state:new int[]{1,2,3,4}){
            jdbc.update("UPDATE student_assessment_appointment SET state=? WHERE assessment_id=? AND student_id=?",state,assessmentId,studentId);
            assertThat(success(request(student,"GET",path,null)).path("phase").asText()).isEqualTo("APPOINTMENT_STATE_CONFLICT");
        }
        jdbc.update("UPDATE student_assessment_appointment SET state=0,time_period=? WHERE assessment_id=? AND student_id=?",recent.minusHours(1),assessmentId,studentId);
        for(int repeat=0;repeat<5;repeat++)
            assertThat(success(request(student,"GET",path,null)).path("phase").asText()).isEqualTo("APPOINTMENT_WINDOW_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT state FROM student_assessment_appointment WHERE assessment_id=? AND student_id=?",Integer.class,assessmentId,studentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT punish_state FROM student_assessment_appointment WHERE assessment_id=? AND student_id=?",Integer.class,assessmentId,studentId)).isEqualTo(-1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student_assessment_record WHERE assessment_id=?",Integer.class,assessmentId)).isZero();
    }

    @Test void repeatedChecksPreserveEveryLegacyRecordStateAndNeverReturnProtectedResultFields() throws Exception {
        syntheticPublicationForEligibility();String path="/backend/prepared-interviews/"+assessmentId+"/eligibility";
        jdbc.update("INSERT INTO student_assessment_record(student_id,assessment_id,state,score,check_score,defense_result) VALUES (?,?,0,7,8,'历史分析秘密')",studentId,assessmentId);
        for(int state:new int[]{0,1,2,3}){
            jdbc.update("UPDATE student_assessment_record SET state=? WHERE assessment_id=? AND student_id=?",state,assessmentId,studentId);
            for(int repeat=0;repeat<5;repeat++){
                var status=success(request(student,"GET",path,null));assertThat(status.path("phase").asText()).isEqualTo("HISTORY_REQUIRES_REVIEW");
                assertThat(status.toString()).doesNotContain("历史分析秘密","score","checkScore","defenseResult","rubric");
            }
            assertThat(jdbc.queryForObject("SELECT state FROM student_assessment_record WHERE assessment_id=? AND student_id=?",Integer.class,assessmentId,studentId)).isEqualTo(state);
            assertThat(jdbc.queryForObject("SELECT score FROM student_assessment_record WHERE assessment_id=? AND student_id=?",Integer.class,assessmentId,studentId)).isEqualTo(7);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student_assessment_record WHERE assessment_id=?",Integer.class,assessmentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND actor_id=?",Integer.class,assessmentId,studentId)).isZero();
    }

    @Test void corruptSnapshotArchivedAssessmentOrRevokedStudentRoleNeverBecomeReady() throws Exception {
        var fixture=syntheticPublicationForEligibility();String path="/backend/prepared-interviews/"+assessmentId+"/eligibility";
        jdbc.update("UPDATE prepared_paper_version SET snapshot_json=JSON_SET(snapshot_json,'$.questions[0].body','完整性被破坏的秘密题目') WHERE id=?",fixture.versionId());
        var corrupt=request(student,"GET",path,null);assertThat(corrupt.statusCode()).isEqualTo(500);
        assertThat(corrupt.body()).doesNotContain("完整性被破坏","仅教师可见",fixture.key(),"stackTrace","snapshot_json");
        jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",assessmentId);
        assertThat(request(student,"GET",path,null).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",assessmentId);
        try{
            jdbc.update("UPDATE `user` SET type=1 WHERE id=?",studentId);
            assertThat(request(student,"GET",path,null).statusCode()).isEqualTo(403);
        } finally { jdbc.update("UPDATE `user` SET type=0 WHERE id=?",studentId); }
    }

    private record EligibilityFixture(String questionId,String assetId,String versionId,String key) {}
    private EligibilityFixture syntheticPublicationForEligibility() throws Exception {
        // Real authenticated publication/SQL, but explicitly synthetic audio proof. This is NOT TTS/OSS acceptance.
        var q=question("未授权前不可泄漏的题目秘密");String qid=(String)q.get("questionId");
        success(request(owner,"PUT",paperPath(),save(0,List.of(q))));
        success(request(owner,"POST",paperPath()+"/approve",Map.of("expectedRevision",1,"questionIds",List.of(qid))));
        jdbc.update("UPDATE assessment SET total_score=10,pass_score=6,question_count=1 WHERE id=?",assessmentId);
        var ledger=new system.assessment.defense.prepared.jobs.PreparedJobLedger(jdbc,new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()));
        var job=ledger.enqueue(ownerId,assessmentId,system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind.TTS,2,UUID.randomUUID().toString(),"{\"syntheticFixture\":true}");
        jdbc.update("UPDATE prepared_job SET state='SUCCEEDED',output_json='{}' WHERE id=?",job.id());
        String asset=UUID.randomUUID().toString(),key="prepared/prepared-local/assessments/"+assessmentId+"/questions/"+qid+"/audio/"+asset+".wav";
        jdbc.update("""
                INSERT INTO prepared_audio_asset(id,assessment_id,question_id,body_sha256,config_sha256,job_id,state,object_key,content_sha256,byte_size,duration_ms,media_type,verified_at)
                VALUES (?,?,?,?,?,?,'READY',?,?,48044,1000,'audio/wav',CURRENT_TIMESTAMP(3))
                """,asset,assessmentId,qid,system.assessment.defense.prepared.jobs.PreparedJobInput.digest((String)q.get("body")),"a".repeat(64),job.id(),key,"b".repeat(64));
        jdbc.update("UPDATE prepared_question_draft SET audio_state='READY',audio_object_key=? WHERE assessment_id=? AND question_id=?",key,assessmentId,qid);
        String hash=success(request(owner,"GET",paperPath()+"/publications",null)).path("preparedContentSha256").asText();
        String version=success(request(owner,"POST",paperPath()+"/publications",Map.of("expectedRevision",2,"idempotencyKey",UUID.randomUUID().toString(),"expectedContentSha256",hash))).path("version").path("id").asText();
        return new EligibilityFixture(qid,asset,version,key);
    }

    @Test void realHttpPublicationStoresFixedSnapshotAndRejectsStudentsForeignVersionsAndChangedAudio() throws Exception {
        // Synthetic asset evidence deliberately injected into this test's dedicated rows. No real TTS/OSS claim.
        var q=question("发布权限真实 HTTP 联调题");String questionId=(String)q.get("questionId");
        success(request(owner,"PUT",paperPath(),save(0,List.of(q))));
        success(request(invited,"POST",paperPath()+"/approve",Map.of("expectedRevision",1,"questionIds",List.of(questionId))));
        jdbc.update("UPDATE assessment SET total_score=10,pass_score=6,question_count=1 WHERE id=?",assessmentId);
        var ledger=new system.assessment.defense.prepared.jobs.PreparedJobLedger(jdbc,new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()));
        var job=ledger.enqueue(ownerId,assessmentId,system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind.TTS,2,UUID.randomUUID().toString(),"{\"syntheticFixture\":true}");
        jdbc.update("UPDATE prepared_job SET state='SUCCEEDED',output_json='{}' WHERE id=?",job.id());
        String asset=UUID.randomUUID().toString();String key="prepared/prepared-local/assessments/"+assessmentId+"/questions/"+questionId+"/audio/"+asset+".wav";
        jdbc.update("""
                INSERT INTO prepared_audio_asset(id,assessment_id,question_id,body_sha256,config_sha256,job_id,state,object_key,content_sha256,byte_size,duration_ms,media_type,verified_at)
                VALUES (?,?,?,?,?,?,'READY',?,?,48044,1000,'audio/wav',CURRENT_TIMESTAMP(3))
                """,asset,assessmentId,questionId,system.assessment.defense.prepared.jobs.PreparedJobInput.digest((String)q.get("body")),"a".repeat(64),job.id(),key,"b".repeat(64));
        jdbc.update("UPDATE prepared_question_draft SET audio_state='READY',audio_object_key=? WHERE assessment_id=?",key,assessmentId);
        String path=paperPath()+"/publications";String hash=success(request(owner,"GET",path,null)).path("preparedContentSha256").asText();
        var cmd=Map.of("expectedRevision",2,"idempotencyKey",UUID.randomUUID().toString(),"expectedContentSha256",hash);
        JsonNode published=success(request(invited,"POST",path,cmd));String version=published.path("version").path("id").asText();
        assertThat(published.path("snapshot").path("questions").get(0).path("audio").path("assetId").asText()).isEqualTo(asset);
        assertThat(published.toString()).doesNotContain(key);
        for(int n=0;n<5;n++)assertThat(success(request(invited,"POST",path,cmd))).isEqualTo(published);
        for(HttpClient client:List.of(owner,invited,admin))assertThat(success(request(client,"GET",path+"/"+version,null))).isEqualTo(published);
        for(HttpClient client:List.of(student,unrelated))assertThat(request(client,"GET",path+"/"+version,null).statusCode()).isEqualTo(403);
        assertThat(request(owner,"GET","/backend/prepared-papers/"+newAssessment()+"/publications/"+version,null).statusCode()).isEqualTo(403);
        var changed=new HashMap<>(q);changed.put("body","不能错用旧播报的新正文");
        success(request(owner,"PUT",paperPath(),save(2,List.of(changed))));
        assertThat(success(request(owner,"GET",path,null)).path("issues").toString()).contains("AUDIO_NOT_READY");
        assertThat(request(owner,"POST",path,Map.of("expectedRevision",3,"idempotencyKey",UUID.randomUUID().toString(),"expectedContentSha256",hash)).statusCode()).isEqualTo(409);
        assertThat(success(request(invited,"POST",path,cmd))).isEqualTo(published);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_paper_version WHERE assessment_id=?",Integer.class,assessmentId)).isEqualTo(1);
        jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=? AND invitee_teacher_id=?",assessmentId,invitedId);
        assertThat(request(invited,"POST",path,cmd).statusCode()).isEqualTo(403);
        assertThat(request(owner,"POST",path,Map.of("idempotencyKey",UUID.randomUUID().toString())).statusCode()).isEqualTo(400);
    }

    @Test void actualHttpAdoptionOfExplicitSyntheticSuggestionsIsPersistentIdempotentAndNeverAutoReviewed() throws Exception {
        String job=syntheticGeneration();String path=paperPath()+"/generations/"+job;
        JsonNode view=success(request(invited,"GET",path,null));
        String question=view.path("suggestions").get(0).path("questionId").asText();
        var cmd=Map.of("expectedRevision",0,"idempotencyKey",UUID.randomUUID().toString(),"questionIds",List.of(question));
        for(HttpClient client:List.of(student,unrelated)) {
            assertThat(request(client,"GET",path,null).statusCode()).isEqualTo(403);
            assertThat(request(client,"POST",path+"/adopt",cmd).statusCode()).isEqualTo(403);
        }
        var result=success(request(invited,"POST",path+"/adopt",cmd));
        assertThat(result.path("adoptedRevision").asInt()).isEqualTo(1);
        assertThat(result.path("draft").path("questions").get(0).path("source").asText()).isEqualTo("AI");
        assertThat(result.path("draft").path("questions").get(0).path("approved").asBoolean()).isFalse();
        assertThat(success(request(invited,"POST",path+"/adopt",cmd)).path("adoptedRevision").asInt()).isEqualTo(1);
        assertThat(success(request(owner,"GET",paperPath(),null)).path("questions").size()).isEqualTo(1);
        assertThat(success(request(owner,"GET",path,null)).path("adoptedQuestionIds").get(0).asText()).isEqualTo(question);
        assertThat(request(owner,"POST",path+"/adopt",Map.of("expectedRevision",1,"idempotencyKey",UUID.randomUUID().toString(),"questionIds",List.of(question))).statusCode()).isEqualTo(409);
        assertThat(request(owner,"GET","/backend/prepared-papers/"+newAssessment()+"/generations/"+job,null).statusCode()).isEqualTo(403);
    }

    private String syntheticGeneration() {
        var ledger=new system.assessment.defense.prepared.jobs.PreparedJobLedger(jdbc,new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()));
        var p=new PreparedGenerationPolicyTest();
        var cmd=new system.assessment.defense.prepared.generation.PreparedGeneration.Command(0L,UUID.randomUUID().toString(),"明确的合成测试输入，非真实模型",1,10,120);
        var policy=new system.assessment.defense.prepared.generation.PreparedGenerationPolicy(p.configured(),p.clock).snapshot();
        var job=ledger.enqueue(ownerId,assessmentId,system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind.QUESTIONS,0,cmd.idempotencyKey(),
                system.assessment.defense.prepared.generation.PreparedGeneration.encode(new system.assessment.defense.prepared.generation.PreparedGeneration.Input(cmd,policy)));
        jdbc.update("UPDATE prepared_job SET state='SUCCEEDED',output_json=? WHERE id=?", "{\"questions\":[{\"body\":\"合成测试建议\",\"rubric\":\"测试标准\",\"maxScore\":10,\"answerSeconds\":120}]}",job.id());
        return job.id();
    }

    @Test void resourcePermissionsAndRevokedInvitationAreEnforcedOverRealSessions() throws Exception {
        assertThat(request(HttpClient.newHttpClient(), "GET", paperPath(), null).statusCode()).isEqualTo(401);
        for (HttpClient client : List.of(unrelated, student)) {
            assertThat(request(client, "GET", paperPath(), null).statusCode()).isEqualTo(403);
            assertThat(request(client, "PUT", paperPath(), save(0, List.of(question("禁止写入")))).statusCode()).isEqualTo(403);
            assertThat(request(client, "POST", paperPath() + "/approve",
                    Map.of("expectedRevision", 0, "questionIds", List.of(UUID.randomUUID().toString()))).statusCode()).isEqualTo(403);
        }
        for (HttpClient client : List.of(owner, invited, admin)) success(request(client, "GET", paperPath(), null));
        jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=? AND invitee_teacher_id=?", assessmentId, invitedId);
        assertThat(request(invited, "GET", paperPath(), null).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_paper_draft WHERE assessment_id=?",
                Integer.class, assessmentId)).isZero();
    }

    @Test void simultaneousSameVersionWritesHaveOneWinnerAndOneConflict() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return request(owner, "PUT", paperPath(), save(0, List.of(question("候选一")))); });
            var second = executor.submit(() -> { start.await(); return request(invited, "PUT", paperPath(), save(0, List.of(question("候选二")))); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS).statusCode(), second.get(10, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        JsonNode current = success(request(owner, "GET", paperPath(), null));
        assertThat(current.path("revision").asLong()).isEqualTo(1);
        assertThat(current.path("questions").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=?",
                Integer.class, assessmentId)).isEqualTo(1);
    }

    @Test void invalidEmptyAndStaleCommandsCannotCorruptPreviouslySavedContent() throws Exception {
        assertThat(request(owner, "PUT", paperPath(), Map.of("questions", List.of())).statusCode()).isEqualTo(400);
        JsonNode empty = success(request(owner, "PUT", paperPath(), save(0, List.of())));
        assertThat(empty.path("revision").asLong()).isEqualTo(1);
        assertThat(request(owner, "POST", paperPath() + "/approve",
                Map.of("expectedRevision", 1, "questionIds", List.of())).statusCode()).isEqualTo(400);
        Map<String, Object> valid = question("必须保留");
        JsonNode saved = success(request(owner, "PUT", paperPath(), save(1, List.of(valid))));
        assertThat(request(owner, "PUT", paperPath(), save(1, List.of(question("旧页面")))).statusCode()).isEqualTo(409);
        Map<String, Object> invalid = new HashMap<>(valid);
        invalid.put("body", "   ");
        assertThat(request(owner, "PUT", paperPath(), save(2, List.of(invalid))).statusCode()).isEqualTo(400);
        assertThat(request(owner, "PUT", paperPath(), save(2, List.of(valid, valid))).statusCode()).isEqualTo(400);
        invalid.put("body", "不得改变原题");
        invalid.put("answerSeconds", 0);
        assertThat(request(owner, "PUT", paperPath(), save(2, List.of(invalid))).statusCode()).isEqualTo(400);
        assertThat(success(request(owner, "GET", paperPath(), null))).isEqualTo(saved);
    }

    @Test void preparedCookieWritesWithoutTrustedOriginAreRejectedBeforePersistence() throws Exception {
        HttpRequest noOrigin = HttpRequest.newBuilder(URI.create(BASE + paperPath()))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(8))
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(save(0, List.of())))).build();
        assertThat(owner.send(noOrigin, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_paper_draft WHERE assessment_id=?",
                Integer.class, assessmentId)).isZero();
    }

    @Test void crossAssessmentQuestionIdentityConflictRollsBackTheWholeTransaction() throws Exception {
        Map<String, Object> shared = question("原所属考核题目");
        JsonNode original = success(request(owner, "PUT", paperPath(), save(0, List.of(shared))));
        int other = newAssessment();
        assertThat(request(owner, "PUT", "/backend/prepared-papers/" + other,
                save(0, List.of(shared))).statusCode()).isEqualTo(409);
        assertThat(success(request(owner, "GET", paperPath(), null))).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_paper_draft WHERE assessment_id=?",
                Integer.class, other)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=?",
                Integer.class, other)).isZero();
    }

    @Test void explicitReviewIsAuditedWithReviewerRevisionAndQuestionScope() throws Exception {
        Map<String, Object> q = question("审核记录测试");
        success(request(owner, "PUT", paperPath(), save(0, List.of(q))));
        success(request(invited, "POST", paperPath() + "/approve",
                Map.of("expectedRevision", 1, "questionIds", List.of(q.get("questionId")))));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prepared_audit
                WHERE assessment_id=? AND actor_id=? AND action='QUESTIONS_APPROVED' AND resource_version=2
                """, Integer.class, assessmentId, invitedId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prepared_question_review
                WHERE assessment_id=? AND question_id=? AND reviewer_id=? AND reviewed_revision=2
                  AND CHAR_LENGTH(content_sha256)=64
                """, Integer.class, assessmentId, q.get("questionId"), invitedId)).isEqualTo(1);
        success(request(owner, "PUT", paperPath(), save(2, List.of())));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_question_review WHERE assessment_id=?",
                Integer.class, assessmentId)).isEqualTo(1);
    }

    @Test void databaseEquivalentLoginAliasesCannotBypassTheAccountAttemptLimit() throws Exception {
        String password = required("PREPARED_TEST_LOGIN_PASSWORD");
        int userId = createUser(0, new PasswordSecurityService().encode(password));
        String account = jdbc.queryForObject("SELECT account FROM `user` WHERE id=?", String.class, userId);
        String accented = Objects.requireNonNull(account).replace("prepared", "prépared");
        // Verify the real database collation, not an assumed Java normalization rule.
        assertThat(jdbc.queryForObject("SELECT id FROM `user` WHERE account=?", Integer.class, accented)).isEqualTo(userId);
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        for (int index = 0; index < 10; index++) {
            String alias = index % 2 == 0 ? accented : account.toUpperCase(Locale.ROOT);
            assertThat(request(client, "POST", "/login", Map.of("account", alias,
                    "password", "incorrect-isolated-fixture-password")).statusCode()).isEqualTo(401);
        }
        assertThat(request(client, "POST", "/login", Map.of("account", account, "password", password)).statusCode()).isEqualTo(429);
        assertThat(request(client, "GET", "/user-detail", null).statusCode()).isEqualTo(401);
    }

    private int createUser(int role, String hash) {
        String account = "prepared-live-" + UUID.randomUUID();
        jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES (?,?,?,?)", "独立联调账号", account, hash, role);
        int id = Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM `user` WHERE account=?", Integer.class, account));
        fixtureUsers.add(id);
        return id;
    }

    private int newAssessment() {
        String name = "prepared-live-" + UUID.randomUUID();
        jdbc.update("INSERT INTO assessment(theme,create_id,deleted) VALUES (?,?,0)", name, ownerId);
        int id = Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM assessment WHERE theme=?", Integer.class, name));
        fixtureAssessments.add(id);
        return id;
    }

    private HttpClient login(int userId, String password) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(3)).build();
        String account = jdbc.queryForObject("SELECT account FROM `user` WHERE id=?", String.class, userId);
        assertThat(request(client, "POST", "/login", Map.of("account", account, "password", password)).statusCode()).isEqualTo(200);
        assertThat(success(request(client, "GET", "/user-detail", null)).path("id").asInt()).isEqualTo(userId);
        return client;
    }

    private HttpResponse<String> request(HttpClient client, String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(8))
                .header("Origin", "http://127.0.0.1:13015").header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode success(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as("HTTP status; response %s", response.body()).isEqualTo(200);
        JsonNode envelope = json.readTree(response.body());
        assertThat(envelope.path("code").asInt()).isEqualTo(200);
        return envelope.path("data");
    }

    private String paperPath() { return "/backend/prepared-papers/" + assessmentId; }
    private static Map<String, Object> save(long revision, List<Map<String, Object>> questions) {
        return Map.of("expectedRevision", revision, "questions", questions);
    }
    private static Map<String, Object> question(String body) {
        return Map.of("questionId", UUID.randomUUID().toString(), "body", body,
                "maxScore", 10, "answerSeconds", 120, "rubric", "仅教师可见评分要求");
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for isolated live tests");
        return value;
    }
}
