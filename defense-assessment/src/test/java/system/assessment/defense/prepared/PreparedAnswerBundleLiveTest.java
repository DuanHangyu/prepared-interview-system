package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.storage.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual MySQL atomic bundle/link/receipt integration; private cloud storage is an explicitly labelled double. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAnswerBundleLiveTest {
    final PreparedAnswerUploadLiveTest f=new PreparedAnswerUploadLiveTest();
    PreparedAnswerBundleService service;
    @BeforeEach void setup()throws Exception {
        f.setup();service=service();
        when(f.store.putAnswerBundle(any(),any())).thenAnswer(i->{var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerBundle.class);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(Files.readAllBytes(i.getArgument(1))).isEqualTo(f.bytes);return new PreparedPrivateOssStore.StoredBundle(scope,scope.key("prepared-fixture"));});
    }
    PreparedAnswerBundleService service(){return new PreparedAnswerBundleService(f.uploads,new PreparedAnswerBundleRepository(f.ends.q.f.jdbc),new PreparedAnswerUploadRepository(f.ends.q.f.jdbc),f.store,f.ends.q.f.tx.getTransactionManager(),
        new java.time.Clock(){public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public java.time.Clock withZone(java.time.ZoneId z){return this;}public java.time.Instant instant(){return f.ends.q.now.get();}});}
    @AfterEach void cleanup(){f.ends.q.f.jdbc.update("DELETE FROM prepared_answer_chunk WHERE assessment_id=?",f.ends.q.f.assessment);f.ends.q.f.jdbc.update("DELETE FROM prepared_answer_upload_bundle WHERE assessment_id=?",f.ends.q.f.assessment);f.cleanup();}
    PreparedAnswerBundle.Command command()throws Exception {return new PreparedAnswerBundle.Command(f.ends.q.client,f.segment,1,"audio/webm;codecs=opus",List.of(
        new PreparedAnswerBundle.Chunk(0,2,f.hash(new byte[]{1,2})),new PreparedAnswerBundle.Chunk(1,3,f.hash(new byte[]{3,4,5}))),5,f.hash(f.bytes));}
    PreparedAnswerBundle.Summary prepare(PreparedAnswerBundle.Command command){return service.prepare(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId(),command);}
    PreparedAnswerBundle.Summary upload(String id,byte[] bytes){return service.upload(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId(),id,new ByteArrayInputStream(bytes));}
    void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);}
    @Test void authorizedMissingBatchCanRecoverOriginalPrepareWithoutConflatingResourcePermission()throws Exception {
        var cmd=command();var id=cmd.scope(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId()).id();
        assertThatThrownBy(()->service.status(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId(),id)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(404);
        assertThat(f.ends.count("prepared_answer_upload_bundle")).isZero();verifyNoInteractions(f.store);
        assertThatThrownBy(()->service.status(f.ends.q.student+1,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId(),id)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        var original=prepare(cmd);assertThat(service.status(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId(),id)).isEqualTo(original);
    }
    @Test void fullThirtyTwoChunkGroupUsesBoundedDatabaseOperationsAndKeepsOriginalAtomicReceipts()throws Exception {
        f.bytes=new byte[32];var logical=new ArrayList<PreparedAnswerBundle.Chunk>();
        for(int index=0;index<32;index++){f.bytes[index]=(byte)(index+1);logical.add(new PreparedAnswerBundle.Chunk(index,1,f.hash(new byte[]{f.bytes[index]})));}
        var cmd=new PreparedAnswerBundle.Command(f.ends.q.client,f.segment,1,"audio/webm;codecs=opus",logical,32,f.hash(f.bytes));
        var observed=spy(f.ends.q.f.jdbc);
        service=new PreparedAnswerBundleService(f.uploads,new PreparedAnswerBundleRepository(f.ends.q.f.jdbc),new PreparedAnswerUploadRepository(observed),f.store,f.ends.q.f.tx.getTransactionManager(),
            new java.time.Clock(){public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public java.time.Clock withZone(java.time.ZoneId z){return this;}public java.time.Instant instant(){return f.ends.q.now.get();}});
        var pending=prepare(cmd);assertThat(pending.confirmed()).isFalse();
        assertThat(databaseOperations(observed)).as("one bounded reservation, not an aggregate/select per logical callback").isLessThanOrEqualTo(15);
        clearInvocations(observed);var result=upload(pending.bundleId(),f.bytes);
        assertThat(result.receipts()).hasSize(32);assertThat(result.receipts().stream().map(PreparedAnswerUpload.Receipt::confirmedAt).distinct().count()).isEqualTo(1);
        assertThat(databaseOperations(observed)).as("atomic group confirm, not an update+read for every piece").isLessThanOrEqualTo(20);
        assertThat(prepare(cmd)).isEqualTo(result);verify(f.store,times(1)).putAnswerBundle(any(),any());
    }
    static long databaseOperations(org.springframework.jdbc.core.JdbcTemplate observed){
        return mockingDetails(observed).getInvocations().stream().filter(i->{
            var types=i.getMethod().getParameterTypes();boolean executes=types.length==3&&types[1]==org.springframework.jdbc.core.PreparedStatementSetter.class&&types[2]==org.springframework.jdbc.core.ResultSetExtractor.class
                    ||types.length==2&&(types[1]==org.springframework.jdbc.core.PreparedStatementSetter.class||types[1]==org.springframework.jdbc.core.BatchPreparedStatementSetter.class);
            return executes&&i.getArguments()[0] instanceof String sql&&(sql.contains("prepared_answer_chunk")||sql.contains("prepared_answer_segment"));
        }).count();
    }
    @Test void existingHeaderWithMissingChunkIsNotSilentlyRecreatedByOriginalPrepare()throws Exception {
        var cmd=command();var original=prepare(cmd);
        f.ends.q.f.jdbc.update("DELETE FROM prepared_answer_chunk WHERE assessment_id=? AND student_id=? AND answer_id=? AND bundle_id=? AND chunk_index=1",f.ends.q.f.assessment,f.ends.q.student,f.grant.answerId(),original.bundleId());
        assertThatThrownBy(()->prepare(cmd)).isInstanceOf(IllegalStateException.class);
        assertThat(f.ends.count("prepared_answer_chunk")).isEqualTo(1);assertThat(f.ends.count("prepared_answer_upload_bundle")).isEqualTo(1);verifyNoInteractions(f.store);
    }
    @Test void metadataIsNotSavedAndOneBundleAtomicallyConfirmsTwoOriginalChunks()throws Exception {
        var original=prepare(command());assertThat(original.confirmed()).isFalse();assertThat(original.receipts()).isEmpty();verifyNoInteractions(f.store);
        var result=upload(original.bundleId(),f.bytes);assertThat(result.confirmed()).isTrue();assertThat(result.receipts()).hasSize(2);
        assertThat(f.uploads.receipts(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId())).isEqualTo(result.receipts());
        assertThat(f.ends.q.f.jdbc.queryForObject("SELECT state FROM prepared_answer WHERE id=?",String.class,f.grant.answerId())).isEqualTo("RECORDING");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result)).doesNotContain("objectKey","offset","serverSaved",f.ends.q.client);
        verify(f.store,times(1)).putAnswerBundle(any(),any());
    }
    @Test void responseLossFreshServiceAndHundredRetriesReuseOneOriginalGroupAndReceipts()throws Exception {
        var original=prepare(command());var saved=upload(original.bundleId(),f.bytes);service=service();
        for(int i=0;i<100;i++){assertThat(prepare(command())).isEqualTo(saved);assertThat(upload(original.bundleId(),f.bytes)).isEqualTo(saved);}
        assertThat(f.ends.count("prepared_answer_upload_bundle")).isEqualTo(1);assertThat(f.ends.count("prepared_answer_chunk")).isEqualTo(2);verify(f.store,times(1)).putAnswerBundle(any(),any());
    }
    @Test void originalGroupCannotBeRegroupedOrChangedAndSingleUploadCannotBypassIt()throws Exception {
        var original=prepare(command());
        var cmd=command();conflict(()->prepare(new PreparedAnswerBundle.Command(cmd.captureClientId(),cmd.segmentId(),1,cmd.mimeType(),List.of(cmd.chunks().getFirst()),2,cmd.chunks().getFirst().sha256())));
        conflict(()->f.upload(new PreparedAnswerUpload.Command(cmd.captureClientId(),cmd.segmentId(),1,cmd.mimeType(),0,2,cmd.chunks().getFirst().sha256()),new byte[]{1,2}));
        assertThat(upload(original.bundleId(),f.bytes).confirmed()).isTrue();assertThat(f.ends.count("prepared_answer_upload_bundle")).isEqualTo(1);
    }
    @Test void existingSingleReservationCannotBeReinterpretedAsABundle()throws Exception {
        doThrow(new IOException("ambiguous storage")).when(f.store).putAnswerChunk(any(),any());
        var cmd=command();assertThatThrownBy(()->f.upload(new PreparedAnswerUpload.Command(cmd.captureClientId(),cmd.segmentId(),1,cmd.mimeType(),0,2,cmd.chunks().getFirst().sha256()),new byte[]{1,2})).isInstanceOf(BusinessException.class);
        conflict(()->prepare(cmd));assertThat(f.ends.count("prepared_answer_upload_bundle")).isZero();
    }
    @Test void failedStorageOrWrongActualChunkBytesNeverConfirmAnyPart()throws Exception {
        var original=prepare(command());assertThatThrownBy(()->upload(original.bundleId(),new byte[5])).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);verifyNoInteractions(f.store);
        doThrow(new IOException("private vendor body")).when(f.store).putAnswerBundle(any(),any());
        assertThatThrownBy(()->upload(original.bundleId(),f.bytes)).isInstanceOf(BusinessException.class).hasMessageNotContaining("vendor").extracting("code").isEqualTo(503);
        assertThat(service.status(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId(),original.bundleId()).receipts()).isEmpty();
        assertThat(f.ends.count("prepared_answer_chunk")).isEqualTo(2);
    }
    @Test void currentRoleIsRecheckedAfterExternalIOAndForeignAccountCannotRead()throws Exception {
        var original=prepare(command());
        assertThatThrownBy(()->service.status(f.ends.q.student+1,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId(),original.bundleId())).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        doAnswer(i->{f.ends.q.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.ends.q.student);var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerBundle.class);return new PreparedPrivateOssStore.StoredBundle(scope,scope.key("prepared-fixture"));}).when(f.store).putAnswerBundle(any(),any());
        try{assertThatThrownBy(()->upload(original.bundleId(),f.bytes)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}
        finally{f.ends.q.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.ends.q.student);}
        assertThat(f.uploads.receipts(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId())).isEmpty();
    }
    @Test void originalEndMustIncludeAllBatchReservationsAndAllowsOnlyMatchingLateBytes()throws Exception {
        var original=prepare(command());conflict(()->f.ends.end(f.grant.answerId(),f.ends.command(f.ends.manifest())));
        var cmd=command();var manifest=new PreparedAnswerEnd.Manifest(1,List.of(new PreparedAnswerEnd.Segment(cmd.segmentId(),1,cmd.mimeType(),1000L,2000L,"SEALED",null,2,5,cmd.chunks().stream().map(c->new PreparedAnswerEnd.Chunk(c.index(),c.byteSize(),c.sha256())).toList())));
        f.ends.end(f.grant.answerId(),f.ends.command(manifest));assertThat(upload(original.bundleId(),f.bytes).confirmed()).isTrue();
        assertThat(f.ends.q.f.jdbc.queryForObject("SELECT state FROM prepared_answer WHERE id=?",String.class,f.grant.answerId())).isEqualTo("UPLOAD_PENDING");
    }
    @Test void outerTransactionCannotHoldLocksThroughoutBundleIO()throws Exception {
        var cmd=command();assertThatThrownBy(()->f.ends.q.f.tx.execute(s->prepare(cmd))).isInstanceOf(IllegalStateException.class);verifyNoInteractions(f.store);
    }
    @Test void corruptedOffsetsCannotReportAConfirmedOriginalReceipt()throws Exception {
        var original=prepare(command());upload(original.bundleId(),f.bytes);
        f.ends.q.f.jdbc.update("UPDATE prepared_answer_chunk SET bundle_offset=bundle_offset+1 WHERE bundle_id=?",original.bundleId());
        assertThatThrownBy(()->service.status(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId(),original.bundleId())).isInstanceOf(IllegalStateException.class);
    }
    PreparedAnswerEnd.Manifest manifest(PreparedAnswerBundle.Command cmd) {
        return new PreparedAnswerEnd.Manifest(1,List.of(new PreparedAnswerEnd.Segment(cmd.segmentId(),1,cmd.mimeType(),1000L,2000L,"SEALED",null,2,5,
            cmd.chunks().stream().map(c->new PreparedAnswerEnd.Chunk(c.index(),c.byteSize(),c.sha256())).toList())));
    }
    void finishAll()throws Exception {
        f.ends.end(f.grant.answerId(),f.ends.command(manifest(command())));
        for(int ordinal=2;ordinal<=3;ordinal++){var question=f.ends.q.grant(ordinal);f.ends.end(question.answerId(),f.ends.command(f.ends.manifest()));}
    }
    @Test void fixedWindowExpiryCommitsReviewAndKeepsOriginalReservationAndReceipts()throws Exception {
        var original=prepare(command());upload(original.bundleId(),f.bytes);finishAll();var attempt=f.ends.attempt();
        f.ends.q.now.set(java.time.Instant.parse(attempt.supplementalUploadDeadline()));conflict(()->prepare(command()));conflict(()->upload(original.bundleId(),f.bytes));
        assertThat(f.ends.attempt().state()).isEqualTo("REQUIRES_REVIEW");assertThat(f.ends.attempt().supplementalUploadDeadline()).isEqualTo(attempt.supplementalUploadDeadline());
        assertThat(service.status(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId(),original.bundleId()).confirmed()).isTrue();verify(f.store,times(1)).putAnswerBundle(any(),any());
    }
    @Test void externalCompletionAtExpiryCannotConfirmButDoesNotDeleteOriginal()throws Exception {
        var original=prepare(command());finishAll();var deadline=java.time.Instant.parse(f.ends.attempt().supplementalUploadDeadline());f.ends.q.now.set(deadline.minusMillis(1));
        doAnswer(i->{f.ends.q.now.set(deadline);var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerBundle.class);return new PreparedPrivateOssStore.StoredBundle(scope,scope.key("prepared-fixture"));}).when(f.store).putAnswerBundle(any(),any());
        conflict(()->upload(original.bundleId(),f.bytes));assertThat(f.ends.attempt().state()).isEqualTo("REQUIRES_REVIEW");
        assertThat(f.uploads.receipts(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId())).isEmpty();assertThat(f.ends.count("prepared_answer_upload_bundle")).isEqualTo(1);
    }
    @Test void confirmedRetryStillValidatesBytesAndRechecksIdentityAfterSlowRead()throws Exception {
        var original=prepare(command());upload(original.bundleId(),f.bytes);
        var input=new ByteArrayInputStream(f.bytes){boolean revoked;@Override public synchronized int read(byte[] bytes,int offset,int size){if(!revoked){revoked=true;f.ends.q.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.ends.q.student);}return super.read(bytes,offset,size);}};
        try{assertThatThrownBy(()->service.upload(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId(),original.bundleId(),input)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}
        finally{f.ends.q.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.ends.q.student);}
        verify(f.store,times(1)).putAnswerBundle(any(),any());
    }
    @Test void falseAdapterProofAndCorruptedImmutableMetadataNeverCommitReceipts()throws Exception {
        var original=prepare(command());doAnswer(i->new PreparedPrivateOssStore.StoredBundle(i.getArgument(0),"prepared/foreign/object")).when(f.store).putAnswerBundle(any(),any());
        assertThatThrownBy(()->upload(original.bundleId(),f.bytes)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        assertThat(f.uploads.receipts(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId())).isEmpty();
        f.ends.q.f.jdbc.update("UPDATE prepared_answer_upload_bundle SET command_sha256=? WHERE id=?","a".repeat(64),original.bundleId());
        assertThatThrownBy(()->service.status(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId(),original.bundleId())).isInstanceOf(IllegalStateException.class);
    }
    @Test void failedInboundStreamKeepsOriginalGroupAndSuccessUsesRemovedPrivateScratch()throws Exception {
        var original=prepare(command());var input=new InputStream(){public int read()throws IOException{throw new IOException("lost connection");}};
        assertThatThrownBy(()->service.upload(f.ends.q.student,f.ends.q.f.assessment,f.ends.q.attempt.id(),f.grant.answerId(),original.bundleId(),input)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);verifyNoInteractions(f.store);
        var scratch=new java.util.concurrent.atomic.AtomicReference<Path>();
        doAnswer(i->{scratch.set(i.getArgument(1));assertThat(Files.getPosixFilePermissions(scratch.get())).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerBundle.class);return new PreparedPrivateOssStore.StoredBundle(scope,scope.key("prepared-fixture"));}).when(f.store).putAnswerBundle(any(),any());
        assertThat(upload(original.bundleId(),f.bytes).confirmed()).isTrue();assertThat(Files.exists(scratch.get())).isFalse();
    }
    @Test void concurrentOriginalBatchCommandsProduceOneImmutableGroupAndAtomicReceiptSet()throws Exception {
        var cmd=command();var ready=new java.util.concurrent.CountDownLatch(1);var results=new ArrayList<java.util.concurrent.Future<PreparedAnswerBundle.Summary>>();
        try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<8;i++)results.add(workers.submit(()->{ready.await();var prepared=prepare(cmd);return upload(prepared.bundleId(),f.bytes);}));ready.countDown();
            var first=results.getFirst().get(15,java.util.concurrent.TimeUnit.SECONDS);for(var result:results)assertThat(result.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(f.ends.count("prepared_answer_upload_bundle")).isEqualTo(1);assertThat(f.ends.count("prepared_answer_chunk")).isEqualTo(2);
    }
}
