package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

/** Native owned database corruption guard, not a real authorized retake or model integration. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedReviewSummaryStateGuardLiveTest {
    final PreparedReviewSummaryLiveTest f=new PreparedReviewSummaryLiveTest();
    @TempDir Path folder;

    @BeforeEach void setup()throws Exception {f.folder=folder;f.setup();}
    @AfterEach void cleanup(){f.cleanup();}

    @Test void unsubmittedOriginalCannotInventCompletionFromAFabricatedTimestamp(){
        f.historical(); // Synthetic new anchor, deliberately not retake acceptance.
        var before=f.summaries.summary(f.f.actor,f.f.assessment,f.newer);
        assertThat(before.state()).isEqualTo("IN_PROGRESS");assertThat(before.completedAt()).isNull();
        assertThat(before.totalScore()).isNull();assertThat(before.answers()).isEmpty();
        int jobs=f.f.count("prepared_job"),audit=f.f.count("prepared_audit");
        var tx=f.f.f.m.f.f.ends.q.f.tx;
        tx.executeWithoutResult(status->{
            assertThat(f.f.jdbc.update("UPDATE prepared_attempt SET first_results_completed_at=CURRENT_TIMESTAMP(3) WHERE id=? AND assessment_id=? AND student_id=?",
                    f.newer,f.f.assessment,f.f.student)).isEqualTo(1);
            assertThatThrownBy(()->f.summaries.summary(f.f.actor,f.f.assessment,f.newer))
                    .isInstanceOf(IllegalStateException.class).hasMessage("原尝试与结果状态无法核验");
            status.setRollbackOnly();
        });
        assertThat(f.summaries.summary(f.f.actor,f.f.assessment,f.newer)).isEqualTo(before);
        assertThat(f.f.count("prepared_job")).isEqualTo(jobs);assertThat(f.f.count("prepared_audit")).isEqualTo(audit);
    }
}
