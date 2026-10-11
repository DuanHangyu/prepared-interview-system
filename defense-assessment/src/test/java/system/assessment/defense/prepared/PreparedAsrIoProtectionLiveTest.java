package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import system.assessment.defense.prepared.retention.*;
import java.nio.file.Path;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NEW MySQL/ledger; ASR, storage and source audio remain explicit fixtures. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAsrIoProtectionLiveTest {
    final PreparedAsrWorkerLiveTest a=new PreparedAsrWorkerLiveTest();@TempDir Path folder;
    @BeforeEach void setup()throws Exception{a.folder=folder;a.setup();}
    @AfterEach void cleanup(){a.cleanup();}
    PreparedAudioRetentionPolicy.Decision inspect(){var p=a.f;return new PreparedRetentionEvidenceService(p.f.service,p.service,p.jdbc,p.f.m.f.f.ends.q.f.tx.getTransactionManager()).inspect(p.student,p.assessment,p.attempt);}
    @Test void privatePreparationAndSupplierDispatchAreProtectedAndCapabilityIsPersistedBeforePaidPost()throws Exception{
        var original=a.f.f.status();
        doAnswer(i->{
            assertThat(inspect().protections()).contains(PreparedAudioRetentionPolicy.Protection.ACTIVE_AUDIO_IO);
            a.submitted=i.getArgument(0,PreparedPrivateOssStore.StoredAnswer.class);
            a.inputUrl="https://prepared-interview-fixture.oss-cn-beijing.aliyuncs.com/"+a.submitted.objectKey()+"?Signature=explicit-fixture";
            return new PreparedPrivateOssStore.Preview(a.inputUrl,Instant.now().plusSeconds(86400).toString());
        }).when(a.store).asrInput(any());
        when(a.client.submit(any(),any(),any(),anyString())).thenAnswer(i->{
            assertThat(inspect().protections()).contains(PreparedAudioRetentionPolicy.Protection.ACTIVE_AUDIO_IO,PreparedAudioRetentionPolicy.Protection.ACTIVE_READ_AUTHORIZATION);
            var row=a.f.jdbc.queryForMap("SELECT * FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_ASR_INPUT_AUTHORIZED'",a.f.assessment);
            assertThat(row.get("resource_id")).isEqualTo(a.submitted.scope().answerId());assertThat(row.get("resource_sha256")).isEqualTo(a.submitted.proof().sha256());
            assertThat(row.toString()).doesNotContain("Signature","explicit-fixture","url");return a.task;
        });
        a.worker().runOnce();assertThat(inspect().protections()).doesNotContain(PreparedAudioRetentionPolicy.Protection.ACTIVE_AUDIO_IO);
        assertThat(inspect().protections()).contains(PreparedAudioRetentionPolicy.Protection.ACTIVE_READ_AUTHORIZATION);
        assertThat(a.f.f.status()).isEqualTo(original);verify(a.client,times(1)).submit(any(),any(),any(),anyString());
    }
    @Test void storageFailureEndsIoWithoutCapabilityCostOrDispatch()throws Exception{
        doThrow(new java.io.IOException("explicit SDK failure")).when(a.store).asrInput(any());a.worker().runOnce();
        assertThat(inspect().protections()).doesNotContain(PreparedAudioRetentionPolicy.Protection.ACTIVE_AUDIO_IO,PreparedAudioRetentionPolicy.Protection.ACTIVE_READ_AUTHORIZATION);
        verifyNoInteractions(a.client);assertThat(a.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_budget WHERE scope_id=?",Integer.class,a.scope)).isZero();
    }
    @Test void unboundedSupplierInputUrlNeverReachesAuthorizationOrPaidSubmit()throws Exception{
        var now=Instant.now();var fixed=Clock.fixed(now,ZoneOffset.UTC);
        doAnswer(i->{var media=i.getArgument(0,PreparedPrivateOssStore.StoredAnswer.class);return new PreparedPrivateOssStore.Preview("https://prepared-interview-fixture.oss-cn-beijing.aliyuncs.com/"+media.objectKey()+"?Signature=explicit-fixture",now.plusSeconds(86401).toString());}).when(a.store).asrInput(any());
        new system.assessment.defense.prepared.processing.PreparedAsrWorker(a.f.f.m.jobs,a.f.service,a.policy,a.client,a.store,a.storage,fixed).runOnce();
        verifyNoInteractions(a.client);assertThat(inspect().protections()).doesNotContain(PreparedAudioRetentionPolicy.Protection.ACTIVE_AUDIO_IO,PreparedAudioRetentionPolicy.Protection.ACTIVE_READ_AUTHORIZATION);
    }
}
