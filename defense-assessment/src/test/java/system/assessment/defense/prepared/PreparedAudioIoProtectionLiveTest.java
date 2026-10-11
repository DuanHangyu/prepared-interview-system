package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import system.assessment.defense.prepared.processing.PreparedReviewAudioService;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual NEW database and original proof; SDK/media/grade fixtures are explicit.
 * This does not delete files, call a model or certify physical devices/cloud. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAudioIoProtectionLiveTest {
    final PreparedRetentionEvidenceLiveTest e=new PreparedRetentionEvidenceLiveTest();
    @TempDir Path folder;
    @BeforeEach void setup()throws Exception {e.folder=folder;e.setup();e.completed();}
    @AfterEach void cleanup(){e.cleanup();}
    PreparedOriginalAudioIoJournal.Handle open(){var media=e.p.service.reviewMedia(e.p.actor,e.p.assessment,e.p.attempt,e.answer());return e.p.service.beginReviewAudioIo(e.p.actor,e.p.assessment,e.p.attempt,e.answer(),media);}
    int count(String action){return e.p.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action=?",Integer.class,e.p.assessment,action);}
    @Test void inFlightPrivateReadIsDurablyProtectedBeforeAnyPlaybackCapabilityExists()throws Exception {
        assertThat(e.inspect().audioDeleteCandidate()).isTrue();
        var before=e.p.f.status();var store=mock(PreparedPrivateOssStore.class);
        var clock=Clock.fixed(e.now.get(),ZoneOffset.UTC);
        when(store.previewAnswer(any())).thenAnswer(i->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // No capability has been returned or audited yet. A different service
            // instance must nevertheless observe the ongoing private operation.
            assertThat(e.inspect().audioDeleteCandidate()).isFalse();
            var media=i.getArgument(0,PreparedPrivateOssStore.StoredAnswer.class);
            return new PreparedPrivateOssStore.Preview("https://prepared-fixture.oss-cn-beijing.aliyuncs.com/"+media.objectKey()+"?Signature=explicit-fixture",clock.instant().plusSeconds(300).toString());
        });
        var result=new PreparedReviewAudioService(e.p.service,store,clock).preview(e.p.actor,e.p.assessment,e.p.attempt,e.answer());
        assertThat(e.inspect().audioDeleteCandidate()).isFalse();
        e.now.set(Instant.parse(result.expiresAt()));assertThat(e.inspect().audioDeleteCandidate()).isTrue();
        assertThat(e.p.f.status()).isEqualTo(before);
    }
    @Test void ioEventsHaveDurableStrongOriginalStudentAndAttemptBindings(){
        var io=open();var event=e.p.jdbc.queryForMap("SELECT * FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",e.p.assessment);
        assertThat(io.toString()).isEqualTo("OriginalAudioIo[opaque completion handle]").doesNotContain(e.p.attempt,e.answer());
        assertThat(io).isNotInstanceOf(java.io.Serializable.class);
        assertThat(event).containsEntry("original_student_id",e.p.student).containsEntry("original_attempt_id",e.p.attempt);
        assertThat(event.toString()).doesNotContain("Signature","录音正文","转写");e.p.service.endAudioIo(io);
    }
    @Test void unknownReservedIoActionIsNotSilentlyIgnoredAsNoOperation(){
        var work=e.p.f.service.retentionSources(e.p.student,e.p.assessment,e.p.attempt).sources().getFirst().binding().input();
        e.p.jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,resource_id,resource_sha256) VALUES (?,?,'ORIGINAL_UNKNOWN_IO_OPENED',?,?,?)",e.p.assessment,e.p.actor,work.answerRevision()-1,work.answer(),work.manifestSha256());
        assertThatThrownBy(e::inspect).isInstanceOf(IllegalStateException.class);
    }
    @Test void abandonedOperationSurvivesServiceRestartTimeAndPermissionChangesWithoutAutomaticExpiry(){
        var before=e.p.f.status();var io=open();e.now.set(e.now.get().plusSeconds(30L*86400));
        var other=new system.assessment.defense.prepared.retention.PreparedRetentionEvidenceService(e.p.f.service,e.p.service,e.p.jdbc,e.p.f.m.f.f.ends.q.f.tx.getTransactionManager(),Clock.fixed(e.now.get(),ZoneOffset.UTC));
        assertThat(other.inspect(e.p.student,e.p.assessment,e.p.attempt).protections()).contains(system.assessment.defense.prepared.retention.PreparedAudioRetentionPolicy.Protection.ACTIVE_AUDIO_IO);
        e.p.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",e.p.actor);
        try{e.p.service.endAudioIo(io);assertThat(e.inspect().audioDeleteCandidate()).isTrue();}finally{e.p.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",e.p.actor);}
        assertThat(e.p.f.status()).isEqualTo(before);
    }
    @Test void closingOneReadDoesNotReleaseAnotherAndClosingIsIdempotent(){
        var first=open();var second=open();e.p.service.endAudioIo(first);e.p.service.endAudioIo(first);
        assertThat(e.inspect().audioDeleteCandidate()).isFalse();assertThat(count("ORIGINAL_PREVIEW_IO_CLOSED")).isEqualTo(1);
        e.p.service.endAudioIo(second);assertThat(e.inspect().audioDeleteCandidate()).isTrue();assertThat(count("ORIGINAL_PREVIEW_IO_CLOSED")).isEqualTo(2);
    }
    @Test void failedPrivateIoClosesOnlyTheStoppedOperationWithoutIssuingPlaybackOrChangingAnswers()throws Exception{
        var before=e.p.f.status();var store=mock(PreparedPrivateOssStore.class);
        when(store.previewAnswer(any())).thenAnswer(i->{assertThat(e.inspect().audioDeleteCandidate()).isFalse();throw new java.io.IOException("explicit SDK failure");});
        var audio=new PreparedReviewAudioService(e.p.service,store,Clock.fixed(e.now.get(),ZoneOffset.UTC));
        assertThatThrownBy(()->audio.preview(e.p.actor,e.p.assessment,e.p.attempt,e.answer())).isInstanceOf(system.assessment.defense.infrastructure.exception.BusinessException.class).extracting("code").isEqualTo(503);
        assertThat(e.inspect().audioDeleteCandidate()).isTrue();assertThat(count("ORIGINAL_AUDIO_AUTHORIZED")).isZero();assertThat(e.p.f.status()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"manifest","revision","duplicate-close","missing-open","wrong-close-kind"})
    void damagedOriginalIoEvidenceNeverBecomesAnEligibleCandidate(String fault){
        var io=open();e.p.service.endAudioIo(io);
        e.rollback(()->{
            switch(fault){
                case "manifest"->e.p.jdbc.update("UPDATE prepared_audit SET resource_sha256=REPEAT('f',64) WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",e.p.assessment);
                case "revision"->e.p.jdbc.update("UPDATE prepared_audit SET resource_version=resource_version+10 WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",e.p.assessment);
                case "missing-open"->e.p.jdbc.update("DELETE FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_OPENED'",e.p.assessment);
                case "wrong-close-kind"->e.p.jdbc.update("UPDATE prepared_audit SET action='ORIGINAL_ASSEMBLY_IO_CLOSED' WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_CLOSED'",e.p.assessment);
                case "duplicate-close"->e.p.jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,resource_id,resource_sha256,original_student_id,original_attempt_id) SELECT assessment_id,actor_id,action,resource_version,resource_id,resource_sha256,original_student_id,original_attempt_id FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_PREVIEW_IO_CLOSED'",e.p.assessment);
                default->throw new AssertionError();
            }
            assertThatThrownBy(e::inspect).isInstanceOf(IllegalStateException.class);
        });assertThat(e.inspect().audioDeleteCandidate()).isTrue();
    }
    @Test void actualParentTransactionMakesConcurrentInspectorWaitForNewOpenEvent()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=e.p.f.m.f.f.ends.q.f.tx;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var writer=pool.submit(()->tx.execute(s->{var io=open();entered.countDown();try{assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}return io;}));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var reader=pool.submit(e::inspect);
            try{assertThatThrownBy(()->reader.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
            var io=writer.get(10,TimeUnit.SECONDS);assertThat(reader.get(10,TimeUnit.SECONDS).audioDeleteCandidate()).isFalse();e.p.service.endAudioIo(io);
        }finally{release.countDown();}
    }
}
