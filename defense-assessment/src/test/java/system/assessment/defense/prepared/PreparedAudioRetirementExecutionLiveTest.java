package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import system.assessment.defense.prepared.retention.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual isolated NEW MySQL protocol tests. Synthetic results/native fixture
 * media are not cloud deletion, paid processing or physical-device acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAudioRetirementExecutionLiveTest {
    final PreparedRetentionEvidenceLiveTest f=new PreparedRetentionEvidenceLiveTest();
    @TempDir Path folder;
    Clock clock;
    PreparedAudioRetirementPreparationService preparation;
    @BeforeEach void setup()throws Exception{
        f.folder=folder;f.setup();clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return f.now.get();}};
        preparation=new PreparedAudioRetirementPreparationService(f.service,f.p.f.service,f.p.jdbc,manager(),clock);
    }
    org.springframework.transaction.PlatformTransactionManager manager(){return f.p.f.m.f.f.ends.q.f.tx.getTransactionManager();}
    PreparedAudioRetirementExecutionService service(){return new PreparedAudioRetirementExecutionService(f.service,f.p.f.service,f.p.jdbc,manager(),clock);}
    PreparedAudioRetirementPreparation.Command command(long version){return new PreparedAudioRetirementPreparation.Command(UUID.randomUUID().toString(),version);}
    PreparedAudioRetirementPreparation.Receipt stage(){return preparation.stage(f.p.student,f.p.assessment,f.p.attempt,command(0));}
    PreparedAudioRetirementExecution.Receipt begin(String id,PreparedAudioRetirementPreparation.Command command){return service().begin(f.p.student,f.p.assessment,f.p.attempt,id,command);}
    @AfterEach void cleanup(){
        // Only this fixture's exact assessment, child first; never cloud objects.
        f.p.jdbc.update("DELETE FROM prepared_audio_retirement_execution WHERE assessment_id=?",f.p.assessment);
        f.p.jdbc.update("DELETE FROM prepared_audio_retirement_preparation WHERE assessment_id=?",f.p.assessment);f.cleanup();
    }
    @RepeatedTest(3) void immutableIntentReplaysAcrossNewInstancesAndNeverReopensAudioOrChangesAnswers(){
        f.completed();var before=f.rows();var submission=f.p.f.status();int io=mockingDetails(f.p.f.m.f.f.store).getInvocations().size();
        var prepared=stage();var command=command(1);var execution=begin(prepared.id(),command);
        assertThat(execution.revision()).isEqualTo(2);assertThat(execution.objectCount()).isPositive();assertThat(execution.byteSize()).isPositive();
        for(int n=0;n<3;n++)assertThat(begin(prepared.id(),command)).isEqualTo(execution);
        assertThat(service().status(f.p.student,f.p.assessment,f.p.attempt)).contains(execution);
        assertThat(preparation.status(f.p.student,f.p.assessment,f.p.attempt).orElseThrow().state()).isEqualTo("EXECUTING");
        for(long version:new long[]{1,2})f.p.conflict(()->preparation.cancel(f.p.student,f.p.assessment,f.p.attempt,prepared.id(),command(version)));
        f.p.conflict(()->f.p.service.reviewMedia(f.p.actor,f.p.assessment,f.p.attempt,f.answer()));
        f.p.conflict(()->f.protection().execute(f.p.actor,f.p.student,f.p.assessment,f.p.attempt,f.command(2,"PROTECT")));
        assertThat(f.p.f.status()).isEqualTo(submission);assertThat(f.p.view(f.answer()).history()).hasSize(2);
        var after=f.rows();for(String table:before.keySet())if(!table.equals("prepared_retention_guard"))assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
        assertThat(mockingDetails(f.p.f.m.f.f.store).getInvocations()).hasSize(io);
        assertThat(f.p.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audio_retirement_execution WHERE assessment_id=?",Integer.class,f.p.assessment)).isEqualTo(1);
    }
    @RepeatedTest(3) void sameParentLockMakesLateCancellationWaitThenRejectTheCommittedIntent()throws Exception{
        f.completed();var prepared=stage();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=f.p.f.m.f.f.ends.q.f.tx;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var writer=pool.submit(()->tx.execute(s->{var result=begin(prepared.id(),command(1));entered.countDown();try{assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}return result;}));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var cancel=pool.submit(()->preparation.cancel(f.p.student,f.p.assessment,f.p.attempt,prepared.id(),command(1)));
            try{assertThatThrownBy(()->cancel.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
            assertThat(writer.get(10,TimeUnit.SECONDS)).isNotNull();assertThatThrownBy(()->cancel.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(system.assessment.defense.infrastructure.exception.BusinessException.class).extracting("code").isEqualTo(409);
            assertThat(preparation.status(f.p.student,f.p.assessment,f.p.attempt).orElseThrow().state()).isEqualTo("EXECUTING");
        }finally{release.countDown();}
    }
    @Test void failedGuardCasAndOuterRollbackLeaveNoOrphanedIntent(){
        f.completed();var prepared=stage();JdbcTemplate failing=spy(f.p.jdbc);
        doReturn(0).when(failing).update(startsWith("UPDATE prepared_retention_guard SET revision="),any(Object[].class));
        var failed=new PreparedAudioRetirementExecutionService(f.service,f.p.f.service,failing,manager(),clock);
        f.p.conflict(()->failed.begin(f.p.student,f.p.assessment,f.p.attempt,prepared.id(),command(1)));
        assertThat(service().status(f.p.student,f.p.assessment,f.p.attempt)).isEmpty();
        f.rollback(()->{assertThat(begin(prepared.id(),command(1))).isNotNull();});
        assertThat(service().status(f.p.student,f.p.assessment,f.p.attempt)).isEmpty();
        assertThat(preparation.status(f.p.student,f.p.assessment,f.p.attempt).orElseThrow().state()).isEqualTo("STAGED");
    }
    @Test void changedDisputeOrMissingCompletionAnchorProtectsEvenAnOldPreparedOriginal(){
        f.completed();var prepared=stage();
        for(String sql:List.of("UPDATE prepared_attempt SET requires_supplement_review=1 WHERE id=?","UPDATE prepared_attempt SET first_results_completed_at=NULL WHERE id=?"))f.rollback(()->{
            f.p.jdbc.update(sql,f.p.attempt);f.p.conflict(()->begin(prepared.id(),command(1)));
            assertThat(service().status(f.p.student,f.p.assessment,f.p.attempt)).isEmpty();
        });assertThat(begin(prepared.id(),command(1))).isNotNull();
    }
    @Test void damagedExecutionAndForeignPreparationBindingsCannotReopenOrRebind(){
        f.completed();var prepared=stage();begin(prepared.id(),command(1));
        f.rollback(()->{f.p.jdbc.update("UPDATE prepared_audio_retirement_execution SET receipt_sha256=REPEAT('f',64) WHERE assessment_id=?",f.p.assessment);
            assertThatThrownBy(()->service().status(f.p.student,f.p.assessment,f.p.attempt)).isInstanceOf(IllegalStateException.class);});
        f.rollback(()->assertThatThrownBy(()->f.p.jdbc.update("UPDATE prepared_audio_retirement_execution SET student_id=student_id+1 WHERE assessment_id=?",f.p.assessment))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class));
        assertThat(preparation.status(f.p.student,f.p.assessment,f.p.attempt).orElseThrow().state()).isEqualTo("EXECUTING");
    }
}
