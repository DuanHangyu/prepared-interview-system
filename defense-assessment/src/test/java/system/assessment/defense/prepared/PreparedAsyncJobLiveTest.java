package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import system.assessment.defense.prepared.jobs.PreparedJobLedger;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;

/** Real MySQL restart/lease/fee fences. No provider request or real monetary charge. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAsyncJobLiveTest {
    final PreparedJobLedgerLiveTest f=new PreparedJobLedgerLiveTest();Lease lease;String call,task;
    @BeforeAll static void migrate(){PreparedJobLedgerLiveTest.migrateOnlyTheDedicatedDatabase();}
    @BeforeEach void setup(){f.fixtures();f.ledger.enqueue(f.actor,f.assessment,Kind.TRANSCRIBE,1,UUID.randomUUID().toString(),"{}");lease=f.ledger.claim(Kind.TRANSCRIBE,"async-fixture",300).orElseThrow();call=f.reserve(lease,"1");task=UUID.randomUUID().toString();}
    @AfterEach void clean(){f.jdbc.update("DELETE FROM prepared_async_model_task WHERE job_id IN (SELECT id FROM prepared_job WHERE assessment_id=?)",f.assessment);f.cleanOnlyOwnedRows();}
    boolean dispatch(){return f.ledger.dispatchAsync(lease,call,"{\"policy\":\"historical-price\"}");}
    void due(){f.jdbc.update("UPDATE prepared_job SET next_run_at=CURRENT_TIMESTAMP(3) WHERE id=?",lease.job().id());}
    @Test void knownTaskSurvivesLeaseExpiryAndManyPollsWithoutAnotherPaidAttemptOrBudgetReservation(){
        assertThat(dispatch()).isTrue();assertThat(dispatch()).isFalse();f.ledger.acceptAsync(lease,call,task);f.expire(lease);
        var recovered=new PreparedJobLedger(f.jdbc,new org.springframework.jdbc.support.JdbcTransactionManager(f.ds));
        var next=recovered.claim(Kind.TRANSCRIBE,"restarted",300).orElseThrow();assertThat(next.generation()).isGreaterThan(lease.generation());
        assertThat(recovered.async(next.job().id()).orElseThrow().taskId()).isEqualTo(task);assertThat(recovered.budget(f.scope).paused()).isFalse();
        assertThat(recovered.succeed(next,"{}")).isFalse(); // earlier-generation fee must not be bypassed
        for(int n=0;n<6;n++){assertThat(recovered.yieldAsync(next,10)).isTrue();due();next=recovered.claim(Kind.TRANSCRIBE,"restarted",300).orElseThrow();}
        assertThat(next.job().attempts()).isEqualTo(1);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_model_call WHERE job_id=?",Integer.class,next.job().id())).isEqualTo(1);
        assertThat(recovered.reserve(next,f.scope,UUID.randomUUID().toString(),new BigDecimal("1")).approved()).isFalse();
        recovered.settle(call,new BigDecimal("0.4"),task);assertThat(recovered.saveAsyncResult(next,"{\"normalized\":true}")).isTrue();
        assertThat(recovered.yieldAsync(next,10)).isTrue();due();next=recovered.claim(Kind.TRANSCRIBE,"again",300).orElseThrow();
        assertThat(recovered.async(next.job().id()).orElseThrow().resultJson()).contains("normalized");assertThat(recovered.succeed(next,"{}")).isTrue();
        assertThat(recovered.budget(f.scope).spent()).isEqualByComparingTo("0.4");assertThat(recovered.budget(f.scope).reserved()).isZero();
    }
    @Test void lostSubmitResponsePausesAndNeverResubmitsButLateKnownTaskCanResumeWithoutUnpausingBudget(){
        dispatch();f.expire(lease);assertThat(f.ledger.claim(Kind.TRANSCRIBE,"restart",300)).isEmpty();
        assertThat(f.ledger.budget(f.scope).paused()).isTrue();assertThat(f.ledger.get(lease.job().id()).orElseThrow().state()).isEqualTo(State.NEEDS_REVIEW);
        f.ledger.acceptAsync(lease,call,task);due();var next=f.ledger.claim(Kind.TRANSCRIBE,"restart",300).orElseThrow();
        assertThat(f.ledger.budget(f.scope).paused()).isTrue();assertThat(f.ledger.succeed(next,"{}")).isFalse();
        f.ledger.settle(call,new BigDecimal("0.2"),task);assertThat(f.ledger.succeed(next,"{}")).isTrue();
    }
    @Test void pendingQueriesAreScheduledAndActiveOldLeasesCannotWriteOrAttachDifferentTasks(){
        dispatch();f.ledger.acceptAsync(lease,call,task);f.ledger.acceptAsync(lease,call,task);
        assertThatThrownBy(()->f.ledger.acceptAsync(lease,call,UUID.randomUUID().toString())).isInstanceOf(RuntimeException.class);
        assertThat(f.ledger.yieldAsync(lease,30)).isTrue();assertThat(f.ledger.claim(Kind.TRANSCRIBE,"too-soon",300)).isEmpty();
        assertThat(f.ledger.yieldAsync(lease,30)).isFalse();assertThat(f.ledger.saveAsyncResult(lease,"{}")).isFalse();
    }
    @Test void invalidScopeUnknownTaskUnsettledCheckpointAndChangedCheckpointAreRejected(){
        assertThat(f.ledger.async(lease.job().id())).isEmpty();assertThatThrownBy(()->f.ledger.acceptAsync(lease,call,task)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->f.ledger.dispatchAsync(lease,call,"[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.ledger.yieldAsync(lease,0)).isInstanceOf(IllegalArgumentException.class);
        dispatch();assertThatThrownBy(()->f.ledger.acceptAsync(lease,call,"../secret")).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.ledger.yieldAsync(lease,10)).isFalse();f.ledger.acceptAsync(lease,call,task);
        assertThat(f.ledger.saveAsyncResult(lease,"{}")).isFalse();f.ledger.settle(call,new BigDecimal("0.2"),task);
        assertThat(f.ledger.saveAsyncResult(lease,"{\"a\":1}")).isTrue();assertThat(f.ledger.saveAsyncResult(lease,"{\"a\":1}")).isTrue();
        assertThatThrownBy(()->f.ledger.saveAsyncResult(lease,"{\"a\":2}")).isInstanceOf(RuntimeException.class);
    }
    @Test void nonPaidIoFailuresAreDurableBoundedAndDistinctFromSupplierPendingStatus(){
        dispatch();f.ledger.acceptAsync(lease,call,task);assertThat(f.ledger.retryAsync(lease,false)).isEqualTo(AsyncRetry.DEFERRED);due();
        var next=f.ledger.claim(Kind.TRANSCRIBE,"restart",300).orElseThrow();assertThat(f.ledger.async(next.job().id()).orElseThrow().queryFailures()).isEqualTo(1);
        assertThat(f.ledger.pollSucceeded(next)).isTrue();assertThat(f.ledger.async(next.job().id()).orElseThrow().queryFailures()).isZero();
        // Result download failures have a separate counter; healthy status GET cannot erase them.
        assertThat(f.ledger.retryAsync(next,true)).isEqualTo(AsyncRetry.DEFERRED);due();next=f.ledger.claim(Kind.TRANSCRIBE,"restart",300).orElseThrow();f.ledger.pollSucceeded(next);
        assertThat(f.ledger.async(next.job().id()).orElseThrow().resultFailures()).isEqualTo(1);
        for(int n=0;n<2;n++){assertThat(f.ledger.retryAsync(next,false)).isEqualTo(AsyncRetry.DEFERRED);due();next=f.ledger.claim(Kind.TRANSCRIBE,"restart",300).orElseThrow();}
        assertThat(f.ledger.retryAsync(next,false)).isEqualTo(AsyncRetry.EXHAUSTED);assertThat(next.job().attempts()).isEqualTo(1);
        assertThat(f.ledger.retryAsync(lease,false)).isEqualTo(AsyncRetry.STALE);assertThat(f.ledger.pollSucceeded(lease)).isFalse();
        assertThat(f.ledger.get(next.job().id()).orElseThrow().state()).isEqualTo(State.NEEDS_REVIEW);assertThat(f.ledger.budget(f.scope).paused()).isTrue();
    }
    @Test void providerTaskAndCallCannotCrossJobsAndStoredSnapshotsCannotSilentlyChange(){
        dispatch();f.ledger.acceptAsync(lease,call,task);
        f.ledger.enqueue(f.actor,f.assessment,Kind.TRANSCRIBE,1,UUID.randomUUID().toString(),"{}");var other=f.ledger.claim(Kind.TRANSCRIBE,"other",300).orElseThrow();String otherCall=f.reserve(other,"1");
        assertThatThrownBy(()->f.ledger.dispatchAsync(other,call,"{}")).isInstanceOf(RuntimeException.class);
        assertThat(f.ledger.dispatchAsync(other,otherCall,"{}")).isTrue();assertThatThrownBy(()->f.ledger.acceptAsync(other,otherCall,task)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->f.ledger.acceptAsync(other,call,UUID.randomUUID().toString())).isInstanceOf(RuntimeException.class);
        f.jdbc.update("UPDATE prepared_async_model_task SET input_sha256=REPEAT('a',64) WHERE job_id=?",lease.job().id());assertThatThrownBy(()->f.ledger.async(lease.job().id())).isInstanceOf(RuntimeException.class);
    }
}
