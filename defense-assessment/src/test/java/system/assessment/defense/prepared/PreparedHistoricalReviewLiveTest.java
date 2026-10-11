package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.processing.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;

/** Independent DB/native original media, with explicit cloud/model doubles. Moving the
 * anchor is a scoped history fixture, NOT evidence that real retake authorization works. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedHistoricalReviewLiveTest {
    final PreparedProcessingLiveTest f=new PreparedProcessingLiveTest();
    @TempDir Path folder;PreparedReviewService review;String newer;
    @BeforeEach void setup()throws Exception{f.folder=folder;f.setup();review=new PreparedReviewService(f.service,f.jdbc,f.f.m.f.f.ends.q.f.tx.getTransactionManager());}
    @AfterEach void cleanup(){if(newer!=null){f.jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",f.attempt,f.assessment,f.student);f.jdbc.update("DELETE FROM prepared_attempt WHERE id=? AND assessment_id=? AND student_id=?",newer,f.assessment,f.student);}f.cleanup();}
    void newerAnchor(){
        newer=UUID.randomUUID().toString();
        f.jdbc.update("INSERT INTO prepared_attempt(id,record_id,assessment_id,student_id,attempt_no,paper_version_id,started_at) SELECT ?,record_id,assessment_id,student_id,attempt_no+1,paper_version_id,CURRENT_TIMESTAMP(3) FROM prepared_attempt WHERE id=?",newer,f.attempt);
        assertThat(f.jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",newer,f.assessment,f.student)).isEqualTo(1);
    }
    String firstAnswer(){return f.jdbc.queryForObject("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal LIMIT 1",String.class,f.assessment);}
    @Test void teacherCanReadOldOriginalAndManualHistoryAfterAnchorChangesWithoutRevivingAnyWriter(){
        String answer=firstAnswer();var media=f.service.reviewMedia(f.actor,f.assessment,f.attempt,answer);
        var original=f.f.status();var receipt=f.service.adjust(f.actor,f.assessment,f.attempt,answer,new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),0,new BigDecimal("8.75"),"历史原录音听审依据"));
        var lease=f.claim(Kind.TRANSCRIBE);String originalTranscript=PreparedProcessingCodec.encode(f.transcript(f.service.plan(lease)));int jobsBefore=f.count("prepared_job");newerAnchor();
        var interview=review.interview(f.actor,f.assessment,f.attempt);assertThat(interview.attemptNo()).isEqualTo(1);assertThat(interview.submitted()).isTrue();assertThat(interview.answers()).hasSize(3);
        var detail=review.answer(f.actor,f.assessment,f.attempt,answer,null,10);assertThat(detail.manualScore().score()).isEqualByComparingTo("8.75");assertThat(detail.revision()).isEqualTo(receipt.revision());
        assertThat(f.service.manualHistory(f.actor,f.assessment,f.attempt,answer,null,10).current().id()).isEqualTo(receipt.manualScoreId());
        assertThat(f.service.reviewMedia(f.actor,f.assessment,f.attempt,answer)).isEqualTo(media);
        assertThatThrownBy(()->f.f.status()).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.service.plan(lease)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.service.completeTranscript(lease,originalTranscript,f.asr)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.f.m.f.f.uploads.receipts(f.student,f.assessment,f.attempt,answer)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.service.adjust(f.actor,f.assessment,f.attempt,answer,new PreparedProcessing.AdjustCommand(UUID.randomUUID().toString(),receipt.revision(),BigDecimal.ONE,"不能改写旧尝试"))).isInstanceOf(BusinessException.class);
        assertThat(f.jdbc.queryForObject("SELECT id FROM prepared_submission WHERE attempt_id=?",String.class,f.attempt)).isEqualTo(original.receipt().id());
        assertThat(f.count("prepared_manual_score")).isEqualTo(1);assertThat(f.count("prepared_processing_result")).isZero();assertThat(f.count("prepared_job")).isEqualTo(jobsBefore);
    }
    @Test void historicalModelResultsAndConfirmationRemainOriginalButNoRegradeOrConfirmCanReviveOldAttempt(){
        var transcript=f.transcribe(f.claim(Kind.TRANSCRIBE));var model=f.score(f.claim(Kind.SCORE),"7.5");
        var confirm=f.service.confirm(f.actor,f.assessment,f.attempt,transcript.answerId(),new PreparedProcessing.ConfirmCommand(UUID.randomUUID().toString(),2,model.id(),"原教师确认"));newerAnchor();
        var detail=review.answer(f.actor,f.assessment,f.attempt,transcript.answerId(),null,1);assertThat(detail.score().score()).isEqualByComparingTo("7.5");assertThat(detail.confirmed()).isTrue();assertThat(detail.nextResultId()).isNotNull();
        assertThat(review.answer(f.actor,f.assessment,f.attempt,transcript.answerId(),detail.nextResultId(),1).transcriptId()).isEqualTo(transcript.id());
        assertThatThrownBy(()->review.regrade(f.actor,f.assessment,f.attempt,transcript.answerId(),new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),confirm.revision(),transcript.id(),"历史不可新建任务"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->review.confirm(f.actor,f.assessment,f.attempt,transcript.answerId(),new PreparedProcessing.ConfirmCommand(UUID.randomUUID().toString(),confirm.revision(),model.id(),"历史不可重新确认"))).isInstanceOf(BusinessException.class);
        assertThat(f.count("prepared_result_command")).isEqualTo(1);assertThat(f.count("prepared_processing_result")).isEqualTo(2);assertThat(f.count("prepared_score_request")).isZero();
    }
    @Test void historicalReadsUseCurrentTeacherPermissionNotCurrentStudentRoleAndNeverExposeOtherScope(){
        String answer=firstAnswer();newerAnchor();f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.student);
        try{assertThat(review.answer(f.actor,f.assessment,f.attempt,answer,null,10).question().ordinal()).isEqualTo(1);
            assertThatThrownBy(()->review.answer(f.student,f.assessment,f.attempt,answer,null,10)).isInstanceOf(BusinessException.class);
        }finally{f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.student);}
        f.f.m.f.f.ends.q.f.tx.executeWithoutResult(s->{f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",f.assessment,f.student);
            assertThat(review.answer(f.actor,f.assessment,f.attempt,answer,null,10).question().ordinal()).isEqualTo(1);s.setRollbackOnly();});
        f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.actor);
        try{assertThatThrownBy(()->review.answer(f.actor,f.assessment,f.attempt,answer,null,10)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->f.service.reviewMedia(f.actor,f.assessment,f.attempt,answer)).isInstanceOf(BusinessException.class);}
        finally{f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.actor);}
        assertThatThrownBy(()->review.answer(f.actor,f.assessment,newer,answer,null,10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->review.answer(f.actor,f.assessment,f.attempt,UUID.randomUUID().toString(),null,10)).isInstanceOf(BusinessException.class);
    }
    @Test void historicalSourceMustStillValidateOriginalSubmissionAndCanonicalMediaProof(){
        String answer=firstAnswer();newerAnchor();var tx=f.f.m.f.f.ends.q.f.tx;
        for(String sql:List.of("UPDATE prepared_submission SET snapshot_sha256=REPEAT('a',64) WHERE attempt_id=?","UPDATE prepared_answer_media SET proof_json='{}' WHERE attempt_id=?","UPDATE prepared_answer_end SET manifest_sha256=REPEAT('a',64) WHERE attempt_id=?"))
            tx.executeWithoutResult(s->{f.jdbc.update(sql,f.attempt);assertThatThrownBy(()->review.answer(f.actor,f.assessment,f.attempt,answer,null,10)).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->f.service.reviewMedia(f.actor,f.assessment,f.attempt,answer)).isInstanceOf(RuntimeException.class);s.setRollbackOnly();});
        assertThat(review.answer(f.actor,f.assessment,f.attempt,answer,null,10).score()).isNull();assertThat(f.count("prepared_submission")).isEqualTo(1);
    }
    @Test void scopedAttemptHistoryHasBoundedStableOrderAndCurrentIdentityWithoutCreatingAnyAttempt(){
        int record=f.jdbc.queryForObject("SELECT record_id FROM prepared_attempt WHERE id=?",Integer.class,f.attempt);int jobsBefore=f.count("prepared_job");newerAnchor();
        var first=review.attempts(f.actor,f.assessment,f.student,0,1);
        assertThat(first.actorId()).isEqualTo(f.actor);assertThat(first.assessmentId()).isEqualTo(f.assessment);assertThat(first.studentId()).isEqualTo(f.student);assertThat(first.recordId()).isEqualTo(record);
        assertThat(first.activeAttemptId()).isEqualTo(newer);assertThat(first.attempts()).hasSize(1);assertThat(first.attempts().getFirst().id()).isEqualTo(newer);assertThat(first.attempts().getFirst().current()).isTrue();assertThat(first.nextAttemptNo()).isEqualTo(2);
        var older=review.attempts(f.actor,f.assessment,f.student,first.nextAttemptNo(),1);assertThat(older.attempts()).hasSize(1);assertThat(older.attempts().getFirst().id()).isEqualTo(f.attempt);assertThat(older.attempts().getFirst().current()).isFalse();assertThat(older.attempts().getFirst().submittedAt()).isNotNull();assertThat(older.nextAttemptNo()).isNull();
        assertThat(review.interview(f.actor,f.assessment,f.attempt).current()).isFalse();assertThat(review.interview(f.actor,f.assessment,newer).current()).isTrue();assertThat(review.attempts(f.actor,f.assessment,f.student,1,1).attempts()).isEmpty();
        assertThat(f.count("prepared_attempt")).isEqualTo(2);assertThat(f.count("prepared_job")).isEqualTo(jobsBefore);assertThat(f.count("prepared_submission")).isEqualTo(1);
    }
    @Test void attemptHistoryValidatesActorOriginalRecordAndCursorEvenForAnEmptyOrDamagedAnchor(){
        assertThatThrownBy(()->review.attempts(0,f.assessment,f.student,0,10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->review.attempts(f.actor,0,f.student,0,10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->review.attempts(f.actor,f.assessment,0,0,10)).isInstanceOf(IllegalArgumentException.class);
        for(int size:List.of(0,21))assertThatThrownBy(()->review.attempts(f.actor,f.assessment,f.student,0,size)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->review.attempts(f.actor,f.assessment,f.student,-1,10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->review.attempts(f.student,f.assessment,f.student,0,10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->review.attempts(f.actor,f.assessment,f.student+100000,0,10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->review.attempts(f.actor,f.assessment,f.student,5,10)).isInstanceOf(BusinessException.class);
        var tx=f.f.m.f.f.ends.q.f.tx;tx.executeWithoutResult(s->{f.jdbc.update("DELETE FROM prepared_attempt_anchor WHERE assessment_id=? AND student_id=?",f.assessment,f.student);
            assertThatThrownBy(()->review.attempts(f.actor,f.assessment,f.student,0,10)).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
    }
    @Test void allocatedRecordWithNoPreparedHistoryIsAnActualEmptyStateNotAZeroOrAnAttempt(){
        String account="prepared-empty-history-"+UUID.randomUUID();f.jdbc.update("INSERT INTO `user`(account,password,type) VALUES (?,'not-a-login-credential',0)",account);
        int other=f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);f.extraUsers.add(other);
        f.jdbc.update("INSERT INTO student_assessment_record(assessment_id,student_id,state) VALUES (?,?,0)",f.assessment,other);
        var page=review.attempts(f.actor,f.assessment,other,0,10);assertThat(page.attempts()).isEmpty();assertThat(page.activeAttemptId()).isNull();assertThat(page.nextAttemptNo()).isNull();
        assertThat(page.studentId()).isEqualTo(other);assertThat(page.recordId()).isPositive();assertThat(f.count("prepared_attempt")).isEqualTo(1);
        assertThatThrownBy(()->review.attempts(f.actor,f.assessment,other,1,10)).isInstanceOf(BusinessException.class);
    }
    @Test void historicalAudioAuditsOriginalProofAndRechecksCurrentManagerAfterPrivateIo()throws Exception{
        String answer=firstAnswer();newerAnchor();var store=org.mockito.Mockito.mock(system.assessment.defense.prepared.storage.PreparedPrivateOssStore.class);
        var clock=java.time.Clock.fixed(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),java.time.ZoneOffset.UTC);
        var audio=new PreparedReviewAudioService(f.service,store,clock);
        org.mockito.Mockito.when(store.previewAnswer(org.mockito.ArgumentMatchers.any())).thenAnswer(i->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var media=i.getArgument(0,system.assessment.defense.prepared.storage.PreparedPrivateOssStore.StoredAnswer.class);return new system.assessment.defense.prepared.storage.PreparedPrivateOssStore.Preview("https://prepared-fixture.oss-cn-beijing.aliyuncs.com/"+media.objectKey()+"?Signature=explicit-historical-fixture",clock.instant().plusSeconds(300).toString());});
        var original=f.service.reviewMedia(f.actor,f.assessment,f.attempt,answer);var result=audio.preview(f.actor,f.assessment,f.attempt,answer);assertThat(result.attemptId()).isEqualTo(f.attempt);assertThat(result.sha256()).isEqualTo(original.proof().sha256());
        var audit=f.jdbc.queryForMap("SELECT * FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_AUDIO_AUTHORIZED'",f.assessment);assertThat(audit.get("resource_id")).isEqualTo(answer);assertThat(audit.get("resource_sha256")).isEqualTo(original.proof().sha256());assertThat(audit.toString()).doesNotContain("Signature","explicit-historical-fixture","url");
        org.mockito.Mockito.doAnswer(i->{f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.actor);return new system.assessment.defense.prepared.storage.PreparedPrivateOssStore.Preview("https://private.invalid/?Signature=not-delivered",clock.instant().plusSeconds(300).toString());}).when(store).previewAnswer(org.mockito.ArgumentMatchers.any());
        try{assertThatThrownBy(()->audio.preview(f.actor,f.assessment,f.attempt,answer)).isInstanceOf(BusinessException.class);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_AUDIO_AUTHORIZED'",Integer.class,f.assessment)).isEqualTo(1);}
        finally{f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.actor);}
        assertThat(f.count("prepared_processing_result")).isZero();assertThat(f.count("prepared_manual_score")).isZero();
    }
}
