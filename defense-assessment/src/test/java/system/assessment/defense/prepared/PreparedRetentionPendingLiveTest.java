package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.prepared.processing.PreparedProcessingService;
import system.assessment.defense.prepared.retention.PreparedRetentionEvidenceService;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.retention.PreparedAudioRetentionPolicy.*;

/** Real NEW database, recording/ended declarations without cloud receipts or a
 * submission. No model/cloud IO, retirement, physical-device or final QA claim. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedRetentionPendingLiveTest {
    final PreparedSubmissionLiveTest f=new PreparedSubmissionLiveTest();
    @TempDir Path folder;
    @BeforeEach void setup()throws Exception{f.folder=folder;f.setup();}
    @AfterEach void cleanup(){f.cleanup();}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unsubmittedRecordingAndEndedOriginalsAreAlwaysRetainedWithoutInventedReceipts(boolean ended)throws Exception{
        var q=f.m.f.f.ends.q;
        if(ended)for(int ordinal=1;ordinal<=3;ordinal++){
            if(ordinal>1)f.m.f.f.grant=q.grant(ordinal);
            f.m.f.f.segment=UUID.randomUUID().toString();f.m.end();
        }
        var jdbc=q.f.jdbc;var before=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("prepared_attempt","prepared_answer","prepared_submission","prepared_job","prepared_audit"))
            before.put(table,jdbc.queryForList("SELECT * FROM "+table+" WHERE assessment_id=?",q.f.assessment));
        var processing=new PreparedProcessingService(f.service,f.m.jobs,q.f.access,jdbc,q.f.tx.getTransactionManager());
        var retention=new PreparedRetentionEvidenceService(f.service,processing,jdbc,q.f.tx.getTransactionManager(),Clock.fixed(q.now.get().plusSeconds(400L*86400),ZoneOffset.UTC));
        var decision=retention.inspect(q.student,q.f.assessment,q.attempt.id());
        assertThat(decision.state()).isEqualTo(State.PROTECTED);assertThat(decision.audioDeleteCandidate()).isFalse();assertThat(decision.audioDueAt()).isNull();
        assertThat(decision.protections()).contains(Protection.NO_COMPLETION_ANCHOR,Protection.NOT_RELIABLY_SUBMITTED,Protection.RESULTS_INCOMPLETE,Protection.UNVERIFIED_BINDINGS);
        for(var row:before.entrySet())assertThat(jdbc.queryForList("SELECT * FROM "+row.getKey()+" WHERE assessment_id=?",q.f.assessment)).isEqualTo(row.getValue());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_submission WHERE assessment_id=?",Integer.class,q.f.assessment)).isZero();
    }
}
