package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.prepared.attempt.*;
import java.nio.file.Path;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Own native database and real local codec receipts. The spy counts real media
 * validation, never supplies proof or a receipt. Storage remains the labelled SDK double. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedSubmissionProofReuseLiveTest {
    final PreparedSubmissionLiveTest f=new PreparedSubmissionLiveTest();
    @TempDir Path folder;
    PreparedAnswerMediaService observed;
    PreparedSubmission.Summary original;

    @BeforeEach void setup()throws Exception {
        f.folder=folder;f.setup();f.savedAll();original=f.submit(f.command());
        var q=f.m.f.f.ends.q;var database=q.f;
        observed=spy(f.m.service);
        f.service=new PreparedSubmissionService(database.access,new PreparedStudentEligibilityRepository(database.jdbc),
            new PreparedAttemptStartRepository(database.jdbc),new PreparedQuestionGrantRepository(database.jdbc),
            new PreparedAnswerEndRepository(database.jdbc),observed,f.m.jobs,database.jdbc,database.tx.getTransactionManager(),
            new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return q.now.get();}});
    }
    @AfterEach void cleanup(){f.cleanup();}

    @RepeatedTest(3)
    void eachReceiptReadVerifiesEveryOriginalMediaOnceAndNextRequestVerifiesAgain(){
        var q=f.m.f.f.ends.q;
        var answers=q.f.jdbc.queryForList("SELECT id FROM prepared_answer WHERE assessment_id=? AND student_id=? AND attempt_id=? ORDER BY ordinal",String.class,q.f.assessment,q.student,q.attempt.id());
        assertThat(answers).hasSize(3);
        for(int request=0;request<2;request++){
            clearInvocations(observed);
            assertThat(f.status()).isEqualTo(original);
            for(String answer:answers)verify(observed,times(1)).status(q.student,q.f.assessment,q.attempt.id(),answer);
        }
        assertThat(f.m.f.f.ends.count("prepared_submission")).isEqualTo(1);
        assertThat(q.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=? AND kind IN ('TRANSCRIBE','SCORE')",Integer.class,q.f.assessment)).isEqualTo(6);
    }

    @RepeatedTest(3)
    void priorSuccessfulReadNeverMasksDamagedProofOrRevokedIdentityOnNextRequest(){
        var q=f.m.f.f.ends.q;var jdbc=q.f.jdbc;String answer=f.m.f.f.grant.answerId();
        assertThat(f.status()).isEqualTo(original);
        q.f.tx.executeWithoutResult(tx->{
            jdbc.update("UPDATE prepared_answer_media SET proof_json='{}' WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=?",q.f.assessment,q.student,q.attempt.id(),answer);
            assertThatThrownBy(f::status).isInstanceOf(IllegalStateException.class);tx.setRollbackOnly();
        });
        assertThat(f.status()).isEqualTo(original);
        q.f.tx.executeWithoutResult(tx->{
            jdbc.update("UPDATE `user` SET type=1 WHERE id=?",q.student);
            assertThatThrownBy(f::status).isInstanceOf(system.assessment.defense.infrastructure.exception.BusinessException.class).extracting("code").isEqualTo(403);
            tx.setRollbackOnly();
        });
        assertThat(f.status()).isEqualTo(original);
        assertThat(f.m.f.f.ends.count("prepared_submission")).isEqualTo(1);
    }
}
