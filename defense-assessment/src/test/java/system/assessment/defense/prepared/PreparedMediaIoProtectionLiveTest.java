package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual NEW MySQL and local decoding; private byte transfers are SDK doubles. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedMediaIoProtectionLiveTest {
    final PreparedAnswerMediaLiveTest m=new PreparedAnswerMediaLiveTest();@TempDir Path folder;
    @BeforeEach void setup()throws Exception{m.folder=folder;m.setup();}
    @AfterEach void cleanup(){m.cleanup();}
    @Test void originalMediaDownloadDecodeAndSaveAreRegisteredUntilWorkerIoReallyStops()throws Exception{
        var hash=m.nativeEnd();m.stagedCloudDouble();m.request(hash);
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(m.f.f.ends.q.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_ASSEMBLY_IO_OPENED'",Integer.class,m.f.f.ends.q.f.assessment)).isEqualTo(1);
            Files.write(i.getArgument(1,Path.class),m.f.f.bytes,StandardOpenOption.CREATE_NEW);return null;
        }).when(m.f.f.store).downloadAnswerBundle(any(),any());
        m.worker().runOnce();assertThat(m.status().serverSaved()).isTrue();
        assertThat(m.f.f.ends.q.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_ASSEMBLY_IO_CLOSED'",Integer.class,m.f.f.ends.q.f.assessment)).isEqualTo(1);
    }
}
