package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static system.assessment.defense.prepared.attempt.PreparedAnswerEnd.*;

/** Real SQL declaration/sequence/window transactions; metadata is synthetic, NOT uploaded bytes or complete interview QA. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAnswerEndLiveTest {
    final PreparedQuestionGrantLiveTest q=new PreparedQuestionGrantLiveTest();
    PreparedAnswerEndService service;
    @BeforeEach void setup(){q.setup();service=service();}
    PreparedAnswerEndService service(){return new PreparedAnswerEndService(q.f.access,new PreparedStudentEligibilityRepository(q.f.jdbc),new PreparedAttemptStartRepository(q.f.jdbc),
        new PreparedQuestionGrantRepository(q.f.jdbc),new PreparedAnswerEndRepository(q.f.jdbc),new PreparedAnswerUploadRepository(q.f.jdbc),new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return q.now.get();}});}
    @AfterEach void cleanup(){q.f.jdbc.update("DELETE FROM prepared_answer_end_receipt WHERE assessment_id=?",q.f.assessment);q.f.jdbc.update("DELETE FROM prepared_answer_end WHERE assessment_id=?",q.f.assessment);q.cleanupOnlyThisFixture();}
    static String key(){return UUID.randomUUID().toString();}
    Manifest manifest(){return new Manifest(1,List.of(new Segment(key(),1,"audio/webm;codecs=opus",1000L,2000L,"SEALED",null,2,6L,List.of(new Chunk(0,3,"a".repeat(64)),new Chunk(1,3,"b".repeat(64))))));}
    Command command(Manifest manifest){return new Command(key(),q.client,manifest);}
    Summary end(String answer,Command command){return q.f.tx.execute(s->service.end(q.student,q.f.assessment,q.attempt.id(),answer,command));}
    PreparedAttemptStart.Summary attempt(){return q.starts.current(q.student,q.f.assessment).orElseThrow();}
    int count(String table){return q.f.jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE assessment_id=?",Integer.class,q.f.assessment);}
    void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);}
    @Test void reliableDeclarationDoesNotPretendCloudSavedAndAuthorizesFixedNextQuestionWithoutWaitingForUploads() throws Exception {
        var first=q.grant(1);q.now.set(q.now.get().plusSeconds(10));var command=command(manifest());var result=end(first.answerId(),command);
        assertThat(result.answerId()).isEqualTo(first.answerId());assertThat(result.declarationState()).isEqualTo("UPLOAD_PENDING");assertThat(result.serverSaved()).isFalse();
        assertThat(result.effectiveEndedAt()).isEqualTo(q.now.get().toString());assertThat(result.acceptedAt()).isEqualTo(q.now.get().toString());
        assertThat(result.reviewReasons()).isEmpty();assertThat(attempt().answeringEndedAt()).isNull();
        assertThat(result.attemptRevision()).isEqualTo(attempt().revision());
        assertThat(q.grant(1).mayRecord()).isFalse();assertThat(q.grant(2).ordinal()).isEqualTo(2);
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        assertThat(json).doesNotContain("rubric","objectKey","submissionReceipt",q.client,"a".repeat(64));
        assertThat(count("prepared_answer_end")).isEqualTo(1);assertThat(q.f.jdbc.queryForObject("SELECT state FROM student_assessment_record WHERE assessment_id=?",Integer.class,q.f.assessment)).isZero();
    }
    @Test void oneHundredRetriesAndAFreshServiceRestoreTheOriginalManifestAndEndTimeWithoutReanswering() {
        var first=q.grant(1);var command=command(manifest());var original=end(first.answerId(),command);q.now.set(q.now.get().plusSeconds(60));
        for(int i=0;i<100;i++)assertThat(end(first.answerId(),command)).isEqualTo(original);
        service=service();assertThat(end(first.answerId(),new Command(key(),q.client,command.manifest()))).isEqualTo(original);
        assertThat(count("prepared_answer_end")).isEqualTo(1);assertThat(count("prepared_answer_end_receipt")).isEqualTo(2);
        assertThat(q.f.jdbc.queryForObject("SELECT revision FROM prepared_answer WHERE id=?",Long.class,first.answerId())).isEqualTo(1);
    }
    @Test void sameKeyOrNewKeyChangingOriginalBytesOrTerminalInformationIsAlwaysAConflict() {
        var first=q.grant(1);var command=command(manifest());end(first.answerId(),command);
        for(Command changed:List.of(new Command(command.idempotencyKey(),q.client,manifest()),command(manifest()),new Command(command.idempotencyKey(),key(),command.manifest())))conflict(()->end(first.answerId(),changed));
        assertThat(count("prepared_answer_end")).isEqualTo(1);assertThat(count("prepared_answer_end_receipt")).isEqualTo(1);
    }
    @Test void simultaneousOriginalEndCommandsHaveOneDurableHeadAndOneRevisionChange() throws Exception {
        var first=q.grant(1);var manifest=manifest();var latch=new CountDownLatch(1);var results=new ArrayList<Future<Summary>>();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            for(int i=0;i<12;i++)results.add(executor.submit(()->{latch.await();return end(first.answerId(),command(manifest));}));latch.countDown();
            var original=results.getFirst().get(15,TimeUnit.SECONDS);for(var result:results)assertThat(result.get(15,TimeUnit.SECONDS)).isEqualTo(original);
        }
        assertThat(count("prepared_answer_end")).isEqualTo(1);assertThat(count("prepared_answer_end_receipt")).isEqualTo(12);
        assertThat(q.f.jdbc.queryForObject("SELECT revision FROM prepared_answer WHERE id=?",Long.class,first.answerId())).isEqualTo(1);
    }
    @Test void earlierAuthorizationSnapshotCannotHideACommittedOriginalEnd() throws Exception {
        var first=q.grant(1);var command=command(manifest());var ready=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        doAnswer(inv->{if(once.compareAndSet(false,true)){assertThat(count("prepared_answer_end")).isZero();ready.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}return null;}).when(q.f.access).requireAssignedStudent(q.student,q.f.assessment);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var waiting=executor.submit(()->end(first.answerId(),command));assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();var original=end(first.answerId(),command);release.countDown();assertThat(waiting.get(10,TimeUnit.SECONDS)).isEqualTo(original);
        }finally{release.countDown();}
        assertThat(count("prepared_answer_end")).isEqualTo(1);
    }
    @Test void allThreeRealEndCommandsFixOneBackfillWindowButNeverCreateASubmissionOrModelTask() {
        var commands=new ArrayList<Command>();var answers=new ArrayList<String>();
        for(int ordinal=1;ordinal<=3;ordinal++){
            var grant=q.grant(ordinal);answers.add(grant.answerId());q.now.set(q.now.get().plusSeconds(5));var command=command(manifest());commands.add(command);end(grant.answerId(),command);
            if(ordinal<3){assertThat(attempt().answeringEndedAt()).isNull();assertThat(attempt().supplementalUploadDeadline()).isNull();}
        }
        var finished=attempt();assertThat(finished.state()).isEqualTo("UPLOAD_PENDING");assertThat(finished.answeringEndedAt()).isEqualTo(q.now.get().toString());
        assertThat(end(answers.getFirst(),commands.getFirst()).attemptRevision()).isEqualTo(finished.revision());
        assertThat(finished.supplementalUploadDeadline()).isEqualTo(q.now.get().plus(Duration.ofHours(24)).toString());
        q.now.set(q.now.get().plusSeconds(100));for(int i=0;i<3;i++)end(answers.get(i),commands.get(i));assertThat(attempt()).isEqualTo(finished);
        assertThat(q.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=? AND kind IN ('TRANSCRIBE','SCORE')",Integer.class,q.f.assessment)).isZero();
        assertThat(q.f.jdbc.queryForObject("SELECT state FROM student_assessment_record WHERE assessment_id=?",Integer.class,q.f.assessment)).isZero();
    }
    @Test void lateFinalDeclarationUsesOriginalQuestionDeadlineAndCannotManufactureAFreshTwentyFourHours() {
        for(int i=1;i<=2;i++){var grant=q.grant(i);end(grant.answerId(),command(manifest()));}
        var last=q.grant(3);var deadline=Instant.parse(last.answerDeadline());q.now.set(deadline.plus(Duration.ofDays(2)));var command=command(manifest());var result=end(last.answerId(),command);
        assertThat(result.effectiveEndedAt()).isEqualTo(deadline.toString());assertThat(result.acceptedAt()).isEqualTo(q.now.get().toString());
        assertThat(attempt().answeringEndedAt()).isEqualTo(deadline.toString());assertThat(attempt().supplementalUploadDeadline()).isEqualTo(deadline.plus(Duration.ofHours(24)).toString());
        assertThat(attempt().state()).isEqualTo("REQUIRES_REVIEW");q.now.set(q.now.get().plus(Duration.ofDays(10)));assertThat(end(last.answerId(),command)).isEqualTo(result);
        assertThat(q.f.jdbc.queryForObject("SELECT score FROM student_assessment_record WHERE assessment_id=?",Integer.class,q.f.assessment)).isNull();
    }
    @Test void aLongGapBeforeLaterQuestionsDoesNotBecomeAnOverallDeadlineOrStartTheBackfillWindow() {
        var first=q.grant(1);q.now.set(q.now.get().plus(Duration.ofDays(30)));end(first.answerId(),command(manifest()));
        assertThat(attempt().state()).isEqualTo("IN_PROGRESS");assertThat(attempt().answeringEndedAt()).isNull();assertThat(q.grant(2).mayRecord()).isTrue();
    }
    @Test void interruptedEmptyAndOverlongDeclarationsRetainMetadataForReviewRatherThanInventingCompleteMedia() {
        var first=q.grant(1);
        var empty=new Manifest(1,List.of(new Segment(key(),1,"audio/webm",1000L,2000L,"INTERRUPTED","DEVICE_ENDED",0,0L,List.of())));
        var result=end(first.answerId(),command(empty));assertThat(result.declarationState()).isEqualTo("REQUIRES_REVIEW");assertThat(result.reviewReasons()).contains("CAPTURE_INTERRUPTED","EMPTY_AUDIO");
        assertThat(q.grant(1).mayRecord()).isFalse();conflict(()->q.grant(2));assertThat(attempt().supplementalUploadDeadline()).isNull();
    }
    @Test void revokeRoleAssignmentArchiveOrUseAnotherWriterOrAnswerCannotConfirmEvenAnOriginalReceipt() {
        var first=q.grant(1);var command=command(manifest());end(first.answerId(),command);
        conflict(()->end(first.answerId(),new Command(key(),key(),command.manifest())));
        assertThatThrownBy(()->end(key(),command)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        q.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",q.student);assertThatThrownBy(()->end(first.answerId(),command)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        q.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",q.student);q.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=?",q.f.assessment);
        assertThatThrownBy(()->end(first.answerId(),command)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        q.f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",q.f.assessment,q.student);
        q.f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",q.f.assessment);assertThatThrownBy(()->end(first.answerId(),command)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThat(count("prepared_answer_end")).isEqualTo(1);
    }
    @Test void transactionFailureRollsBackHeadReceiptAnswerStateAndAuditTogether() {
        var first=q.grant(1);var command=command(manifest());
        assertThatThrownBy(()->q.f.tx.execute(s->{service.end(q.student,q.f.assessment,q.attempt.id(),first.answerId(),command);throw new IllegalStateException("explicit rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(count("prepared_answer_end")).isZero();assertThat(count("prepared_answer_end_receipt")).isZero();assertThat(q.grant(1).mayRecord()).isTrue();
    }
    @Test void corruptedOriginalEndTimeOutsideItsQuestionGrantMustNotBeReplayed() {
        var first=q.grant(1);var command=command(manifest());end(first.answerId(),command);
        q.f.jdbc.update("UPDATE prepared_answer_end SET effective_ended_at=TIMESTAMPADD(SECOND,-1,effective_ended_at) WHERE answer_id=?",first.answerId());
        assertThatThrownBy(()->end(first.answerId(),command)).isInstanceOf(IllegalStateException.class);
    }
    @Test void corruptedAcceptedTimeCannotChangeTheOriginalServerDerivedEnd() {
        var first=q.grant(1);var command=command(manifest());end(first.answerId(),command);
        q.f.jdbc.update("UPDATE prepared_answer_end SET accepted_at=TIMESTAMPADD(SECOND,1,accepted_at) WHERE answer_id=?",first.answerId());
        assertThatThrownBy(()->end(first.answerId(),command)).isInstanceOf(IllegalStateException.class);
    }
    @Test void supplementalBoundaryIsFixedEndExclusiveAndSubmittedFixtureIsNeverDowngradedByAReplay() {
        Command finalCommand=null;String finalAnswer=null;
        for(int i=1;i<=3;i++){var grant=q.grant(i);finalCommand=command(manifest());finalAnswer=grant.answerId();end(finalAnswer,finalCommand);}
        var original=attempt();Instant deadline=Instant.parse(original.supplementalUploadDeadline());
        q.now.set(deadline.minusMillis(1));end(finalAnswer,finalCommand);assertThat(attempt().state()).isEqualTo("UPLOAD_PENDING");
        q.now.set(deadline);end(finalAnswer,finalCommand);assertThat(attempt().state()).isEqualTo("REQUIRES_REVIEW");assertThat(attempt().supplementalUploadDeadline()).isEqualTo(original.supplementalUploadDeadline());
        // Explicit future-submission state fixture only; this does not implement or prove a submission endpoint.
        q.f.jdbc.update("UPDATE prepared_attempt SET state='SUBMITTED',requires_supplement_review=0 WHERE id=?",q.attempt.id());q.now.set(deadline.plusSeconds(10));
        end(finalAnswer,finalCommand);assertThat(attempt().state()).isEqualTo("SUBMITTED");
    }
    @Test void invalidCommandsAreRejectedBeforeAnyAuthorizationOrPersistence() {
        reset(q.f.access);var command=command(manifest());
        assertThatThrownBy(()->service.end(0,q.f.assessment,q.attempt.id(),key(),command)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.end(q.student,0,q.attempt.id(),key(),command)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.end(q.student,q.f.assessment,q.attempt.id(),key(),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.end(q.student,q.f.assessment,"invalid",key(),command)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.end(q.student,q.f.assessment,q.attempt.id(),"invalid",command)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.end(q.student,q.f.assessment,q.attempt.id(),key(),new Command("invalid",q.client,command.manifest()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.end(q.student,q.f.assessment,q.attempt.id(),key(),new Command(key(),"invalid",command.manifest()))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(q.f.access);assertThat(count("prepared_answer_end")).isZero();
    }
    @Test void unknownAttemptWrongStateAndClockBeforeOriginalGrantCannotCreateANewEnd() {
        var first=q.grant(1);var command=command(manifest());
        assertThatThrownBy(()->q.f.tx.execute(s->service.end(q.student,q.f.assessment,key(),first.answerId(),command))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        q.f.jdbc.update("UPDATE prepared_attempt SET state='CANCELLED' WHERE id=?",q.attempt.id());conflict(()->end(first.answerId(),command));
        q.f.jdbc.update("UPDATE prepared_attempt SET state='IN_PROGRESS' WHERE id=?",q.attempt.id());q.terminalFixture(first,"UPLOAD_PENDING");conflict(()->end(first.answerId(),command));
        q.terminalFixture(first,"RECORDING");q.now.set(Instant.parse(first.startedAt()).minusMillis(1));
        assertThatThrownBy(()->end(first.answerId(),command)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);assertThat(count("prepared_answer_end")).isZero();
    }
    @Test void corruptOriginalMetadataAndReceiptFailClosedWithoutRebindingOrDeletingEvidence() {
        var first=q.grant(1);var command=command(manifest());end(first.answerId(),command);
        q.f.jdbc.update("UPDATE prepared_answer_end_receipt SET request_hash=? WHERE assessment_id=?","d".repeat(64),q.f.assessment);conflict(()->end(first.answerId(),command));
        q.f.jdbc.update("DELETE FROM prepared_answer_end_receipt WHERE assessment_id=?",q.f.assessment);
        q.f.jdbc.update("UPDATE prepared_answer_end SET byte_size=7 WHERE answer_id=?",first.answerId());
        assertThatThrownBy(()->end(first.answerId(),command)).isInstanceOf(IllegalStateException.class).hasMessage("原录音结束记录完整性验证失败");
        q.f.jdbc.update("UPDATE prepared_answer_end SET byte_size=6,review_reasons='null' WHERE answer_id=?",first.answerId());
        assertThatThrownBy(()->end(first.answerId(),command)).isInstanceOf(IllegalStateException.class).hasMessage("原结束记录无法核验");assertThat(count("prepared_answer_end")).isEqualTo(1);
    }
    @Test void corruptBoundPaperAndQuestionCannotCloseOrMutateAnOriginalAnswer() {
        var first=q.grant(1);var command=command(manifest());
        q.f.jdbc.update("UPDATE prepared_question_snapshot SET body='tampered teacher question' WHERE paper_version_id=? AND ordinal=1",q.attempt.paperVersionId());
        assertThatThrownBy(()->end(first.answerId(),command)).isInstanceOf(IllegalStateException.class);
        q.f.jdbc.update("UPDATE prepared_question_snapshot SET body='题目 1' WHERE paper_version_id=? AND ordinal=1",q.attempt.paperVersionId());
        q.f.jdbc.update("UPDATE prepared_paper_version SET content_sha256=? WHERE id=?","c".repeat(64),q.attempt.paperVersionId());
        assertThatThrownBy(()->end(first.answerId(),command)).isInstanceOf(IllegalStateException.class);assertThat(count("prepared_answer_end")).isZero();
    }
    @Test void finalInterruptedDeclarationFixesTheWindowButKeepsTheWholeAttemptForReview() {
        for(int i=1;i<=2;i++){var grant=q.grant(i);end(grant.answerId(),command(manifest()));}
        var last=q.grant(3);var broken=new Manifest(1,List.of(new Segment(key(),1,"audio/webm",1000L,2000L,"INTERRUPTED","DEVICE_ENDED",0,0,List.of())));
        var original=end(last.answerId(),command(broken));assertThat(original.declarationState()).isEqualTo("REQUIRES_REVIEW");
        assertThat(attempt().state()).isEqualTo("REQUIRES_REVIEW");assertThat(attempt().supplementalUploadDeadline()).isEqualTo(q.now.get().plus(Duration.ofHours(24)).toString());
        assertThat(original.serverSaved()).isFalse();assertThat(q.f.jdbc.queryForObject("SELECT score FROM student_assessment_record WHERE assessment_id=?",Integer.class,q.f.assessment)).isNull();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"manifest_sha256","review_reasons","declaration_state","early_times"})
    void individuallyCorruptedOriginalHeadCannotHideItsDigestReviewStateOrOriginalTime(String field) {
        var first=q.grant(1);var command=command(manifest());end(first.answerId(),command);
        switch(field){
            case "manifest_sha256"->q.f.jdbc.update("UPDATE prepared_answer_end SET manifest_sha256=? WHERE answer_id=?","c".repeat(64),first.answerId());
            case "review_reasons"->q.f.jdbc.update("UPDATE prepared_answer_end SET review_reasons='[\"CAPTURE_INTERRUPTED\"]' WHERE answer_id=?",first.answerId());
            case "declaration_state"->q.f.jdbc.update("UPDATE prepared_answer_end SET declaration_state='REQUIRES_REVIEW' WHERE answer_id=?",first.answerId());
            case "early_times"->q.f.jdbc.update("UPDATE prepared_answer_end SET accepted_at=TIMESTAMPADD(SECOND,-1,accepted_at),effective_ended_at=TIMESTAMPADD(SECOND,-1,effective_ended_at) WHERE answer_id=?",first.answerId());
            default->throw new AssertionError("unsupported test field");
        }
        assertThatThrownBy(()->end(first.answerId(),command)).isInstanceOf(IllegalStateException.class).hasMessage("原录音结束记录完整性验证失败");assertThat(count("prepared_answer_end")).isEqualTo(1);
    }
    @Test void corruptQuestionGrantBeforeAttemptTimeCannotBeUsedToAcceptAnEnd() {
        var first=q.grant(1);
        q.f.jdbc.update("UPDATE prepared_answer SET started_at=TIMESTAMPADD(SECOND,-1,started_at),answer_deadline=TIMESTAMPADD(SECOND,-1,answer_deadline) WHERE id=?",first.answerId());
        assertThatThrownBy(()->end(first.answerId(),command(manifest()))).isInstanceOf(IllegalStateException.class);assertThat(count("prepared_answer_end")).isZero();
    }
}
