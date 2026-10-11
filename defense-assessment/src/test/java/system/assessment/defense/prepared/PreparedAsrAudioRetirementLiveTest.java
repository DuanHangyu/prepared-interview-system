package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.nio.file.Path;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual NEW DB/job ledger with explicitly doubled OSS/ASR. State transitions
 * are fixtures, not actual retirement or real provider/billing acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAsrAudioRetirementLiveTest {
    final PreparedAsrWorkerLiveTest a=new PreparedAsrWorkerLiveTest();@TempDir Path folder;
    @BeforeEach void setup()throws Exception {a.folder=folder;a.setup();}
    @AfterEach void cleanup(){a.f.jdbc.update("DELETE FROM prepared_retention_guard WHERE assessment_id=?",a.f.assessment);a.cleanup();}
    void mark(String state){a.f.jdbc.update("INSERT INTO prepared_retention_guard(attempt_id,assessment_id,student_id,audio_state,created_at,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",a.f.attempt,a.f.assessment,a.f.student,state);}
    void noFee(){assertThat(a.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_budget WHERE scope_id=?",Integer.class,a.scope)).isZero();
        assertThat(a.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_model_call c JOIN prepared_job j ON j.id=c.job_id WHERE j.assessment_id=?",Integer.class,a.f.assessment)).isZero();
        assertThat(a.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_async_model_task t JOIN prepared_job j ON j.id=t.job_id WHERE j.assessment_id=?",Integer.class,a.f.assessment)).isZero();assertThat(a.f.count("prepared_processing_result")).isZero();}
    @ParameterizedTest @ValueSource(strings={"RETIRING","RETIRED"})
    void exitedAudioNeverStartsStorageOrProviderIoOrReservesAnyFee(String state){
        var before=a.f.f.status();mark(state);a.worker().runOnce();verifyNoInteractions(a.store,a.client);noFee();assertThat(a.f.f.status()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"RETIRING","RETIRED"})
    void exitDuringPrivatePreparationStopsBeforeReserveDispatchOrPaidSubmit(String state)throws Exception {
        var before=a.f.f.status();doAnswer(i->{mark(state);var media=i.getArgument(0,PreparedPrivateOssStore.StoredAnswer.class);
            return new PreparedPrivateOssStore.Preview("https://prepared-interview-fixture.oss-cn-beijing.aliyuncs.com/"+media.objectKey()+"?Signature=not-dispatched",Instant.now().plusSeconds(86400).toString());}).when(a.store).asrInput(any());
        a.worker().runOnce();verify(a.store,times(1)).asrInput(any());verifyNoInteractions(a.client);noFee();assertThat(a.f.f.status()).isEqualTo(before);
    }
}
