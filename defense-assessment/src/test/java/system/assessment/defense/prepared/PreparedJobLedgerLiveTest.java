package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import system.assessment.defense.prepared.jobs.PreparedJobLedger;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.*;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real dedicated MySQL; synthetic costs, never calls a paid provider. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS", matches="true")
class PreparedJobLedgerLiveTest {
    JdbcTemplate jdbc; HikariDataSource ds; PreparedJobLedger ledger; int actor, assessment; String scope;
    @BeforeAll static void migrateOnlyTheDedicatedDatabase() {
        var ds=new DriverManagerDataSource(PreparedLiveDatabase.jdbcUrl(),
                required("PREPARED_DB_USERNAME"),required("PREPARED_DB_PASSWORD"));
        assertThat(new JdbcTemplate(ds).queryForObject("SELECT DATABASE()",String.class)).isEqualTo("prepared_interview");
        Flyway.configure().dataSource(ds).cleanDisabled(true).load().migrate();
    }
    @BeforeEach void fixtures() {
        ds = new HikariDataSource();
        ds.setJdbcUrl(PreparedLiveDatabase.jdbcUrl());
        ds.setUsername(required("PREPARED_DB_USERNAME")); ds.setPassword(required("PREPARED_DB_PASSWORD"));
        ds.setMaximumPoolSize(20); ds.setMinimumIdle(1); ds.setConnectionTimeout(5000);
        jdbc = new JdbcTemplate(ds); jdbc.setQueryTimeout(5);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("prepared_interview");
        ledger = new PreparedJobLedger(jdbc, new JdbcTransactionManager(ds));
        String account="prepared-jobs-"+UUID.randomUUID();
        jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('任务联调隔离账号',?,'not-a-login-credential',1)",account);
        actor=Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account));
        jdbc.update("INSERT INTO assessment(theme,create_id,deleted) VALUES ('独立任务联调',?,0)",actor);
        assessment=Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM assessment WHERE create_id=?",Integer.class,actor));
        scope="prepared-fixture-"+UUID.randomUUID(); ledger.initializeBudget(scope, money("100"), money("80"));
    }
    @AfterEach void cleanOnlyOwnedRows() {
        if(jdbc==null)return;
        jdbc.update("DELETE FROM prepared_budget_event WHERE scope_id=?",scope);
        jdbc.update("DELETE FROM prepared_model_call WHERE scope_id=?",scope);
        jdbc.update("DELETE FROM prepared_job_dependency WHERE job_id IN (SELECT id FROM prepared_job WHERE assessment_id=?)",assessment);
        jdbc.update("DELETE FROM prepared_job WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_budget WHERE scope_id=?",scope);
        jdbc.update("DELETE FROM assessment WHERE id=?",assessment);
        jdbc.update("DELETE FROM `user` WHERE id=?",actor);
        ds.close();
    }
    Job enqueue() {return ledger.enqueue(actor,assessment,Kind.QUESTIONS,0,UUID.randomUUID().toString(),"{\"requirements\":\"预置题目\"}");}
    Lease claim() {return ledger.claim(Kind.QUESTIONS,"fixture-worker",300).orElseThrow();}
    String reserve(Lease lease, String bound) {
        String id=UUID.randomUUID().toString();
        assertThat(ledger.reserve(lease,scope,id,money(bound)).approved()).isTrue(); return id;
    }
    void expire(Lease lease) {jdbc.update("UPDATE prepared_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",lease.job().id());}

    @Test void enqueueIsPersistentIdempotentAndConflictingRequestsCannotOverwrite() {
        String key=UUID.randomUUID().toString();
        Job first=ledger.enqueue(actor,assessment,Kind.QUESTIONS,4,key,"{\"b\":2,\"a\":1}");
        assertThat(ledger.enqueue(actor,assessment,Kind.QUESTIONS,4,key,"{\"a\":1,\"b\":2}").id()).isEqualTo(first.id());
        assertThatThrownBy(()->ledger.enqueue(actor,assessment,Kind.QUESTIONS,5,key,"{\"a\":1,\"b\":2}"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(ledger.get(first.id()).orElseThrow().inputVersion()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=?",Integer.class,assessment)).isEqualTo(1);
    }
    @Test void concurrentWorkersClaimEachJobOnceAndCompletionIsGenerationFenced() throws Exception {
        Job job=enqueue();
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1); List<Future<Optional<Lease>>> results=new ArrayList<>();
            for(int i=0;i<10;i++){String worker="fixture-worker-"+i;results.add(workers.submit(()->{start.await();return ledger.claim(Kind.QUESTIONS,worker,60);}));}
            start.countDown(); List<Lease> winners=new ArrayList<>();
            for(var result:results)result.get(15,TimeUnit.SECONDS).ifPresent(winners::add);
            assertThat(winners).hasSize(1); Lease lease=winners.getFirst();
            assertThat(ledger.succeed(lease,"{\"suggestions\":[]}")).isTrue();
            assertThat(ledger.succeed(lease,"{\"suggestions\":[\"late\"]}")).isFalse();
            assertThat(ledger.get(job.id()).orElseThrow().state()).isEqualTo(State.SUCCEEDED);
        }
    }
    @Test void unsentExpiredTaskCanBeRecoveredButOldWorkerCannotWrite() {
        enqueue(); Lease first=claim(); expire(first);
        assertThat(ledger.claim(Kind.QUESTIONS,"fixture-restart",60)).isEmpty();
        jdbc.update("UPDATE prepared_job SET next_run_at=CURRENT_TIMESTAMP(3) WHERE id=?",first.job().id());
        Lease second=ledger.claim(Kind.QUESTIONS,"fixture-restart",60).orElseThrow();
        assertThat(second.generation()).isGreaterThan(first.generation());
        assertThat(ledger.heartbeat(first,60)).isFalse();
        assertThat(ledger.succeed(first,"{}")).isFalse();
        assertThat(ledger.heartbeat(second,60)).isTrue();
        assertThat(ledger.succeed(second,"{}")).isTrue();
    }
    @Test void aDispatchedExpiredTaskIsNotAutomaticallyReplayedAndKeepsItsReservation() {
        enqueue(); Lease lease=claim(); String call=reserve(lease,"2");
        assertThat(ledger.markDispatched(lease,call)).isTrue();
        assertThat(ledger.markDispatched(lease,call)).isFalse();
        expire(lease); assertThat(ledger.claim(Kind.QUESTIONS,"fixture-restart",60)).isEmpty();
        assertThat(ledger.get(lease.job().id()).orElseThrow().state()).isEqualTo(State.NEEDS_REVIEW);
        assertThat(ledger.budget(scope).reserved()).isEqualByComparingTo("2");
        assertThat(ledger.succeed(lease,"{}")).isFalse();
        ledger.settle(call,money("0.5"),"fixture-provider-1");
        assertThat(ledger.budget(scope).spent()).isEqualByComparingTo("0.5");
        assertThat(ledger.budget(scope).reserved()).isZero();
    }
    @Test void reservationAndSettlementAreIdempotentAndCannotBeRetargeted() {
        enqueue(); Lease lease=claim(); String call=reserve(lease,"3");
        assertThat(ledger.reserve(lease,scope,call,money("3")).approved()).isTrue();
        assertThatThrownBy(()->ledger.reserve(lease,scope,call,money("4"))).isInstanceOf(BusinessException.class);
        assertThat(ledger.budget(scope).reserved()).isEqualByComparingTo("3");
        assertThatThrownBy(()->ledger.settle(call,money("1"),"fixture-provider")).isInstanceOf(BusinessException.class);
        assertThat(ledger.markDispatched(lease,call)).isTrue();
        assertThat(ledger.succeed(lease,"{}")).isFalse();
        ledger.settle(call,money("1"),"fixture-provider"); ledger.settle(call,money("1"),"fixture-provider");
        assertThatThrownBy(()->ledger.settle(call,money("2"),"fixture-provider")).isInstanceOf(BusinessException.class);
        assertThat(ledger.budget(scope).spent()).isEqualByComparingTo("1");
        assertThat(ledger.budget(scope).reserved()).isZero();
        assertThat(ledger.succeed(lease,"{}")).isTrue();
    }
    @Test void hundredConcurrentReservationsCannotCrossThePauseThreshold() throws Exception {
        List<Lease> leases=new ArrayList<>();for(int i=0;i<100;i++){enqueue();leases.add(claim());}
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Decision>> results=new ArrayList<>();var start=new CountDownLatch(1);
            for(Lease lease:leases)results.add(workers.submit(()->{start.await();return ledger.reserve(lease,scope,UUID.randomUUID().toString(),money("1"));}));
            start.countDown();int approved=0;for(var result:results)if(result.get(30,TimeUnit.SECONDS).approved())approved++;
            assertThat(approved).isEqualTo(79);
            Budget snapshot=ledger.budget(scope);assertThat(snapshot.reserved()).isEqualByComparingTo("79");assertThat(snapshot.paused()).isTrue();
            assertThat(snapshot.spent().add(snapshot.reserved())).isLessThan(money("80"));
        }
    }
    @Test void restartDoesNotResetCostsPauseOrAnApprovedScopeLimit() {
        enqueue(); Lease lease=claim(); reserve(lease,"79");
        enqueue(); Lease other=claim(); assertThat(ledger.reserve(other,scope,UUID.randomUUID().toString(),money("1")).approved()).isFalse();
        var restarted=new PreparedJobLedger(jdbc,new JdbcTransactionManager(jdbc.getDataSource()));
        restarted.initializeBudget(scope,money("100"),money("80"));
        assertThat(restarted.budget(scope).reserved()).isEqualByComparingTo("79");assertThat(restarted.budget(scope).paused()).isTrue();
        assertThatThrownBy(()->restarted.initializeBudget(scope,money("99"),money("80"))).isInstanceOf(BusinessException.class);
    }
    @Test void uncertainCallsNeverReleaseOrRetryTheirPossibleCharges() {
        enqueue();Lease lease=claim();String call=reserve(lease,"5");ledger.markDispatched(lease,call);
        ledger.uncertain(call);ledger.uncertain(call);
        assertThat(ledger.cancelBeforeDispatch(call)).isFalse();
        assertThat(ledger.budget(scope).reserved()).isEqualByComparingTo("5");assertThat(ledger.budget(scope).paused()).isTrue();
        assertThat(ledger.fail(lease,"MODEL_TIMEOUT",true)).isTrue();
        assertThat(ledger.get(lease.job().id()).orElseThrow().state()).isEqualTo(State.NEEDS_REVIEW);
        assertThat(ledger.claim(Kind.QUESTIONS,"fixture-restart",60)).isEmpty();
    }
    @Test void preDispatchCancellationReleasesOnlyItsOwnReservationAndCannotEnableASecondCall() {
        enqueue();Lease lease=claim();String call=reserve(lease,"5");
        assertThat(ledger.cancelBeforeDispatch(call)).isTrue();assertThat(ledger.cancelBeforeDispatch(call)).isTrue();
        assertThat(ledger.budget(scope).reserved()).isZero();
        assertThat(ledger.markDispatched(lease,call)).isFalse();
        assertThat(ledger.reserve(lease,scope,call,money("5")).approved()).isFalse();
        assertThatThrownBy(()->ledger.reserve(lease,scope,UUID.randomUUID().toString(),money("1"))).isInstanceOf(BusinessException.class);
    }
    @Test void impossibleProviderCostIsRecordedAndFreezesFurtherCallsRatherThanBeingHidden() {
        enqueue();Lease lease=claim();String call=reserve(lease,"1");ledger.markDispatched(lease,call);
        ledger.settle(call,money("2"),"fixture-pricing-drift");
        assertThat(ledger.budget(scope).spent()).isEqualByComparingTo("2");assertThat(ledger.budget(scope).reserved()).isZero();
        assertThat(ledger.budget(scope).paused()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_budget_event WHERE scope_id=? AND action='BOUND_EXCEEDED'",Integer.class,scope)).isEqualTo(1);
    }
    @Test void invalidLimitsIdentifiersAndDeletedParentsNeverCreateTasksOrBudgetRows() {
        for(String[] pair:List.of(new String[]{"101","80"},new String[]{"100","81"},new String[]{"10","11"},new String[]{"-1","0"}))
            assertThatThrownBy(()->ledger.initializeBudget("prepared-invalid",money(pair[0]),money(pair[1]))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ledger.claim(Kind.QUESTIONS,"../worker",60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ledger.claim(Kind.QUESTIONS,"fixture",0)).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",assessment);
        assertThatThrownBy(this::enqueue).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=?",Integer.class,assessment)).isZero();
    }
    @Test void retryableInternalFailuresAreBoundedAndPermanentFailuresNeedReview() {
        Job job=enqueue();
        for(int index=0;index<3;index++) {
            Lease lease=claim();assertThat(ledger.fail(lease,"TEMPORARY_IO",true)).isTrue();
            assertThat(ledger.fail(lease,"LATE_FAILURE",true)).isFalse();
            jdbc.update("UPDATE prepared_job SET next_run_at=CURRENT_TIMESTAMP(3) WHERE id=?",job.id());
        }
        assertThat(ledger.get(job.id()).orElseThrow().state()).isEqualTo(State.NEEDS_REVIEW);
        assertThat(ledger.claim(Kind.QUESTIONS,"fixture",60)).isEmpty();
        enqueue();Lease permanent=claim();assertThat(ledger.fail(permanent,"INVALID_OUTPUT",false)).isTrue();
        assertThat(ledger.get(permanent.job().id()).orElseThrow().state()).isEqualTo(State.NEEDS_REVIEW);
    }
    @Test void expiredRecoveryLocksAtMostOneBudgetScopePerPoll() {
        String secondScope=scope+"-second";
        ledger.initializeBudget(secondScope,money("100"),money("80"));
        try {
            enqueue();Lease first=claim();String firstCall=reserve(first,"1");ledger.markDispatched(first,firstCall);expire(first);
            // Do not claim after first expiry: recoveries would already run during that call.
            jdbc.update("UPDATE prepared_job SET lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 60 SECOND) WHERE id=?",first.job().id());
            enqueue();Lease second=claim();String secondCall=UUID.randomUUID().toString();
            assertThat(ledger.reserve(second,secondScope,secondCall,money("1")).approved()).isTrue();
            ledger.markDispatched(second,secondCall);expire(first);expire(second);
            assertThat(ledger.claim(Kind.QUESTIONS,"fixture-recovery",60)).isEmpty();
            assertThat(List.of(ledger.get(first.job().id()).orElseThrow().state(),ledger.get(second.job().id()).orElseThrow().state()))
                    .containsExactlyInAnyOrder(State.NEEDS_REVIEW,State.RUNNING);
        } finally {
            jdbc.update("DELETE FROM prepared_budget_event WHERE scope_id=?",secondScope);
            jdbc.update("DELETE FROM prepared_model_call WHERE scope_id=?",secondScope);
            jdbc.update("DELETE FROM prepared_budget WHERE scope_id=?",secondScope);
        }
    }
    @Test void missingBudgetStaleLeaseAndCrossTaskCallIdentityAreRejectedBeforeDispatch() {
        enqueue();Lease first=claim();String call=reserve(first,"1");
        assertThatThrownBy(()->ledger.budget("prepared-not-initialized")).isInstanceOf(BusinessException.class);
        enqueue();Lease second=claim();
        assertThatThrownBy(()->ledger.markDispatched(second,call)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->ledger.reserve(second,scope,call,money("1"))).isInstanceOf(BusinessException.class);
        expire(first);
        assertThat(ledger.reserve(first,scope,UUID.randomUUID().toString(),money("1")).reason()).isEqualTo("STALE_LEASE");
        assertThat(ledger.markDispatched(first,call)).isFalse();
        assertThatThrownBy(()->ledger.markDispatched(second,UUID.randomUUID().toString())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->ledger.settle(call,money("1"),"http://secret/query?token=value")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void reservationsCreatedBeforePauseCannotBeDispatchedAndCancellationCannotUnpause() {
        enqueue();Lease first=claim();String call=reserve(first,"79");
        enqueue();Lease other=claim();
        assertThat(ledger.reserve(other,scope,UUID.randomUUID().toString(),money("1")).approved()).isFalse();
        assertThat(ledger.markDispatched(first,call)).isFalse();
        ledger.cancelBeforeDispatch(call);
        assertThat(ledger.budget(scope).reserved()).isZero();
        assertThat(ledger.budget(scope).paused()).isTrue();
    }
    Job speechChild(){return ledger.enqueue(actor,assessment,Kind.TTS,2,UUID.randomUUID().toString(),"{\"text\":\"预置播报\"}");}
    Job after(String key,List<String> children){return ledger.enqueueAfter(actor,assessment,Kind.ASSEMBLE,2,key,"{\"audio\":true}",children);}
    @Test void assemblyWaitsForEverySpeechPartWithoutSpendingLeaseAttempts() {
        Job first=speechChild(),second=speechChild(),parent=after(UUID.randomUUID().toString(),List.of(first.id(),second.id()));
        for(int i=0;i<5;i++)assertThat(ledger.claim(Kind.ASSEMBLE,"fixture-assembler",60)).isEmpty();
        assertThat(ledger.get(parent.id()).orElseThrow().attempts()).isZero();
        Lease firstLease=ledger.claim(Kind.TTS,"fixture-speech",60).orElseThrow();ledger.succeed(firstLease,"{}");
        assertThat(ledger.claim(Kind.ASSEMBLE,"fixture-assembler",60)).isEmpty();
        Lease secondLease=ledger.claim(Kind.TTS,"fixture-speech",60).orElseThrow();ledger.succeed(secondLease,"{}");
        Lease assembly=ledger.claim(Kind.ASSEMBLE,"fixture-assembler",60).orElseThrow();assertThat(assembly.job().id()).isEqualTo(parent.id());
        assertThat(ledger.succeed(assembly,"{}")).isTrue();assertThat(ledger.get(parent.id()).orElseThrow().attempts()).isEqualTo(1);
    }
    @Test void failedPartEscalatesItsParentWithoutCallingAnotherPaidServiceOrExhaustingAttempts() {
        Job child=speechChild(),parent=after(UUID.randomUUID().toString(),List.of(child.id()));
        Lease speech=ledger.claim(Kind.TTS,"fixture-speech",60).orElseThrow();ledger.fail(speech,"INVALID_AUDIO",false);
        assertThat(ledger.claim(Kind.ASSEMBLE,"fixture-assembler",60)).isEmpty();
        assertThat(ledger.get(parent.id()).orElseThrow().state()).isEqualTo(State.NEEDS_REVIEW);
        assertThat(ledger.get(parent.id()).orElseThrow().attempts()).isZero();
        assertThat(ledger.get(child.id()).orElseThrow().state()).isEqualTo(State.NEEDS_REVIEW);
    }
    @Test void dependencyIdentityIsCanonicalIdempotentAndCannotBeChangedUnderTheSameKey() {
        Job a=speechChild(),b=speechChild(),c=speechChild();String key=UUID.randomUUID().toString();
        Job first=after(key,List.of(b.id(),a.id()));assertThat(after(key,List.of(a.id(),b.id())).id()).isEqualTo(first.id());
        assertThatThrownBy(()->after(key,List.of(a.id(),c.id()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->after(key,List.of(a.id(),a.id()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->after(key,List.of(UUID.randomUUID().toString()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->ledger.enqueue(actor,assessment,Kind.ASSEMBLE,2,key,"{\"audio\":true}")).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job_dependency WHERE job_id=?",Integer.class,first.id())).isEqualTo(2);
    }
    @Test void dependencyKindsAndOwnersCannotCrossResourcesOrFormCycles() {
        Job questions=enqueue();int before=jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=?",Integer.class,assessment);
        assertThatThrownBy(()->after(UUID.randomUUID().toString(),List.of(questions.id()))).isInstanceOf(BusinessException.class);
        Job child=speechChild();Job parent=after(UUID.randomUUID().toString(),List.of(child.id()));
        assertThatThrownBy(()->ledger.enqueueAfter(actor,assessment,Kind.TTS,2,UUID.randomUUID().toString(),"{}",List.of(parent.id()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ledger.enqueueAfter(actor,assessment,Kind.SCORE,2,UUID.randomUUID().toString(),"{}",List.of(child.id()))).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE prepared_job SET created_by=? WHERE id=?",actor,child.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=?",Integer.class,assessment)).isEqualTo(before+2);
        String otherAccount="prepared-dependency-"+UUID.randomUUID();
        jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('隔离依赖身份',?,'not-a-login-credential',1)",otherAccount);
        int other=jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,otherAccount);
        try {assertThatThrownBy(()->ledger.enqueueAfter(other,assessment,Kind.ASSEMBLE,2,UUID.randomUUID().toString(),"{}",List.of(child.id()))).isInstanceOf(BusinessException.class);}
        finally {jdbc.update("DELETE FROM `user` WHERE id=?",other);}
    }
    @Test void dependencyChainAndStandaloneAssemblyRemainRestartSafe() {
        Job child=speechChild(),assembly=after(UUID.randomUUID().toString(),List.of(child.id()));
        Job transcription=ledger.enqueueAfter(actor,assessment,Kind.TRANSCRIBE,2,UUID.randomUUID().toString(),"{}",List.of(assembly.id()));
        Job scoring=ledger.enqueueAfter(actor,assessment,Kind.SCORE,2,UUID.randomUUID().toString(),"{}",List.of(transcription.id()));
        var restarted=new PreparedJobLedger(jdbc,new JdbcTransactionManager(ds));
        assertThat(restarted.claim(Kind.SCORE,"fixture-score",60)).isEmpty();
        ledger.succeed(restarted.claim(Kind.TTS,"fixture-speech",60).orElseThrow(),"{}");
        assertThat(ledger.succeed(restarted.claim(Kind.ASSEMBLE,"fixture-assembly",60).orElseThrow(),"{}")).isTrue();
        assertThat(ledger.succeed(restarted.claim(Kind.TRANSCRIBE,"fixture-transcribe",60).orElseThrow(),"{}")).isTrue();
        Lease score=restarted.claim(Kind.SCORE,"fixture-score",60).orElseThrow();assertThat(score.job().id()).isEqualTo(scoring.id());ledger.succeed(score,"{}");
        Job standalone=ledger.enqueue(actor,assessment,Kind.ASSEMBLE,0,UUID.randomUUID().toString(),"{}");
        assertThat(restarted.claim(Kind.ASSEMBLE,"fixture-standalone",60).orElseThrow().job().id()).isEqualTo(standalone.id());
    }
    static BigDecimal money(String amount){return new BigDecimal(amount);}
    static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required");return value;}
}
