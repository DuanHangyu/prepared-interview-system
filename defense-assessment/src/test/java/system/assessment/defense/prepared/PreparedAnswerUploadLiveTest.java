package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.storage.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.io.*;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL two-phase receipt/ownership; the private OSS adapter is an explicit double, not real cloud. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAnswerUploadLiveTest {
    final PreparedAnswerEndLiveTest ends=new PreparedAnswerEndLiveTest();
    PreparedAnswerUploadService uploads;
    PreparedPrivateOssStore store;
    PreparedStoragePolicy policy;
    PreparedQuestionGrant.Grant grant;
    byte[] bytes={1,2,3,4,5};
    String segment=UUID.randomUUID().toString();
    String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}
    PreparedAnswerUpload.Command command()throws Exception{return new PreparedAnswerUpload.Command(ends.q.client,segment,1,"audio/webm;codecs=opus",0,bytes.length,hash(bytes));}
    @BeforeEach void setup()throws Exception {
        ends.setup();grant=ends.q.grant(1);store=mock(PreparedPrivateOssStore.class);policy=new PreparedStoragePolicy(new PreparedPrivateStorageTest().config());
        uploads=new PreparedAnswerUploadService(ends.q.f.access,new PreparedStudentEligibilityRepository(ends.q.f.jdbc),new PreparedAttemptStartRepository(ends.q.f.jdbc),
            new PreparedQuestionGrantRepository(ends.q.f.jdbc),new PreparedAnswerEndRepository(ends.q.f.jdbc),new PreparedAnswerUploadRepository(ends.q.f.jdbc),policy,store,ends.q.f.tx.getTransactionManager(),
            new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return ends.q.now.get();}});
        when(store.putAnswerChunk(any(),any())).thenAnswer(i->{var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerChunk.class);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(Files.readAllBytes(i.getArgument(1))).isEqualTo(bytes);return new PreparedPrivateOssStore.StoredChunk(scope,scope.key("prepared-fixture"));});
    }
    @AfterEach void cleanup(){ends.q.f.jdbc.update("DELETE FROM prepared_answer_chunk WHERE assessment_id=?",ends.q.f.assessment);ends.q.f.jdbc.update("DELETE FROM prepared_answer_segment WHERE assessment_id=?",ends.q.f.assessment);ends.cleanup();}
    PreparedAnswerUpload.Receipt upload(PreparedAnswerUpload.Command command,byte[] body){return uploads.upload(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),grant.answerId(),command,new ByteArrayInputStream(body));}
    @Test void receiptRequiresActualStoreProofAndDatabaseCommitButIsNotWholeAnswerSaved()throws Exception {
        var receipt=upload(command(),bytes);assertThat(receipt.confirmed()).isTrue();assertThat(receipt.answerId()).isEqualTo(grant.answerId());
        assertThat(receipt.sha256()).isEqualTo(hash(bytes));assertThat(receipt.byteSize()).isEqualTo(bytes.length);
        assertThat(ends.q.f.jdbc.queryForObject("SELECT state FROM prepared_answer WHERE id=?",String.class,grant.answerId())).isEqualTo("RECORDING");
        assertThat(uploads.receipts(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),grant.answerId())).containsExactly(receipt);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(receipt)).doesNotContain("objectKey","url","serverSaved",ends.q.client);
    }
    @Test void oneHundredOriginalRetriesReuseOneReceiptAndDoNotRepeatCloudWrite()throws Exception {
        var command=command();var first=upload(command,bytes);ends.q.now.set(ends.q.now.get().plusSeconds(60));
        for(int i=0;i<100;i++)assertThat(upload(command,bytes)).isEqualTo(first);verify(store,times(1)).putAnswerChunk(any(),any());
        assertThat(ends.count("prepared_answer_chunk")).isEqualTo(1);
    }
    @Test void sameSequenceDifferentClaimOrActualBodyConflictsAndCannotOverwrite()throws Exception {
        var command=command();upload(command,bytes);
        assertThatThrownBy(()->upload(new PreparedAnswerUpload.Command(command.captureClientId(),segment,1,command.mimeType(),0,5,"a".repeat(64)),bytes)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThatThrownBy(()->upload(command,new byte[5])).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        verify(store,times(1)).putAnswerChunk(any(),any());
    }
    @Test void failedOrAmbiguousStorageKeepsReservationAndOriginalBytesCanBeRetried()throws Exception {
        doThrow(new IOException("secret vendor URL")).when(store).putAnswerChunk(any(),any());
        assertThatThrownBy(()->upload(command(),bytes)).isInstanceOf(BusinessException.class).hasMessageNotContaining("secret").extracting("code").isEqualTo(503);
        assertThat(ends.count("prepared_answer_chunk")).isEqualTo(1);assertThat(uploads.receipts(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),grant.answerId())).isEmpty();
        doAnswer(i->{var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerChunk.class);return new PreparedPrivateOssStore.StoredChunk(scope,scope.key("prepared-fixture"));}).when(store).putAnswerChunk(any(),any());
        assertThat(upload(command(),bytes).confirmed()).isTrue();
    }
    @Test void declaredOriginalManifestRestrictsEveryLaterChunkAndDoesNotGrantFreshRecording()throws Exception {
        var command=command();var manifest=new PreparedAnswerEnd.Manifest(1,List.of(new PreparedAnswerEnd.Segment(segment,1,command.mimeType(),1000L,2000L,"SEALED",null,1,5,List.of(new PreparedAnswerEnd.Chunk(0,5,command.sha256())))));
        ends.end(grant.answerId(),ends.command(manifest));assertThat(upload(command,bytes).confirmed()).isTrue();
        assertThatThrownBy(()->upload(new PreparedAnswerUpload.Command(command.captureClientId(),segment,1,command.mimeType(),1,5,command.sha256()),bytes)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(ends.q.f.jdbc.queryForObject("SELECT state FROM prepared_answer WHERE id=?",String.class,grant.answerId())).isEqualTo("UPLOAD_PENDING");
    }
    @Test void revokedIdentityDuringCloudIOCannotCommitAReceiptAndDoesNotHoldParentLock()throws Exception {
        doAnswer(i->{ends.q.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",ends.q.student);var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerChunk.class);return new PreparedPrivateOssStore.StoredChunk(scope,scope.key("prepared-fixture"));}).when(store).putAnswerChunk(any(),any());
        assertThatThrownBy(()->upload(command(),bytes)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThat(ends.q.f.jdbc.queryForObject("SELECT confirmed_at FROM prepared_answer_chunk WHERE assessment_id=?",java.sql.Timestamp.class,ends.q.f.assessment)).isNull();
        ends.q.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",ends.q.student);
    }
    @Test void foreignAnswerClientOrAccountAndOversizedActualBodiesNeverReachStorage()throws Exception {
        var command=command();
        assertThatThrownBy(()->uploads.upload(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),UUID.randomUUID().toString(),command,new ByteArrayInputStream(bytes))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThatThrownBy(()->uploads.upload(ends.q.student+1,ends.q.f.assessment,ends.q.attempt.id(),grant.answerId(),command,new ByteArrayInputStream(bytes))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThatThrownBy(()->upload(new PreparedAnswerUpload.Command(UUID.randomUUID().toString(),segment,1,command.mimeType(),0,5,command.sha256()),bytes)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThatThrownBy(()->upload(command,new byte[6])).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);verifyNoInteractions(store);
    }
    @Test void previouslyConfirmedChunkStillRechecksIdentityAfterReadingSlowRetryBody()throws Exception {
        upload(command(),bytes);
        var body=new ByteArrayInputStream(bytes){boolean revoked;
            @Override public synchronized int read(byte[] b,int off,int length){if(!revoked){revoked=true;ends.q.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",ends.q.student);}return super.read(b,off,length);}};
        assertThatThrownBy(()->uploads.upload(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),grant.answerId(),command(),body)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        ends.q.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",ends.q.student);
    }
    PreparedAnswerEnd.Manifest manifest(PreparedAnswerUpload.Command command) {
        return new PreparedAnswerEnd.Manifest(1,List.of(new PreparedAnswerEnd.Segment(command.segmentId(),command.segmentOrdinal(),command.mimeType(),1000L,2000L,"SEALED",null,1,command.byteSize(),List.of(new PreparedAnswerEnd.Chunk(command.index(),command.byteSize(),command.sha256())))));
    }
    void finishAll()throws Exception {
        ends.end(grant.answerId(),ends.command(manifest(command())));
        for(int ordinal=2;ordinal<=3;ordinal++){var question=ends.q.grant(ordinal);ends.end(question.answerId(),ends.command(ends.manifest()));}
    }
    @Test void endCannotDiscardOrChangeOriginalReservedOrConfirmedBytes()throws Exception {
        var command=command();upload(command,bytes);
        assertThatThrownBy(()->ends.end(grant.answerId(),ends.command(ends.manifest()))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(ends.count("prepared_answer_end")).isZero();assertThat(ends.end(grant.answerId(),ends.command(manifest(command))).serverSaved()).isFalse();
    }
    @Test void fixed24HourExpiryCommitsReviewWithoutDeletingBytesOrExtendingWindow()throws Exception {
        upload(command(),bytes);finishAll();var attempt=ends.attempt();ends.q.now.set(Instant.parse(attempt.supplementalUploadDeadline()));
        assertThatThrownBy(()->upload(command(),bytes)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(ends.attempt().state()).isEqualTo("REQUIRES_REVIEW");assertThat(ends.attempt().supplementalUploadDeadline()).isEqualTo(attempt.supplementalUploadDeadline());
        assertThat(uploads.receipts(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),grant.answerId())).hasSize(1);
        verify(store,times(1)).putAnswerChunk(any(),any());
    }
    @Test void cloudCompletionAfterFixedWindowPreservesObjectButCannotConfirmWithoutApproval()throws Exception {
        finishAll();var deadline=Instant.parse(ends.attempt().supplementalUploadDeadline());ends.q.now.set(deadline.minusMillis(1));
        doAnswer(i->{ends.q.now.set(deadline);var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerChunk.class);return new PreparedPrivateOssStore.StoredChunk(scope,scope.key("prepared-fixture"));}).when(store).putAnswerChunk(any(),any());
        assertThatThrownBy(()->upload(command(),bytes)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(ends.attempt().state()).isEqualTo("REQUIRES_REVIEW");assertThat(uploads.receipts(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),grant.answerId())).isEmpty();
        assertThat(ends.count("prepared_answer_chunk")).isEqualTo(1);
    }
    @Test void noWholeOrIdleLimitIsInventedForUploadingOriginalCurrentAnswer()throws Exception {
        ends.q.now.set(ends.q.now.get().plusSeconds(86400*20));assertThat(upload(command(),bytes).confirmed()).isTrue();
        assertThat(ends.attempt().answeringEndedAt()).isNull();assertThat(ends.attempt().supplementalUploadDeadline()).isNull();assertThat(ends.q.grants.current(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),ends.q.client).orElseThrow().mayRecord()).isFalse();
    }
    @Test void deploymentChangeOrFalseAdapterProofNeverCommitsAReceipt()throws Exception {
        doAnswer(i->{var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerChunk.class);return new PreparedPrivateOssStore.StoredChunk(scope,"prepared/other/object");}).when(store).putAnswerChunk(any(),any());
        assertThatThrownBy(()->upload(command(),bytes)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        assertThat(uploads.receipts(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),grant.answerId())).isEmpty();
    }
    @Test void outerTransactionAndInvalidClaimsAreRefusedBeforeIOOrReservation()throws Exception {
        var command=command();assertThatThrownBy(()->ends.q.f.tx.execute(status->upload(command,bytes))).isInstanceOf(IllegalStateException.class);verifyNoInteractions(store);
        for(int ordinal:new int[]{0,1001})assertThatThrownBy(()->upload(new PreparedAnswerUpload.Command(command.captureClientId(),segment,ordinal,command.mimeType(),0,5,command.sha256()),bytes)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ends.count("prepared_answer_chunk")).isZero();
    }
    @Test void concurrentSameChunkHasOneDurableReceiptEvenIfStorageRunsMoreThanOnce()throws Exception {
        var command=command();var ready=new java.util.concurrent.CountDownLatch(1);var results=new ArrayList<java.util.concurrent.Future<PreparedAnswerUpload.Receipt>>();
        try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<8;i++)results.add(workers.submit(()->{ready.await();return upload(command,bytes);}));ready.countDown();
            var first=results.getFirst().get(10,java.util.concurrent.TimeUnit.SECONDS);for(var result:results)assertThat(result.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(ends.count("prepared_answer_chunk")).isEqualTo(1);assertThat(ends.count("prepared_answer_segment")).isEqualTo(1);
    }
    @Test void reorderedChunkReceiptsAndMimeOrdinalConflictsPreserveOriginalSegment()throws Exception {
        var command=command();var second=new PreparedAnswerUpload.Command(command.captureClientId(),segment,1,command.mimeType(),1,5,command.sha256());
        upload(second,bytes);upload(command,bytes);assertThat(uploads.receipts(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),grant.answerId()).stream().map(PreparedAnswerUpload.Receipt::index)).containsExactly(0,1);
        for(var changed:List.of(new PreparedAnswerUpload.Command(command.captureClientId(),segment,2,command.mimeType(),2,5,command.sha256()),new PreparedAnswerUpload.Command(command.captureClientId(),segment,1,"audio/mp4",2,5,command.sha256()),new PreparedAnswerUpload.Command(command.captureClientId(),UUID.randomUUID().toString(),1,command.mimeType(),0,5,command.sha256())))
            assertThatThrownBy(()->upload(changed,bytes)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(ends.count("prepared_answer_chunk")).isEqualTo(2);
    }
    @Test void parentAndImmutableScopeConstraintsDoNotPermitForeignSegmentReuse()throws Exception {
        var command=command();upload(command,bytes);ends.end(grant.answerId(),ends.command(manifest(command)));
        var second=ends.q.grant(2);String originalAnswer=grant.answerId();grant=second;
        assertThatThrownBy(()->upload(command,bytes)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(uploads.receipts(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),originalAnswer)).hasSize(1);
        assertThat(uploads.receipts(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),grant.answerId())).isEmpty();
    }
    @Test void missingOrUnusableStorageConfigurationDoesNotCreateAReservation()throws Exception {
        var disabled=mock(PreparedStoragePolicy.class);when(disabled.snapshot()).thenThrow(new IllegalArgumentException("private key"));
        var service=new PreparedAnswerUploadService(ends.q.f.access,new PreparedStudentEligibilityRepository(ends.q.f.jdbc),new PreparedAttemptStartRepository(ends.q.f.jdbc),new PreparedQuestionGrantRepository(ends.q.f.jdbc),new PreparedAnswerEndRepository(ends.q.f.jdbc),new PreparedAnswerUploadRepository(ends.q.f.jdbc),disabled,store,ends.q.f.tx.getTransactionManager(),Clock.systemUTC());
        assertThatThrownBy(()->service.upload(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),grant.answerId(),command(),new ByteArrayInputStream(bytes))).isInstanceOf(BusinessException.class).hasMessageNotContaining("private key").extracting("code").isEqualTo(503);
        assertThat(ends.count("prepared_answer_chunk")).isZero();verifyNoInteractions(store);
    }
    @Test void aggregateReservationLimitIncludesPendingOriginalBytesAndNeverBuffersWholeAnswer()throws Exception {
        var repository=new PreparedAnswerUploadRepository(ends.q.f.jdbc);var command=command();
        ends.q.f.tx.execute(status->{new PreparedAttemptStartRepository(ends.q.f.jdbc).lockAssessment(ends.q.f.assessment);
            for(int index=0;index<32;index++)repository.reserve(new PreparedPrivateOssStore.AnswerChunk(ends.q.f.assessment,ends.q.student,ends.q.attempt.id(),grant.answerId(),segment,index,2097152,command.sha256(),command.mimeType()),1,"prepared-fixture",ends.q.now.get());return null;});
        var extra=new PreparedAnswerUpload.Command(command.captureClientId(),segment,1,command.mimeType(),32,5,command.sha256());
        assertThatThrownBy(()->upload(extra,bytes)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(413);
        assertThat(ends.count("prepared_answer_chunk")).isEqualTo(32);verifyNoInteractions(store);
    }
    @Test void failedInboundStreamKeepsReservationAndSuccessfulScratchFileIsPrivateAndRemoved()throws Exception {
        var command=command();var broken=new InputStream(){@Override public int read()throws IOException{throw new IOException("network interrupted");}};
        assertThatThrownBy(()->uploads.upload(ends.q.student,ends.q.f.assessment,ends.q.attempt.id(),grant.answerId(),command,broken)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);verifyNoInteractions(store);
        var path=new java.util.concurrent.atomic.AtomicReference<java.nio.file.Path>();
        doAnswer(i->{path.set(i.getArgument(1));assertThat(Files.getPosixFilePermissions(path.get())).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerChunk.class);return new PreparedPrivateOssStore.StoredChunk(scope,scope.key("prepared-fixture"));}).when(store).putAnswerChunk(any(),any());
        assertThat(upload(command,bytes).confirmed()).isTrue();assertThat(Files.exists(path.get())).isFalse();
    }
}
