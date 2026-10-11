package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL permissions, original manifests, expiry and races. Private OSS is an explicit SDK double. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedSupplementLiveTest {
    final PreparedAnswerUploadLiveTest f=new PreparedAnswerUploadLiveTest();
    PreparedSupplementService service;
    String answer;
    @BeforeEach void setup()throws Exception {
        f.setup();answer=f.grant.answerId();var q=f.ends.q;
        service=new PreparedSupplementService(q.f.access,new PreparedAttemptStartRepository(q.f.jdbc),f.uploads,new PreparedAnswerEndRepository(q.f.jdbc),q.f.jdbc,q.f.tx.getTransactionManager(),
            new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return q.now.get();}});
    }
    @AfterEach void cleanup(){f.ends.q.f.jdbc.update("DELETE FROM prepared_supplement_grant WHERE assessment_id=?",f.ends.q.f.assessment);f.cleanup();}
    PreparedSupplement.View status(){var q=f.ends.q;return service.status(q.f.actor,q.student,q.f.assessment,q.attempt.id(),answer);}
    PreparedSupplement.Receipt approve(PreparedSupplement.Command command){var q=f.ends.q;return service.approve(q.f.actor,q.student,q.f.assessment,q.attempt.id(),answer,command);}
    PreparedSupplement.Command command(){var s=status();return new PreparedSupplement.Command(UUID.randomUUID().toString(),s.approvalRevision(),s.attemptRevision(),s.manifestSha256(),f.ends.q.now.get().plusSeconds(3600).toString(),"已核验原清单，只补传原录音");}
    void expired()throws Exception {f.finishAll();f.ends.q.now.set(Instant.parse(f.ends.attempt().supplementalUploadDeadline()));conflict(()->f.upload(f.command(),f.bytes));}
    void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable work){assertThatThrownBy(work).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);}
    @Test void approvalOnlyOpensSpecifiedOriginalManifestAndNeverRecordingOrOtherAnswers()throws Exception {
        assertThat(status().mayApprove()).isFalse();expired();var original=f.ends.attempt();var before=f.ends.q.grants.current(f.ends.q.student,f.ends.q.f.assessment,original.id(),f.ends.q.client).orElseThrow();
        var receipt=approve(command());assertThat(receipt.answerId()).isEqualTo(answer);assertThat(receipt.approvalRevision()).isEqualTo(1);assertThat(status().phase()).isEqualTo("APPROVED");
        assertThat(f.upload(f.command(),f.bytes).confirmed()).isTrue();
        var q=f.ends.q;var other=q.f.jdbc.queryForObject("SELECT id FROM prepared_answer WHERE attempt_id=? AND ordinal=2",String.class,q.attempt.id());
        assertThatThrownBy(()->f.uploads.upload(q.student,q.f.assessment,q.attempt.id(),other,f.command(),new java.io.ByteArrayInputStream(f.bytes))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        var after=q.grants.current(q.student,q.f.assessment,original.id(),q.client).orElseThrow();assertThat(after.answerDeadline()).isEqualTo(before.answerDeadline());assertThat(after.mayRecord()).isFalse();
        assertThat(f.ends.attempt().answeringEndedAt()).isEqualTo(original.answeringEndedAt());assertThat(f.ends.attempt().supplementalUploadDeadline()).isEqualTo(original.supplementalUploadDeadline());
        assertThat(f.ends.attempt().state()).isEqualTo("REQUIRES_REVIEW");assertThat(f.ends.count("prepared_answer")).isEqualTo(3);
        conflict(()->q.grant(4));conflict(()->f.ends.end(answer,f.ends.command(f.ends.manifest())));
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(receipt)).doesNotContain("objectKey",q.client,"serverSaved","rubric");
    }
    @Test void lostApprovalResponseOneHundredReplaysAndNewExtensionPreserveOriginalReceipts()throws Exception {
        expired();var cmd=command();var original=approve(cmd);for(int n=0;n<100;n++)assertThat(approve(cmd)).isEqualTo(original);
        assertThat(f.ends.count("prepared_supplement_grant")).isEqualTo(1);assertThat(f.ends.q.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='SUPPLEMENT_APPROVED'",Integer.class,f.ends.q.f.assessment)).isEqualTo(1);
        f.ends.q.now.set(Instant.parse(original.uploadDeadline()));assertThat(status().phase()).isEqualTo("EXPIRED");assertThat(approve(cmd)).isEqualTo(original);conflict(()->f.upload(f.command(),f.bytes));
        var second=approve(command());assertThat(second.approvalRevision()).isEqualTo(2);assertThat(approve(cmd)).isEqualTo(original);assertThat(f.upload(f.command(),f.bytes).confirmed()).isTrue();
        conflict(()->approve(new PreparedSupplement.Command(cmd.idempotencyKey(),cmd.expectedRevision(),cmd.expectedAttemptRevision(),cmd.manifestSha256(),cmd.uploadDeadline(),"不能同键改原因")));
    }
    @Test void staleVersionsAndConcurrentDistinctCommandsCannotOverwriteApproval()throws Exception {
        expired();var command=command();var first=approve(command);conflict(()->approve(new PreparedSupplement.Command(UUID.randomUUID().toString(),0,first.attemptRevision(),command.manifestSha256(),command.uploadDeadline(),"过期的审批版本")));
        conflict(()->approve(new PreparedSupplement.Command(UUID.randomUUID().toString(),1,command.expectedAttemptRevision(),command.manifestSha256(),f.ends.q.now.get().plusSeconds(7200).toString(),"过期的尝试版本")));
        var s=status();var barrier=new CountDownLatch(1);var results=new ArrayList<Future<Object>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            for(int n=0;n<8;n++){var cmd=new PreparedSupplement.Command(UUID.randomUUID().toString(),s.approvalRevision(),s.attemptRevision(),s.manifestSha256(),f.ends.q.now.get().plusSeconds(7200).toString(),"并发延期");results.add(pool.submit(()->{barrier.await();try{return approve(cmd);}catch(BusinessException e){return e.getCode();}}));}
            barrier.countDown();var outcomes=new ArrayList<Object>();for(var result:results)outcomes.add(result.get(15,TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(v->v instanceof PreparedSupplement.Receipt).count()).isEqualTo(1);assertThat(outcomes.stream().filter(v->Integer.valueOf(409).equals(v)).count()).isEqualTo(7);
        }assertThat(f.ends.count("prepared_supplement_grant")).isEqualTo(2);
    }
    @Test void expirationDuringPrivateIOKeepsReservationButCannotConfirmBytes()throws Exception {
        expired();var receipt=approve(command());
        doAnswer(i->{f.ends.q.now.set(Instant.parse(receipt.uploadDeadline()));var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerChunk.class);return new PreparedPrivateOssStore.StoredChunk(scope,scope.key("prepared-fixture"));}).when(f.store).putAnswerChunk(any(),any());
        conflict(()->f.upload(f.command(),f.bytes));assertThat(f.uploads.receipts(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),answer)).isEmpty();assertThat(f.ends.count("prepared_answer_chunk")).isEqualTo(1);
        approve(command());assertThat(f.upload(f.command(),f.bytes).confirmed()).isTrue();assertThat(f.ends.count("prepared_answer_chunk")).isEqualTo(1);
    }
    @Test void authorizationIsRecheckedForCreationReplayReadAndStudentBackfill()throws Exception {
        expired();var cmd=command();var receipt=approve(cmd);var q=f.ends.q;var jdbc=q.f.jdbc;
        jdbc.update("UPDATE `user` SET type=0 WHERE id=?",q.f.actor);
        try{assertThatThrownBy(this::status).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);assertThatThrownBy(()->approve(cmd)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}finally{jdbc.update("UPDATE `user` SET type=1 WHERE id=?",q.f.actor);}
        jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",q.f.assessment,q.student);
        try{assertThatThrownBy(()->f.upload(f.command(),f.bytes)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);assertThatThrownBy(()->approve(cmd)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}finally{jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",q.f.assessment,q.student);}
        assertThat(approve(cmd)).isEqualTo(receipt);verifyNoInteractions(f.store);
    }
    @Test void deadlineMustActuallyExtendAndUncertainCaptureCannotBecomeACompleteAnswer()throws Exception {
        f.finishAll();assertThat(status().mayApprove()).isFalse();var before=command();conflict(()->approve(before));
        f.ends.q.now.set(Instant.parse(f.ends.attempt().supplementalUploadDeadline()));var c=command();
        conflict(()->approve(new PreparedSupplement.Command(c.idempotencyKey(),0,c.expectedAttemptRevision(),c.manifestSha256(),f.ends.q.now.get().toString(),c.reason())));
        var approved=approve(c);var next=command();conflict(()->approve(new PreparedSupplement.Command(next.idempotencyKey(),next.expectedRevision(),next.expectedAttemptRevision(),next.manifestSha256(),approved.uploadDeadline(),next.reason())));
        assertThat(f.ends.count("prepared_submission")).isZero();assertThat(f.ends.count("prepared_answer_media")).isZero();
    }
    @Test void tamperedGrantProofCannotReopenUploadsOrReturnAnApprovalReceipt()throws Exception {
        expired();var cmd=command();var receipt=approve(cmd);var jdbc=f.ends.q.f.jdbc;
        var original=jdbc.queryForMap("SELECT request_sha256,manifest_sha256,upload_deadline FROM prepared_supplement_grant WHERE id=?",receipt.id());
        for(String sql:List.of("UPDATE prepared_supplement_grant SET request_sha256=REPEAT('a',64) WHERE id=?","UPDATE prepared_supplement_grant SET manifest_sha256=REPEAT('b',64) WHERE id=?","UPDATE prepared_supplement_grant SET upload_deadline=DATE_ADD(upload_deadline,INTERVAL 1 DAY) WHERE id=?")){
            jdbc.update(sql,receipt.id());
            try{assertThatThrownBy(this::status).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->approve(cmd)).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->f.upload(f.command(),f.bytes)).isInstanceOf(IllegalStateException.class);}
            finally{jdbc.update("UPDATE prepared_supplement_grant SET request_sha256=?,manifest_sha256=?,upload_deadline=? WHERE id=?",original.get("request_sha256"),original.get("manifest_sha256"),original.get("upload_deadline"),receipt.id());}
        }
        assertThat(status().phase()).isEqualTo("APPROVED");assertThat(f.ends.count("prepared_answer_chunk")).isZero();
    }
    @Test void uncertainCaptureMissingOriginalScopeAndUnclassifiedReviewCannotBeApproved()throws Exception {
        var q=f.ends.q;var jdbc=q.f.jdbc;
        assertThatThrownBy(()->service.status(q.f.actor,q.student,q.f.assessment,q.attempt.id(),UUID.randomUUID().toString())).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThatThrownBy(()->service.status(q.student,q.student,q.f.assessment,q.attempt.id(),answer)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThatThrownBy(()->service.status(0,q.student,q.f.assessment,q.attempt.id(),answer)).isInstanceOf(IllegalArgumentException.class);
        expired();var c=command();conflict(()->approve(new PreparedSupplement.Command(c.idempotencyKey(),c.expectedRevision(),c.expectedAttemptRevision(),"b".repeat(64),c.uploadDeadline(),c.reason())));
        jdbc.update("UPDATE prepared_attempt SET requires_supplement_review=0 WHERE id=?",q.attempt.id());
        try{assertThat(status().mayApprove()).isFalse();conflict(()->approve(c));}finally{jdbc.update("UPDATE prepared_attempt SET requires_supplement_review=1 WHERE id=?",q.attempt.id());}
        var approved=approve(c);var second=jdbc.queryForObject("SELECT id FROM prepared_answer WHERE attempt_id=? AND ordinal=2",String.class,q.attempt.id());
        assertThatThrownBy(()->service.approve(q.f.actor,q.student,q.f.assessment,q.attempt.id(),second,c)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(approve(c)).isEqualTo(approved);
        jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",q.f.assessment);try{assertThatThrownBy(this::status).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}finally{jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",q.f.assessment);}
        assertThatThrownBy(()->approve(null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingEndingsAndInconsistentOriginalWindowAreNotAnApprovalShortcut()throws Exception {
        expired();var q=f.ends.q;var jdbc=q.f.jdbc;q.now.set(q.now.get().plusSeconds(2));
        for(String removed:List.of(answer,jdbc.queryForObject("SELECT id FROM prepared_answer WHERE attempt_id=? AND ordinal=2",String.class,q.attempt.id())))
            q.f.tx.executeWithoutResult(s->{jdbc.update("DELETE FROM prepared_answer_end_receipt WHERE answer_id=?",removed);jdbc.update("DELETE FROM prepared_answer_end WHERE answer_id=?",removed);var view=status();assertThat(view.mayApprove()).isFalse();assertThat(view.eligibilityIssue()).isNotBlank();s.setRollbackOnly();});
        q.f.tx.executeWithoutResult(s->{jdbc.update("UPDATE prepared_attempt SET supplemental_upload_deadline=DATE_ADD(supplemental_upload_deadline,INTERVAL 1 SECOND) WHERE id=?",q.attempt.id());assertThatThrownBy(this::status).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
        q.f.tx.executeWithoutResult(s->{jdbc.update("UPDATE prepared_attempt SET answering_ended_at=DATE_ADD(answering_ended_at,INTERVAL 1 SECOND),supplemental_upload_deadline=DATE_ADD(supplemental_upload_deadline,INTERVAL 1 SECOND) WHERE id=?",q.attempt.id());assertThatThrownBy(this::status).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
        assertThat(f.ends.count("prepared_supplement_grant")).isZero();assertThat(f.ends.count("prepared_answer_end")).isEqualTo(3);
    }
}
