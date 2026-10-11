package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.prepared.retention.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real NEW MySQL/transaction recovery; native media and synthetic results reuse
 * explicit fixtures. No object deletion, cloud or physical-device acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAudioRetirementPreparationLiveTest {
    final PreparedRetentionEvidenceLiveTest f=new PreparedRetentionEvidenceLiveTest();
    @TempDir Path folder;
    PreparedAudioRetirementPreparationService service;
    Clock clock;
    @BeforeEach void setup()throws Exception{
        f.folder=folder;f.setup();clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return f.now.get();}};
        service=newService();
    }
    PreparedAudioRetirementPreparationService newService(){return new PreparedAudioRetirementPreparationService(f.service,f.p.f.service,f.p.jdbc,f.p.f.m.f.f.ends.q.f.tx.getTransactionManager(),clock);}
    @AfterEach void cleanup(){f.p.jdbc.update("DELETE FROM prepared_audio_retirement_preparation WHERE assessment_id=?",f.p.assessment);f.cleanup();}
    PreparedAudioRetirementPreparation.Command command(long revision){return new PreparedAudioRetirementPreparation.Command(UUID.randomUUID().toString(),revision);}
    PreparedAudioRetirementPreparation.Receipt stage(PreparedAudioRetirementPreparation.Command command){return service.stage(f.p.student,f.p.assessment,f.p.attempt,command);}
    PreparedAudioRetirementPreparation.View status(){return service.status(f.p.student,f.p.assessment,f.p.attempt).orElseThrow();}
    PreparedAudioRetirementPreparation.Cancellation cancel(String id,PreparedAudioRetirementPreparation.Command command){return service.cancel(f.p.student,f.p.assessment,f.p.attempt,id,command);}

    @RepeatedTest(3) void stageAndCancelRecoverOriginalReceiptsAcrossNewServiceInstancesWithoutRemovingAnyContent(){
        f.completed();var before=f.rows();var originalSubmission=f.p.f.status();int io=org.mockito.Mockito.mockingDetails(f.p.f.m.f.f.store).getInvocations().size();
        var command=command(0);var receipt=stage(command);assertThat(receipt.revision()).isEqualTo(1);assertThat(receipt.audioDueAt()).isEqualTo(f.now.get().toString());assertThat(receipt.evidenceSha256()).matches("[a-f0-9]{64}");
        assertThat(status().state()).isEqualTo("STAGED");service=newService();for(int n=0;n<3;n++)assertThat(stage(command)).isEqualTo(receipt);
        f.p.conflict(()->f.p.service.reviewMedia(f.p.actor,f.p.assessment,f.p.attempt,f.answer()));
        f.p.conflict(()->f.protection().execute(f.p.actor,f.p.student,f.p.assessment,f.p.attempt,f.command(1,"PROTECT")));
        assertThat(f.p.view(f.answer()).history()).hasSize(2);assertThat(f.p.f.status()).isEqualTo(originalSubmission);
        var cancelCommand=command(1);var cancellation=cancel(receipt.id(),cancelCommand);service=newService();assertThat(cancel(receipt.id(),cancelCommand)).isEqualTo(cancellation);
        assertThat(stage(command)).isEqualTo(receipt);assertThat(status().state()).isEqualTo("CANCELLED");assertThat(status().cancellation()).isEqualTo(cancellation);
        assertThat(f.p.service.reviewMedia(f.p.actor,f.p.assessment,f.p.attempt,f.answer())).isNotNull();
        var after=f.rows();for(String table:before.keySet())if(!table.startsWith("prepared_retention_"))assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
        assertThat(org.mockito.Mockito.mockingDetails(f.p.f.m.f.f.store).getInvocations()).hasSize(io);
    }
    @Test void cannotStageBeforeExpiryWithoutResultsDuringHoldOrWithWrongVersion(){
        assertThatThrownBy(()->stage(command(0))).isInstanceOf(RuntimeException.class);f.completed();f.now.set(f.now.get().minusMillis(1));f.p.conflict(()->stage(command(0)));
        f.now.set(f.now.get().plusMillis(1));f.p.conflict(()->stage(command(1)));f.protection().execute(f.p.actor,f.p.student,f.p.assessment,f.p.attempt,f.command(0,"PROTECT"));f.p.conflict(()->stage(command(1)));
        assertThat(f.p.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audio_retirement_preparation WHERE assessment_id=?",Integer.class,f.p.assessment)).isZero();
    }
    @Test void onlyOriginalScopeAndExactCommandCanRecoverOrCancelAndCancelledPreparationCannotBeReactivated(){
        f.completed();var command=command(0);var receipt=stage(command);
        f.p.conflict(()->stage(new PreparedAudioRetirementPreparation.Command(command.idempotencyKey(),1)));
        assertThatThrownBy(()->service.status(f.p.student+1,f.p.assessment,f.p.attempt)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.stage(f.p.student,f.p.assessment,UUID.randomUUID().toString(),command)).isInstanceOf(RuntimeException.class);
        f.p.conflict(()->cancel(UUID.randomUUID().toString(),command(1)));f.p.conflict(()->cancel(receipt.id(),command(0)));
        var cancelled=command(1);cancel(receipt.id(),cancelled);f.p.conflict(()->cancel(receipt.id(),command(1)));
        var next=stage(command(2));assertThat(next.id()).isNotEqualTo(receipt.id());assertThat(status().preparation()).isEqualTo(next);
        assertThat(cancel(receipt.id(),cancelled).revision()).isEqualTo(2);assertThat(status().state()).isEqualTo("STAGED");
    }
    @Test void detachedGuardOrAlteredReceiptFailsClosedRatherThanReopeningOriginalAudio(){
        f.completed();var receipt=stage(command(0));
        for(String sql:List.of("UPDATE prepared_retention_guard SET audio_state='RETAINED' WHERE attempt_id=?", "UPDATE prepared_retention_guard SET revision=revision+1 WHERE attempt_id=?"))f.rollback(()->{
            f.p.jdbc.update(sql,f.p.attempt);assertThatThrownBy(this::status).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->f.p.service.reviewMedia(f.p.actor,f.p.assessment,f.p.attempt,f.answer())).isInstanceOf(IllegalStateException.class);
        });
        f.rollback(()->{f.p.jdbc.update("UPDATE prepared_audio_retirement_preparation SET receipt_sha256=REPEAT('a',64) WHERE id=?",receipt.id());assertThatThrownBy(this::status).isInstanceOf(IllegalStateException.class);});
        f.rollback(()->{f.p.jdbc.update("DELETE FROM prepared_retention_guard WHERE attempt_id=?",f.p.attempt);assertThatThrownBy(()->f.protection().status(f.p.actor,f.p.student,f.p.assessment,f.p.attempt)).isInstanceOf(IllegalStateException.class);});
        assertThat(status().state()).isEqualTo("STAGED");
    }
    @Test void outerTransactionRollbackCannotLeaveAnOrphanedStageOrCancellation(){
        f.completed();f.rollback(()->{stage(command(0));assertThat(status().state()).isEqualTo("STAGED");});assertThat(service.status(f.p.student,f.p.assessment,f.p.attempt)).isEmpty();
        var receipt=stage(command(0));f.rollback(()->cancel(receipt.id(),command(1)));assertThat(status().state()).isEqualTo("STAGED");
    }
    @Test void nativeDriverDatetimeProjectionIsNotAJdbcTimestamp(){
        assertThat(f.p.jdbc.queryForMap("SELECT CAST(CURRENT_TIMESTAMP(3) AS DATETIME(3)) AS value").get("value"))
            .isInstanceOf(java.time.LocalDateTime.class);
    }
    @RepeatedTest(3) void preparationParentLockSerializesLateProtectionWithoutSilentlyDiscardingEitherDecision()throws Exception{
        f.completed();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=f.p.f.m.f.f.ends.q.f.tx;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var writer=pool.submit(()->tx.execute(s->{var receipt=stage(command(0));entered.countDown();try{assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}return receipt;}));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var held=pool.submit(()->f.protection().execute(f.p.actor,f.p.student,f.p.assessment,f.p.attempt,f.command(1,"PROTECT")));
            try{assertThatThrownBy(()->held.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
            var receipt=writer.get(10,TimeUnit.SECONDS);assertThatThrownBy(()->held.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(system.assessment.defense.infrastructure.exception.BusinessException.class).extracting("code").isEqualTo(409);
            assertThat(status().state()).isEqualTo("STAGED");cancel(receipt.id(),command(1));assertThat(f.protection().execute(f.p.actor,f.p.student,f.p.assessment,f.p.attempt,f.command(2,"PROTECT")).held()).isTrue();
        }finally{release.countDown();}
    }
    @RepeatedTest(3) void openIoAndIssuedReadCapabilityPreventPreparationUntilVerifiedRelease(){
        f.completed();var media=f.p.service.reviewMedia(f.p.actor,f.p.assessment,f.p.attempt,f.answer());var io=f.p.service.beginReviewAudioIo(f.p.actor,f.p.assessment,f.p.attempt,f.answer(),media);
        f.p.conflict(()->stage(command(0)));f.now.set(f.now.get().plusSeconds(86400));service=newService();f.p.conflict(()->stage(command(0)));
        f.p.service.endAudioIo(io);f.p.service.recordAudioAccess(f.p.actor,f.p.assessment,f.p.attempt,f.answer(),media,f.now.get().plusSeconds(300));
        f.p.conflict(()->stage(command(0)));f.now.set(f.now.get().plusSeconds(300));assertThat(stage(command(0))).isNotNull();
    }
    @Test void backwardsClockCannotCancelAndPreparationMustLeaveARemainingSafeRevision(){
        f.completed();var receipt=stage(command(0));f.now.set(f.now.get().minusMillis(1));f.p.conflict(()->cancel(receipt.id(),command(1)));f.now.set(f.now.get().plusMillis(1));cancel(receipt.id(),command(1));
        f.rollback(()->{f.p.jdbc.update("UPDATE prepared_retention_guard SET revision=9007199254740990 WHERE attempt_id=?",f.p.attempt);f.p.conflict(()->stage(command(9007199254740990L)));});
    }
    @Test void defaultConstructorKeepsFreshResultsAndMalformedOrNullScopeCommandsNeverMutate(){
        service=new PreparedAudioRetirementPreparationService(f.service,f.p.f.service,f.p.jdbc,f.p.f.m.f.f.ends.q.f.tx.getTransactionManager());
        assertThat(service.status(f.p.student,f.p.assessment,f.p.attempt)).isEmpty();assertThatThrownBy(()->stage(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.status(0,f.p.assessment,f.p.attempt)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.status(f.p.student,0,f.p.attempt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.status(f.p.student,f.p.assessment,"bad")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->cancel(UUID.randomUUID().toString(),null)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->cancel("bad",command(0))).isInstanceOf(IllegalArgumentException.class);
        f.completed();f.p.conflict(()->stage(command(0)));
    }
    @Test void existingReleasedHoldAndClockRollbackCannotBeBypassedWithAnEligibleCandidate(){
        f.completed();f.now.set(f.now.get().plusMillis(1));f.protection().execute(f.p.actor,f.p.student,f.p.assessment,f.p.attempt,f.command(0,"PROTECT"));f.protection().execute(f.p.actor,f.p.student,f.p.assessment,f.p.attempt,f.command(1,"RELEASE"));
        f.now.set(f.now.get().minusMillis(1));assertThat(f.inspect().audioDeleteCandidate()).isTrue();f.p.conflict(()->stage(command(2)));
        f.now.set(f.now.get().plusMillis(1));var receipt=stage(command(2));assertThat(receipt.revision()).isEqualTo(3);cancel(receipt.id(),command(3));
        assertThat(f.protection().status(f.p.actor,f.p.student,f.p.assessment,f.p.attempt).revision()).isEqualTo(4);
    }
    @Test void failedGuardWriteRollsBackPreparationAndFailedCancelWriteLeavesOriginalFenceIntact(){
        f.completed();f.protection().execute(f.p.actor,f.p.student,f.p.assessment,f.p.attempt,f.command(0,"PROTECT"));f.protection().execute(f.p.actor,f.p.student,f.p.assessment,f.p.attempt,f.command(1,"RELEASE"));
        var failing=org.mockito.Mockito.spy(f.p.jdbc);org.mockito.Mockito.doReturn(0).when(failing).update(org.mockito.ArgumentMatchers.startsWith("UPDATE prepared_retention_guard SET audio_state"),org.mockito.ArgumentMatchers.any(Object[].class));
        var failed=new PreparedAudioRetirementPreparationService(f.service,f.p.f.service,failing,f.p.f.m.f.f.ends.q.f.tx.getTransactionManager(),clock);
        f.p.conflict(()->failed.stage(f.p.student,f.p.assessment,f.p.attempt,command(2)));assertThat(service.status(f.p.student,f.p.assessment,f.p.attempt)).isEmpty();
        var receipt=stage(command(2));f.p.conflict(()->failed.cancel(f.p.student,f.p.assessment,f.p.attempt,receipt.id(),command(3)));assertThat(status().state()).isEqualTo("STAGED");cancel(receipt.id(),command(3));
    }
    @Test void unboundPhysicalBundleCannotBeIgnoredWhenPreparingOriginalAudioRetirement(){
        f.completed();f.rollback(()->{
            // A saved answer alone is not proof that every registered physical
            // object is covered. Extra headers must fail closed, not be omitted.
            f.p.jdbc.update("""
                INSERT INTO prepared_answer_upload_bundle(id,assessment_id,student_id,attempt_id,answer_id,
                    command_json,command_sha256,byte_size,sha256,deployment_id,object_key,reserved_at,confirmed_at)
                SELECT REPEAT('e',64),assessment_id,student_id,attempt_id,answer_id,command_json,command_sha256,
                    byte_size,sha256,deployment_id,object_key,reserved_at,confirmed_at
                FROM prepared_answer_upload_bundle WHERE answer_id=? LIMIT 1
                """,f.answer());
            assertThatThrownBy(()->stage(command(0))).isInstanceOf(IllegalStateException.class);
            assertThat(service.status(f.p.student,f.p.assessment,f.p.attempt)).isEmpty();
        });
    }
}
