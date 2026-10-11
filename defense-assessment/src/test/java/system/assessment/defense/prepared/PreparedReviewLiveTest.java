package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.prepared.processing.*;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;

/** Real independent DB/native original receipt; model and cloud contents are explicit doubles. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedReviewLiveTest {
    final PreparedProcessingLiveTest f=new PreparedProcessingLiveTest();@TempDir Path folder;PreparedReviewService review;
    @BeforeEach void setup()throws Exception{f.folder=folder;f.setup();review=new PreparedReviewService(f.service,f.jdbc,f.f.m.f.f.ends.q.f.tx.getTransactionManager());}
    @AfterEach void cleanup(){f.cleanup();}
    @Test void teacherFindsActualOriginalSubmissionAndQueuedProcessingWithoutDefaultZero(){
        var rows=review.roster(f.actor,f.assessment,0,20);assertThat(rows.actorId()).isEqualTo(f.actor);assertThat(rows.rows()).hasSize(1);assertThat(rows.rows().getFirst().studentId()).isEqualTo(f.student);
        var interview=review.interview(f.actor,f.assessment,f.attempt);assertThat(interview.submitted()).isTrue();assertThat(interview.answers()).hasSize(3);
        var answer=review.answer(f.actor,f.assessment,f.attempt,interview.answers().getFirst().answerId(),null,10);
        assertThat(answer.score()).isNull();assertThat(answer.transcript()).isNull();assertThat(answer.history()).isEmpty();assertThat(answer.revision()).isZero();assertThat(answer.question().rubric()).startsWith("不应泄漏的教师标准");
        assertThat(interview.answers().getFirst().transcription().state()).isEqualTo("QUEUED");assertThat(f.count("prepared_processing_head")).isZero();
    }
    @Test void realResultReviewRegradeAndConfirmReuseAuthoritativeVersionsReasonsAndReceipts(){
        var transcript=f.transcribe(f.claim(Kind.TRANSCRIBE));var initial=f.score(f.claim(Kind.SCORE),"8.5");
        var detail=review.answer(f.actor,f.assessment,f.attempt,transcript.answerId(),null,10);assertThat(detail.score().score().toPlainString()).isEqualTo("8.5");assertThat(detail.transcript().sentences()).hasSize(1);
        var command=new PreparedProcessing.ConfirmCommand(UUID.randomUUID().toString(),detail.revision(),initial.id(),"教师核验原回答");var receipt=review.confirm(f.actor,f.assessment,f.attempt,transcript.answerId(),command);
        assertThat(review.confirm(f.actor,f.assessment,f.attempt,transcript.answerId(),command)).isEqualTo(receipt);assertThat(review.answer(f.actor,f.assessment,f.attempt,transcript.answerId(),null,10).confirmed()).isTrue();
        var request=new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),receipt.revision(),transcript.id(),"核对冻结标准");var queued=review.regrade(f.actor,f.assessment,f.attempt,transcript.answerId(),request);
        assertThat(review.regrade(f.actor,f.assessment,f.attempt,transcript.answerId(),request)).isEqualTo(queued);assertThat(review.interview(f.actor,f.assessment,f.attempt).answers().stream().filter(a->a.answerId().equals(transcript.answerId())).findFirst().orElseThrow().scoring().state()).isEqualTo("QUEUED");
        assertThat(review.answer(f.actor,f.assessment,f.attempt,transcript.answerId(),null,10).score().score()).isEqualByComparingTo("8.5");assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void boundedHistoryPaginationKeepsEffectivePointersAndRejectsOtherAnswerCursor(){
        var transcript=f.transcribe(f.claim(Kind.TRANSCRIBE));f.score(f.claim(Kind.SCORE),"6");
        for(int n=0;n<3;n++){var v=f.view(transcript.answerId());f.service.regrade(f.actor,f.assessment,f.attempt,transcript.answerId(),new PreparedProcessing.RegradeCommand(UUID.randomUUID().toString(),v.revision(),transcript.id(),"独立边界合成重评"));f.score(f.claim(Kind.SCORE),"7");}
        var first=review.answer(f.actor,f.assessment,f.attempt,transcript.answerId(),null,2);assertThat(first.history()).hasSize(2);assertThat(first.nextResultId()).isNotNull();
        var second=review.answer(f.actor,f.assessment,f.attempt,transcript.answerId(),first.nextResultId(),2);assertThat(second.history()).hasSize(2);assertThat(first.history()).doesNotContainAnyElementsOf(second.history());
        assertThat(second.scoreId()).isEqualTo(first.scoreId());assertThat(second.transcriptId()).isEqualTo(transcript.id());
        var other=f.transcribe(f.claim(Kind.TRANSCRIBE));assertThatThrownBy(()->review.answer(f.actor,f.assessment,f.attempt,transcript.answerId(),other.id(),2)).isInstanceOf(system.assessment.defense.infrastructure.exception.BusinessException.class);
    }
    @Test void studentsUnrelatedTeachersBadIdentifiersAndArchiveCannotReadTeacherData(){
        assertThatThrownBy(()->review.roster(f.student,f.assessment,0,20)).isInstanceOf(system.assessment.defense.infrastructure.exception.BusinessException.class);
        assertThatThrownBy(()->review.interview(f.actor,f.assessment,UUID.randomUUID().toString())).isInstanceOf(system.assessment.defense.infrastructure.exception.BusinessException.class);
        for(int size:List.of(0,51))assertThatThrownBy(()->review.roster(f.actor,f.assessment,0,size)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->review.roster(f.actor,f.assessment,-1,20)).isInstanceOf(IllegalArgumentException.class);
        f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",f.assessment);try{assertThatThrownBy(()->review.roster(f.actor,f.assessment,0,20)).isInstanceOf(system.assessment.defense.infrastructure.exception.BusinessException.class);}finally{f.jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",f.assessment);}
    }
    @Test void rosterKeysetsIncludeNotStartedStudentsWithoutInventingPreparedAttempts(){
        for(int n=0;n<2;n++){String account="prepared-review-roster-"+UUID.randomUUID();f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('尚未开始联调',?,'not-a-login-credential',0)",account);
            int student=f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);f.extraUsers.add(student);
            f.jdbc.update("INSERT INTO student_assessment_record(assessment_id,student_id,state,re_analysis) VALUES (?,?,0,0)",f.assessment,student);}
        var first=review.roster(f.actor,f.assessment,0,1);assertThat(first.nextRecordId()).isNotNull();assertThat(first.rows()).hasSize(1);
        var second=review.roster(f.actor,f.assessment,first.nextRecordId(),1);assertThat(second.rows().getFirst().state()).isEqualTo("NO_PREPARED_ATTEMPT");assertThat(second.rows().getFirst().attemptId()).isNull();
        var third=review.roster(f.actor,f.assessment,second.nextRecordId(),1);assertThat(third.nextRecordId()).isNull();
        assertThat(review.roster(f.actor,f.assessment,third.rows().getFirst().recordId(),1).rows()).isEmpty();
    }
    @Test void unsubmittedOrDamagedSubmittedStateCannotClaimReliableOriginalAndHistoryBoundsFailClosed(){
        var tx=f.f.m.f.f.ends.q.f.tx;String answer=f.jdbc.queryForObject("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal LIMIT 1",String.class,f.assessment);
        for(int limit:List.of(0,21))assertThatThrownBy(()->review.answer(f.actor,f.assessment,f.attempt,answer,null,limit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->review.answer(f.actor,f.assessment,f.attempt,answer,"bad",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->review.answer(f.actor,f.assessment,f.attempt,answer,UUID.randomUUID().toString(),1)).isInstanceOf(system.assessment.defense.infrastructure.exception.BusinessException.class);
        tx.executeWithoutResult(s->{f.jdbc.update("UPDATE prepared_attempt SET state='UPLOAD_PENDING' WHERE id=?",f.attempt);assertThatThrownBy(()->review.interview(f.actor,f.assessment,f.attempt)).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});
        tx.executeWithoutResult(s->{f.jdbc.update("DELETE FROM prepared_submission_command WHERE assessment_id=?",f.assessment);f.jdbc.update("DELETE FROM prepared_submission_answer WHERE assessment_id=?",f.assessment);f.jdbc.update("DELETE FROM prepared_submission WHERE assessment_id=?",f.assessment);
            assertThatThrownBy(()->review.interview(f.actor,f.assessment,f.attempt)).isInstanceOf(IllegalStateException.class);
            f.jdbc.update("UPDATE prepared_attempt SET state='UPLOAD_PENDING' WHERE id=?",f.attempt);var view=review.interview(f.actor,f.assessment,f.attempt);assertThat(view.submitted()).isFalse();assertThat(view.submittedAt()).isNull();assertThat(view.answers().getFirst().transcription().state()).isEqualTo("NOT_QUEUED");s.setRollbackOnly();});
        assertThatThrownBy(()->review.roster(0,f.assessment,0,1)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->review.interview(f.actor,f.assessment,"bad")).isInstanceOf(IllegalArgumentException.class);
    }
}
