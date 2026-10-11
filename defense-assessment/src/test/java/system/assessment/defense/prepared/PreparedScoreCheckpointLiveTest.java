package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import system.assessment.defense.prepared.jobs.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;

/** Actual dedicated MySQL fencing and atomic fee/response persistence, no model calls. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedScoreCheckpointLiveTest {
    final PreparedJobLedgerLiveTest f=new PreparedJobLedgerLiveTest();Lease lease;String call;
    @BeforeAll static void migrate(){PreparedJobLedgerLiveTest.migrateOnlyTheDedicatedDatabase();}
    @BeforeEach void setup(){f.fixtures();f.ledger.enqueue(f.actor,f.assessment,Kind.SCORE,1,UUID.randomUUID().toString(),"{}");lease=f.ledger.claim(Kind.SCORE,"score-fixture",300).orElseThrow();call=f.reserve(lease,"1");}
    @AfterEach void cleanup(){f.jdbc.update("DELETE FROM prepared_score_call WHERE job_id IN (SELECT id FROM prepared_job WHERE assessment_id=?)",f.assessment);f.cleanOnlyOwnedRows();}
    void dispatch(){assertThat(f.ledger.dispatchScore(lease,call,"{\"frozen\":true}")).isTrue();}
    void save(){f.ledger.saveScoreReply(lease,call,"{\"known\":true}",new BigDecimal("0.2"),"fixture-provider");}
    @Test void onePaidDispatchAndImmutableCheckpointRecoverAfterExpiredLease(){
        dispatch();assertThat(f.ledger.dispatchScore(lease,call,"{}")).isFalse();save();save();f.expire(lease);
        var next=f.ledger.claim(Kind.SCORE,"score-restart",300).orElseThrow();assertThat(next.job().attempts()).isEqualTo(1);assertThat(next.generation()).isGreaterThan(lease.generation());
        assertThat(f.ledger.reserve(next,f.scope,UUID.randomUUID().toString(),BigDecimal.ONE).approved()).isFalse();assertThat(f.ledger.succeed(lease,"{}")).isFalse();assertThat(f.ledger.succeed(next,"{}")).isTrue();
        assertThat(f.ledger.budget(f.scope).spent()).isEqualByComparingTo("0.2");assertThat(f.ledger.budget(f.scope).reserved()).isZero();
        assertThatThrownBy(()->f.ledger.saveScoreReply(lease,call,"{\"known\":false}",new BigDecimal("0.2"),"fixture-provider")).isInstanceOf(RuntimeException.class);
    }
    @Test void feeReplyTransactionRollsBackOnConflictAndOriginalCallerCanReconcileLateUnknown(){
        dispatch();f.expire(lease);assertThat(f.ledger.claim(Kind.SCORE,"score-restart",300)).isEmpty();assertThat(f.ledger.budget(f.scope).paused()).isTrue();
        save();var recovered=f.ledger.claim(Kind.SCORE,"score-restart",300).orElseThrow();assertThat(f.ledger.succeed(recovered,"{}")).isTrue();assertThat(f.ledger.budget(f.scope).paused()).isTrue();
        assertThatThrownBy(()->f.ledger.saveScoreReply(lease,call,"{\"known\":true}",new BigDecimal("0.3"),"fixture-provider")).isInstanceOf(RuntimeException.class);assertThat(f.ledger.budget(f.scope).spent()).isEqualByComparingTo("0.2");
    }
    @Test void wrongTypeJobCallGenerationAndCorruptCheckpointAreRejected(){
        assertThatThrownBy(()->f.ledger.dispatchScore(new Lease(new Job(lease.job().id(),f.assessment,f.actor,Kind.TTS,1,"{}",null,State.RUNNING,1),lease.generation(),lease.worker()),call,"{}")).isInstanceOf(IllegalArgumentException.class);
        dispatch();assertThatThrownBy(()->f.ledger.saveScoreReply(new Lease(lease.job(),lease.generation()+1,lease.worker()),call,"{}",BigDecimal.ZERO,"fixture")).isInstanceOf(RuntimeException.class);
        f.ledger.enqueue(f.actor,f.assessment,Kind.SCORE,1,UUID.randomUUID().toString(),"{}");var other=f.ledger.claim(Kind.SCORE,"other",300).orElseThrow();String otherCall=f.reserve(other,"1");
        assertThatThrownBy(()->f.ledger.dispatchScore(other,call,"{}")).isInstanceOf(RuntimeException.class);assertThat(f.ledger.dispatchScore(other,otherCall,"{}")).isTrue();
        assertThatThrownBy(()->f.ledger.saveScoreReply(other,call,"{}",BigDecimal.ZERO,"fixture")).isInstanceOf(RuntimeException.class);
        assertThat(f.ledger.retryScorePublication(lease)).isEqualTo(AsyncRetry.STALE);f.jdbc.update("UPDATE prepared_score_call SET input_sha256=REPEAT('a',64) WHERE job_id=?",lease.job().id());assertThatThrownBy(()->f.ledger.scoreCall(lease.job().id())).isInstanceOf(RuntimeException.class);
    }
    @Test void repeatedNonPaidPublicationFailuresAreDurableAndBounded(){
        dispatch();save();Lease current=lease;
        for(int n=0;n<2;n++){assertThat(f.ledger.retryScorePublication(current)).isEqualTo(AsyncRetry.DEFERRED);f.jdbc.update("UPDATE prepared_job SET next_run_at=CURRENT_TIMESTAMP(3) WHERE id=?",lease.job().id());current=f.ledger.claim(Kind.SCORE,"score-restart",300).orElseThrow();}
        assertThat(f.ledger.retryScorePublication(current)).isEqualTo(AsyncRetry.EXHAUSTED);assertThat(f.ledger.retryScorePublication(lease)).isEqualTo(AsyncRetry.STALE);
        assertThat(f.ledger.get(lease.job().id()).orElseThrow().state()).isEqualTo(State.NEEDS_REVIEW);assertThat(f.ledger.scoreCall(lease.job().id()).orElseThrow().publicationFailures()).isEqualTo(3);
        f.jdbc.update("UPDATE prepared_score_call SET reply_sha256=REPEAT('a',64) WHERE job_id=?",lease.job().id());assertThatThrownBy(()->f.ledger.scoreCall(lease.job().id())).isInstanceOf(RuntimeException.class);
    }
}
