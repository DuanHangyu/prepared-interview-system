package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.paper.PreparedPaper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual MySQL question authorization. Synthetic publication proof is NOT real TTS/OSS or formal student QA. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedQuestionGrantLiveTest {
    final PreparedPublicationLiveTest f=new PreparedPublicationLiveTest();
    final AtomicReference<Instant> now=new AtomicReference<>();
    private final List<String> questions=new ArrayList<>();
    PreparedQuestionGrantService grants;
    PreparedAttemptStartService starts;
    PreparedAttemptStart.Summary attempt;
    int student;
    final String client=key();
    @BeforeEach void setup() {
        f.setup();now.set(Instant.parse("2026-10-04T03:00:00.123Z"));
        String account="prepared-question-"+key();
        f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('逐题授权联调',?,'not-a-login-credential',0)",account);
        student=f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);
        f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",f.assessment,student);
        f.jdbc.update("UPDATE assessment SET total_score=30,question_count=3 WHERE id=?",f.assessment);
        questions.addAll(List.of(f.questionId,key(),key()));
        f.tx.execute(s->f.papers.save(f.actor,f.assessment,0,List.of(input(0),input(1),input(2))));
        f.tx.execute(s->f.papers.approve(f.actor,f.assessment,1,questions));
        f.syntheticAudio("题目 1");
        for(int ordinal=2;ordinal<=3;ordinal++)syntheticAudio(ordinal);
        f.publish(f.command(2));
        var clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
        var eligibility=new PreparedStudentEligibilityRepository(f.jdbc);var attempts=new PreparedAttemptStartRepository(f.jdbc);
        starts=new PreparedAttemptStartService(f.access,eligibility,attempts,clock);
        grants=new PreparedQuestionGrantService(f.access,eligibility,attempts,new PreparedQuestionGrantRepository(f.jdbc),clock);
        attempt=f.tx.execute(s->starts.start(student,f.assessment,new PreparedAttemptStart.Command(key())));
    }
    PreparedPaper.QuestionInput input(int i){return new PreparedPaper.QuestionInput(questions.get(i),"题目 "+(i+1),10,120,"不应泄漏的教师标准 "+i);}
    void syntheticAudio(int ordinal) {
        String question=questions.get(ordinal-1),asset=key(),object="prepared/prepared-local/assessments/"+f.assessment+"/questions/"+question+"/audio/"+asset+".wav";
        var job=f.jobs.enqueue(f.actor,f.assessment,PreparedJobLedger.Kind.TTS,2,key(),"{\"syntheticFixture\":true}");
        f.jdbc.update("UPDATE prepared_job SET state='SUCCEEDED',output_json='{}' WHERE id=?",job.id());
        f.jdbc.update("""
                INSERT INTO prepared_audio_asset(id,assessment_id,question_id,body_sha256,config_sha256,job_id,state,object_key,content_sha256,byte_size,duration_ms,media_type,verified_at)
                VALUES (?,?,?,?,?,?,'READY',?,?,48044,1000,'audio/wav',CURRENT_TIMESTAMP(3))
                """,asset,f.assessment,question,PreparedJobInput.digest("题目 "+ordinal),"a".repeat(64),job.id(),object,"b".repeat(64));
        f.jdbc.update("UPDATE prepared_question_draft SET audio_state='READY',audio_object_key=? WHERE assessment_id=? AND question_id=?",object,f.assessment,question);
    }
    @AfterEach void cleanupOnlyThisFixture() {
        // Fixture teardown only: scoped original audit now references answers/attempts.
        // Remove THIS fixture's events first; never relax the production foreign keys.
        f.jdbc.update("DELETE FROM prepared_audit WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM prepared_question_start_receipt WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM prepared_answer WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM prepared_attempt_start_receipt WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM prepared_attempt_anchor WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM prepared_attempt WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM student_assessment_record WHERE assessment_id=?",f.assessment);
        f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?",f.assessment);
        f.cleanupOnlyOwnedRows();f.jdbc.update("DELETE FROM `user` WHERE id=?",student);
    }
    static String key(){return UUID.randomUUID().toString();}
    PreparedQuestionGrant.Grant grant(String command,String writer,int ordinal){return f.tx.execute(s->grants.start(student,f.assessment,attempt.id(),new PreparedQuestionGrant.Command(command,writer,ordinal)));}
    PreparedQuestionGrant.Grant grant(int ordinal){return grant(key(),client,ordinal);}
    int count(String table){return f.jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE assessment_id=?",Integer.class,f.assessment);}
    /** This is a fixture state, NOT an implemented terminal/upload endpoint or server receipt. */
    void terminalFixture(PreparedQuestionGrant.Grant grant,String state){f.jdbc.update("UPDATE prepared_answer SET state=? WHERE id=?",state,grant.answerId());}
    void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);}

    @Test void firstGrantReturnsOnlyCurrentQuestionAndPersistsOriginalServerTimesWithoutAnOverallDeadline() throws Exception {
        var first=grant(1);assertThat(first.attemptId()).isEqualTo(attempt.id());assertThat(first.paperVersionId()).isEqualTo(attempt.paperVersionId());
        assertThat(first.questionId()).isEqualTo(questions.getFirst());assertThat(first.body()).isEqualTo("题目 1");assertThat(first.ordinal()).isEqualTo(1);
        assertThat(first.answerSeconds()).isEqualTo(120);assertThat(first.startedAt()).isEqualTo(now.get().toString());
        assertThat(first.answerDeadline()).isEqualTo(now.get().plusSeconds(120).toString());assertThat(first.mayRecord()).isTrue();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(first)).doesNotContain("rubric","教师标准","objectKey","maxScore","题目 2","题目 3",client,"overallDeadline","attemptDeadline");
        assertThat(count("prepared_answer")).isEqualTo(1);assertThat(starts.current(student,f.assessment).orElseThrow().answeringEndedAt()).isNull();
    }
    @Test void oneHundredRetriesSameOrDifferentKeyNeverCreateAnotherAnswerOrExtendTime() {
        String command=key();var original=grant(command,client,1);now.set(now.get().plusSeconds(30));
        for(int i=0;i<100;i++){var replay=grant(command,client,1);assertThat(replay.answerId()).isEqualTo(original.answerId());assertThat(replay.startedAt()).isEqualTo(original.startedAt());assertThat(replay.answerDeadline()).isEqualTo(original.answerDeadline());}
        var another=grant(1);assertThat(another.answerId()).isEqualTo(original.answerId());assertThat(count("prepared_answer")).isEqualTo(1);
        assertThat(count("prepared_question_start_receipt")).isEqualTo(2);
    }
    @Test void parallelRequestsFromTheSameWriterGetOneGrantAndOneOriginalClock() throws Exception {
        var barrier=new CountDownLatch(1);var results=new ArrayList<Future<PreparedQuestionGrant.Grant>>();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            for(int i=0;i<12;i++)results.add(executor.submit(()->{barrier.await();return grant(1);}));barrier.countDown();
            String id=results.getFirst().get(15,TimeUnit.SECONDS).answerId();for(var result:results)assertThat(result.get(15,TimeUnit.SECONDS).answerId()).isEqualTo(id);
        }
        assertThat(count("prepared_answer")).isEqualTo(1);assertThat(count("prepared_question_start_receipt")).isEqualTo(12);
    }
    @Test void differentClientsRacingForTheFirstQuestionCannotBothReceiveRecordingPermission() throws Exception {
        var barrier=new CountDownLatch(1);var writers=List.of(client,key());var outcomes=new ArrayList<Future<Object>>();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            for(String writer:writers)outcomes.add(executor.submit(()->{barrier.await();try{return grant(key(),writer,1);}catch(BusinessException error){return error.getCode();}}));
            barrier.countDown();var values=new ArrayList<Object>();for(var outcome:outcomes)values.add(outcome.get(15,TimeUnit.SECONDS));
            assertThat(values.stream().filter(v->v instanceof PreparedQuestionGrant.Grant).count()).isEqualTo(1);
            assertThat(values.stream().filter(v->Integer.valueOf(409).equals(v)).count()).isEqualTo(1);
        }
        assertThat(count("prepared_answer")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void earlierAuthorizationSnapshotDoesNotHideAConcurrentCommittedQuestionGrant(boolean sameKey) throws Exception {
        String command=key(),waitingCommand=sameKey?command:key();var ready=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        doAnswer(invocation->{if(once.compareAndSet(false,true)){assertThat(count("prepared_answer")).isZero();ready.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}return null;}).when(f.access).requireAssignedStudent(student,f.assessment);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var waiting=executor.submit(()->grant(waitingCommand,client,1));assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();
            var original=grant(command,client,1);release.countDown();assertThat(waiting.get(10,TimeUnit.SECONDS).answerId()).isEqualTo(original.answerId());
        }finally{release.countDown();}
        assertThat(count("prepared_answer")).isEqualTo(1);
    }
    @Test void lostResponseReadOnlyRecoveryDoesNotStartAnUnstartedQuestionOrLeakWriterIdentity() throws Exception {
        assertThat(grants.current(student,f.assessment,attempt.id(),client)).isEmpty();assertThat(count("prepared_answer")).isZero();
        var original=grant(1);now.set(now.get().plusSeconds(5));var recovered=grants.current(student,f.assessment,attempt.id(),client).orElseThrow();
        assertThat(recovered.answerId()).isEqualTo(original.answerId());assertThat(recovered.answerDeadline()).isEqualTo(original.answerDeadline());assertThat(recovered.mayRecord()).isTrue();
        var other=grants.current(student,f.assessment,attempt.id(),key()).orElseThrow();assertThat(other.mayRecord()).isFalse();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(other)).doesNotContain(client,"rubric","objectKey");
        assertThat(count("prepared_question_start_receipt")).isEqualTo(1);
    }
    @Test void originalDeadlineBoundaryAndLongOfflineGapNeverRenewRecordingPermissionOrAutoPunish() {
        String command=key();var original=grant(command,client,1);Instant deadline=Instant.parse(original.answerDeadline());
        now.set(deadline.minusMillis(1));assertThat(grant(command,client,1).mayRecord()).isTrue();
        now.set(deadline);assertThat(grant(command,client,1).mayRecord()).isFalse();
        now.set(deadline.plus(Duration.ofDays(30)));assertThat(grant(1).mayRecord()).isFalse();conflict(()->grant(key(),key(),1));conflict(()->grant(2));
        assertThat(starts.current(student,f.assessment).orElseThrow().state()).isEqualTo("IN_PROGRESS");
        assertThat(starts.current(student,f.assessment).orElseThrow().supplementalUploadDeadline()).isNull();
        assertThat(f.jdbc.queryForObject("SELECT state FROM student_assessment_record WHERE assessment_id=?",Integer.class,f.assessment)).isZero();
        assertThat(count("prepared_answer")).isEqualTo(1);
    }
    @Test void finishedAnswerCannotBeVoluntarilyRestartedAndPendingUploadsDoNotBlockTheNextAuthorizedQuestion() {
        conflict(()->grant(2));conflict(()->grant(3));var first=grant(1);conflict(()->grant(2));
        terminalFixture(first,"REQUIRES_REVIEW");conflict(()->grant(2));assertThat(grant(1).mayRecord()).isFalse();
        terminalFixture(first,"UPLOAD_PENDING");now.set(now.get().plus(Duration.ofDays(10)));var second=grant(2);
        assertThat(second.startedAt()).isEqualTo(now.get().toString());assertThat(second.answerDeadline()).isEqualTo(now.get().plusSeconds(120).toString());
        assertThat(grant(1).answerId()).isEqualTo(first.answerId());assertThat(grant(1).mayRecord()).isFalse();conflict(()->grant(3));
        terminalFixture(second,"SAVED");assertThat(grant(3).ordinal()).isEqualTo(3);conflict(()->grant(4));
        assertThat(count("prepared_answer")).isEqualTo(3);assertThat(starts.current(student,f.assessment).orElseThrow().answeringEndedAt()).isNull();
    }
    @Test void sameKeyDifferentOrdinalOrWriterIsAConflictAndProducesNoExtraAnswer() {
        String command=key();var original=grant(command,client,1);terminalFixture(original,"UPLOAD_PENDING");
        conflict(()->grant(command,client,2));conflict(()->grant(command,key(),1));assertThat(count("prepared_answer")).isEqualTo(1);
    }
    @Test void terminalAttemptDoesNotAuthorizeMoreRecordingEvenWithAnOriginalReceipt() {
        String command=key();var first=grant(command,client,1);terminalFixture(first,"UPLOAD_PENDING");
        for(String state:List.of("UPLOAD_PENDING","SUBMITTED","REQUIRES_REVIEW","CANCELLED")){
            f.jdbc.update("UPDATE prepared_attempt SET state=? WHERE id=?",state,attempt.id());
            assertThat(grant(command,client,1).mayRecord()).isFalse();conflict(()->grant(2));
        }
        assertThat(count("prepared_answer")).isEqualTo(1);
    }
    @Test void eachCommandAndReadRecheckCurrentStudentRoleAssignmentArchiveAndAttemptOwnership() {
        String command=key();grant(command,client,1);
        doThrow(new BusinessException(403,"无权限")).when(f.access).requireAssignedStudent(student,f.assessment);assertThatThrownBy(()->grant(command,client,1)).isInstanceOf(BusinessException.class);reset(f.access);
        f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",student);assertThatThrownBy(()->grant(command,client,1)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",student);f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?",f.assessment);
        assertThatThrownBy(()->grants.current(student,f.assessment,attempt.id(),client)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",f.assessment,student);
        assertThatThrownBy(()->f.tx.execute(s->grants.start(student,f.assessment,key(),new PreparedQuestionGrant.Command(key(),client,1)))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",f.assessment);assertThatThrownBy(()->grant(command,client,1)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
    }
    @Test void invalidInputsAreRejectedBeforeAccessOrPersistence() {
        reset(f.access);
        for(String invalid:Arrays.asList(null,"no-uuid",key().toUpperCase())){
            assertThatThrownBy(()->grant(invalid,client,1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->grant(key(),invalid,1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->grants.current(student,f.assessment,invalid,client)).isInstanceOf(IllegalArgumentException.class);
        }
        for(int ordinal:new int[]{-1,0,51})assertThatThrownBy(()->grant(ordinal)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->grants.start(student,f.assessment,attempt.id(),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->grants.current(0,f.assessment,attempt.id(),client)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->grants.current(student,0,attempt.id(),client)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->grants.current(student,f.assessment,attempt.id(),null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(f.access);assertThat(count("prepared_answer")).isZero();
    }
    @Test void corruptCanonicalPaperOrRelationalQuestionCannotGrantOrLeakQuestionText() {
        f.jdbc.update("UPDATE prepared_question_snapshot SET body='swapped question' WHERE paper_version_id=? AND ordinal=1",attempt.paperVersionId());
        assertThatThrownBy(()->grant(1)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("swapped");assertThat(count("prepared_answer")).isZero();
        f.jdbc.update("UPDATE prepared_question_snapshot SET body='题目 1' WHERE paper_version_id=? AND ordinal=1",attempt.paperVersionId());
        grant(1);f.jdbc.update("UPDATE prepared_paper_version SET content_sha256=? WHERE id=?","c".repeat(64),attempt.paperVersionId());
        assertThatThrownBy(()->grant(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->grants.current(student,f.assessment,attempt.id(),client)).isInstanceOf(IllegalStateException.class);assertThat(count("prepared_answer")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"ordinal","answer_seconds","max_score","rubric","audio_asset_id","question_id"})
    void everyRelationalQuestionBindingIsCheckedBeforeGranting(String field) {
        Object value=switch(field){
            case "ordinal"->4;case "answer_seconds"->121;case "max_score"->11;case "rubric"->"changed teacher-only rubric";
            case "audio_asset_id"->f.jdbc.queryForObject("SELECT audio_asset_id FROM prepared_question_snapshot WHERE paper_version_id=? AND ordinal=2",String.class,attempt.paperVersionId());
            case "question_id"->key();default->throw new AssertionError("test field not allowed");
        };
        // Field is exclusively the fixed parameterized-test whitelist above, never request input.
        f.jdbc.update("UPDATE prepared_question_snapshot SET "+field+"=? WHERE paper_version_id=? AND ordinal=1",value,attempt.paperVersionId());
        assertThatThrownBy(()->grant(1)).isInstanceOf(IllegalStateException.class).hasMessage("题目快照绑定验证失败");
        assertThat(count("prepared_answer")).isZero();assertThat(count("prepared_question_start_receipt")).isZero();
    }
    @Test void aScopedButWrongAnswerInAnOriginalReceiptCannotReturnAnotherQuestion() {
        String command=key();var first=grant(command,client,1);terminalFixture(first,"UPLOAD_PENDING");var second=grant(2);
        f.jdbc.update("UPDATE prepared_question_start_receipt SET answer_id=? WHERE assessment_id=? AND idempotency_key=?",second.answerId(),f.assessment,command);
        assertThatThrownBy(()->grant(command,client,1)).isInstanceOf(IllegalStateException.class).hasMessage("原开题回执绑定无效");
        assertThat(count("prepared_answer")).isEqualTo(2);
    }
    @Test void rollbackAndCorruptCommandReceiptNeverCreateAnotherGrant() {
        assertThatThrownBy(()->f.tx.execute(s->{grants.start(student,f.assessment,attempt.id(),new PreparedQuestionGrant.Command(key(),client,1));throw new IllegalStateException("explicit rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(count("prepared_answer")).isZero();assertThat(count("prepared_question_start_receipt")).isZero();
        String command=key();grant(command,client,1);f.jdbc.update("UPDATE prepared_question_start_receipt SET request_hash=? WHERE assessment_id=?","d".repeat(64),f.assessment);
        conflict(()->grant(command,client,1));assertThat(count("prepared_answer")).isEqualTo(1);
    }
    @Test void clockMovingBeforeAttemptStartCannotIssueABackdatedQuestionGrant() {
        now.set(Instant.parse(attempt.startedAt()).minusMillis(1));
        assertThatThrownBy(()->grant(1)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        assertThat(count("prepared_answer")).isZero();assertThat(count("prepared_question_start_receipt")).isZero();
    }
    @Test void storedQuestionTimesBeforeTheOriginalAttemptFailClosedEvenWithAnOtherwiseValidDeadline() {
        var first=grant(1);
        f.jdbc.update("UPDATE prepared_answer SET started_at=TIMESTAMPADD(SECOND,-1,started_at),answer_deadline=TIMESTAMPADD(SECOND,-1,answer_deadline) WHERE id=?",first.answerId());
        assertThatThrownBy(()->grant(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->grants.current(student,f.assessment,attempt.id(),client)).isInstanceOf(IllegalStateException.class);
        assertThat(count("prepared_answer")).isEqualTo(1);
    }
    @Test void databaseConstraintsPreventDifferentPaperStudentQuestionOrderAndTimeBindings() {
        var first=grant(1);
        assertThatThrownBy(()->f.jdbc.update("UPDATE prepared_answer SET student_id=? WHERE id=?",f.actor,first.answerId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->f.jdbc.update("UPDATE prepared_answer SET paper_version_id=? WHERE id=?",key(),first.answerId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->f.jdbc.update("UPDATE prepared_answer SET question_id=? WHERE id=?",questions.get(1),first.answerId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->f.jdbc.update("UPDATE prepared_answer SET answer_seconds=121 WHERE id=?",first.answerId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->f.jdbc.update("UPDATE prepared_answer SET answer_deadline=TIMESTAMPADD(SECOND,1,answer_deadline) WHERE id=?",first.answerId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("prepared_answer")).isEqualTo(1);
    }
}
