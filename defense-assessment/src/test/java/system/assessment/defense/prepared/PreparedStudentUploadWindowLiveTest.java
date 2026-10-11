package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real original grants and MySQL authorization; no cloud calls or recording mutations. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedStudentUploadWindowLiveTest {
    final PreparedSupplementLiveTest f=new PreparedSupplementLiveTest();
    @BeforeEach void setup()throws Exception {f.setup();}
    @AfterEach void cleanup(){f.cleanup();}
    PreparedUploadWindow.View window(){var q=f.f.ends.q;return f.f.uploads.window(q.student,q.f.assessment,q.attempt.id(),f.answer,q.client);}
    @Test void queriesNeverStartExtendOrExpireTheOriginalWindow()throws Exception {
        var initial=window();assertThat(initial.phase()).isEqualTo("ANSWERING");assertThat(initial.mayUploadOriginal()).isTrue();assertThat(initial.automaticDeadline()).isNull();
        f.f.finishAll();var original=f.f.ends.attempt();var before=window();assertThat(before.phase()).isEqualTo("AUTOMATIC_WINDOW");assertThat(before.uploadDeadline()).isEqualTo(original.supplementalUploadDeadline());
        f.f.ends.q.now.set(Instant.parse(original.supplementalUploadDeadline()));var expired=window();assertThat(expired.phase()).isEqualTo("AWAITING_APPROVAL");assertThat(expired.mayUploadOriginal()).isFalse();
        for(int n=0;n<10;n++)assertThat(window()).isEqualTo(expired);
        assertThat(f.f.ends.attempt()).isEqualTo(original);assertThat(f.f.ends.count("prepared_supplement_grant")).isZero();assertThat(f.f.ends.count("prepared_answer_chunk")).isZero();verifyNoInteractions(f.f.store);
    }
    @Test void grantIsOnlyForItsOriginalAnswerAndDeadlineDoesNotPermitNewRecording()throws Exception {
        f.expired();var original=f.f.ends.attempt();var receipt=f.approve(f.command());var approved=window();
        assertThat(approved.phase()).isEqualTo("APPROVED");assertThat(approved.mayUploadOriginal()).isTrue();assertThat(approved.approvalRevision()).isEqualTo(1);
        assertThat(approved.manifestSha256()).isEqualTo(receipt.manifestSha256());assertThat(approved.answerDeadline()).isEqualTo(receipt.answerDeadline());assertThat(approved.uploadDeadline()).isEqualTo(receipt.uploadDeadline());
        var q=f.f.ends.q;String other=q.f.jdbc.queryForObject("SELECT id FROM prepared_answer WHERE attempt_id=? AND ordinal=2",String.class,q.attempt.id());
        assertThat(f.f.uploads.window(q.student,q.f.assessment,q.attempt.id(),other,q.client).mayUploadOriginal()).isFalse();
        assertThat(f.f.ends.attempt().answeringEndedAt()).isEqualTo(original.answeringEndedAt());assertThat(f.f.ends.attempt().supplementalUploadDeadline()).isEqualTo(original.supplementalUploadDeadline());
        assertThat(q.grants.current(q.student,q.f.assessment,q.attempt.id(),q.client).orElseThrow().mayRecord()).isFalse();
        q.now.set(Instant.parse(receipt.uploadDeadline()));assertThat(window().phase()).isEqualTo("EXPIRED");assertThat(window().mayUploadOriginal()).isFalse();
        var renewed=f.approve(f.command());assertThat(window().approvalRevision()).isEqualTo(2);assertThat(window().uploadDeadline()).isEqualTo(renewed.uploadDeadline());
    }
    @Test void readUsesCurrentStudentAssignmentAndOriginalClientNeverHistoricalPermission()throws Exception {
        f.expired();f.approve(f.command());var q=f.f.ends.q;
        assertThatThrownBy(()->f.f.uploads.window(q.student,q.f.assessment,q.attempt.id(),f.answer,UUID.randomUUID().toString())).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThatThrownBy(()->f.f.uploads.window(q.f.actor,q.f.assessment,q.attempt.id(),f.answer,q.client)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThatThrownBy(()->f.f.uploads.window(q.student,q.f.assessment,UUID.randomUUID().toString(),f.answer,q.client)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThatThrownBy(()->f.f.uploads.window(q.student,q.f.assessment,q.attempt.id(),f.answer,"invalid")).isInstanceOf(IllegalArgumentException.class);
        q.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",q.f.assessment,q.student);
        try{assertThatThrownBy(this::window).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}finally{q.f.jdbc.update("INSERT INTO assessment_student_relation(assessment_id,student_id,relation_type) VALUES (?,?,0)",q.f.assessment,q.student);}
        q.f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",q.f.assessment);try{assertThatThrownBy(this::window).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}finally{q.f.jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",q.f.assessment);}
    }
    @Test void studentProjectionDoesNotLeakTeacherReasonIdentityOrPrivateStorage()throws Exception {
        f.expired();f.approve(f.command());var q=f.f.ends.q;String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(window());
        assertThat(json).doesNotContain("reason","actorId","idempotencyKey","objectKey","rubric","captureClientId",q.client);
        assertThat(window().studentId()).isEqualTo(q.student);assertThat(window().assessmentId()).isEqualTo(q.f.assessment);assertThat(window().answerId()).isEqualTo(f.answer);assertThat(window().paperVersionId()).isEqualTo(q.attempt.paperVersionId());
    }
    @Test void corruptedProofAndUnclassifiedReviewCannotBeAnApprovalShortcut()throws Exception {
        f.expired();var receipt=f.approve(f.command());var q=f.f.ends.q;
        q.f.tx.executeWithoutResult(s->{q.f.jdbc.update("UPDATE prepared_supplement_grant SET request_sha256=REPEAT('a',64) WHERE id=?",receipt.id());assertThatThrownBy(this::window).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
        q.f.jdbc.update("UPDATE prepared_attempt SET requires_supplement_review=0 WHERE id=?",q.attempt.id());try{assertThat(window().mayUploadOriginal()).isFalse();assertThat(window().phase()).isEqualTo("NOT_ELIGIBLE");}finally{q.f.jdbc.update("UPDATE prepared_attempt SET requires_supplement_review=1 WHERE id=?",q.attempt.id());}
        q.f.tx.executeWithoutResult(s->{q.f.jdbc.update("UPDATE prepared_attempt SET supplemental_upload_deadline=DATE_ADD(supplemental_upload_deadline,INTERVAL 1 SECOND) WHERE id=?",q.attempt.id());assertThatThrownBy(this::window).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
    }
    @Test void onlyActualDeadlineRejectionHasTheRecoverableReason()throws Exception {
        f.expired();assertThatThrownBy(()->f.f.upload(f.f.command(),f.f.bytes)).isInstanceOf(PreparedUploadWindow.Expired.class);
        var q=f.f.ends.q;var cmd=f.f.command();var wrong=new PreparedAnswerUpload.Command(UUID.randomUUID().toString(),cmd.segmentId(),cmd.segmentOrdinal(),cmd.mimeType(),cmd.index(),cmd.byteSize(),cmd.sha256());
        assertThatThrownBy(()->f.f.upload(wrong,f.f.bytes)).isInstanceOf(BusinessException.class).isNotInstanceOf(PreparedUploadWindow.Expired.class);
    }
}
