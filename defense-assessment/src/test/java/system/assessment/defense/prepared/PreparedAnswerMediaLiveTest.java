package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.jobs.PreparedJobLedger;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL lease/receipt and real local codec; cloud bytes are an explicitly labelled adapter double. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAnswerMediaLiveTest {
    final PreparedAnswerBundleLiveTest f=new PreparedAnswerBundleLiveTest();
    @TempDir Path folder;
    PreparedAnswerMediaService service;PreparedAnswerMediaRuntime runtime;PreparedJobLedger jobs;
    @BeforeEach void setup()throws Exception {
        folder=folder.toRealPath();f.setup();var jdbc=f.f.ends.q.f.jdbc;
        jobs=new PreparedJobLedger(jdbc,f.f.ends.q.f.tx.getTransactionManager());
        runtime=new PreparedAnswerMediaRuntime(new MockEnvironment().withProperty("prepared.answer-media.worker-enabled","true")
            .withProperty("prepared.answer-media.spool-root",folder.resolve("prepared-answer-spool").toString())
            .withProperty("prepared.answer-media.ffmpeg",Path.of("/Users/duanhangyu/.homebrew/bin/ffmpeg").toRealPath().toString())
            .withProperty("prepared.answer-media.ffprobe",Path.of("/Users/duanhangyu/.homebrew/bin/ffprobe").toRealPath().toString()));
        service=new PreparedAnswerMediaService(f.f.uploads,new PreparedAnswerUploadRepository(jdbc),new PreparedAnswerBundleRepository(jdbc),jobs,runtime,f.f.policy,jdbc,f.f.ends.q.f.tx.getTransactionManager());
    }
    @AfterEach void cleanup(){var jdbc=f.f.ends.q.f.jdbc;jdbc.update("DELETE FROM prepared_supplement_grant WHERE assessment_id=?",f.f.ends.q.f.assessment);jdbc.update("DELETE FROM prepared_answer_media WHERE assessment_id=?",f.f.ends.q.f.assessment);jdbc.update("DELETE FROM prepared_job WHERE assessment_id=? AND kind='ANSWER_MEDIA'",f.f.ends.q.f.assessment);f.cleanup();}
    PreparedAnswerMediaService.Summary request(String hash){return service.request(f.f.ends.q.student,f.f.ends.q.f.assessment,f.f.ends.q.attempt.id(),f.f.grant.answerId(),new PreparedAnswerMediaService.Command(f.f.ends.q.client,hash));}
    String end()throws Exception {
        var cmd=f.command();var prepared=f.prepare(cmd);f.upload(prepared.bundleId(),f.f.bytes);
        return f.f.ends.end(f.f.grant.answerId(),f.f.ends.command(f.manifest(cmd))).manifestSha256();
    }
    PreparedAnswerMediaService.Summary status(){return service.status(f.f.ends.q.student,f.f.ends.q.f.assessment,f.f.ends.q.attempt.id(),f.f.grant.answerId());}
    void finishOriginalQuestions(boolean expire)throws Exception {
        PreparedAnswerEnd.Command last=null;String lastAnswer=null;
        for(int ordinal=2;ordinal<=3;ordinal++){var next=f.f.ends.q.grant(ordinal);last=f.f.ends.command(f.f.ends.manifest());lastAnswer=next.answerId();f.f.ends.end(lastAnswer,last);}
        if(expire){f.f.ends.q.now.set(java.time.Instant.parse(f.f.ends.attempt().supplementalUploadDeadline()));f.f.ends.end(lastAnswer,last);}
    }
    PreparedSupplement.Receipt approveOriginal(){
        var q=f.f.ends.q;var approval=new PreparedSupplementService(q.f.access,new PreparedAttemptStartRepository(q.f.jdbc),f.f.uploads,new PreparedAnswerEndRepository(q.f.jdbc),q.f.jdbc,q.f.tx.getTransactionManager(),
            new java.time.Clock(){public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public java.time.Clock withZone(java.time.ZoneId zone){return this;}public java.time.Instant instant(){return q.now.get();}});
        var current=approval.status(q.f.actor,q.student,q.f.assessment,q.attempt.id(),f.f.grant.answerId());
        return approval.approve(q.f.actor,q.student,q.f.assessment,q.attempt.id(),f.f.grant.answerId(),new PreparedSupplement.Command(UUID.randomUUID().toString(),current.approvalRevision(),current.attemptRevision(),current.manifestSha256(),q.now.get().plusSeconds(3600).toString(),"核验原录音任务，允许补传而不重答"));
    }
    PreparedAnswerMediaWorker worker(){return new PreparedAnswerMediaWorker(jobs,service,runtime,f.f.store,f.f.policy);}
    @Test void queuedWindowExpiryWaitsAndOneApprovedOriginalJobSurvivesConcurrentResumeAndActualDecoding()throws Exception {
        var hash=nativeEnd();stagedCloudDouble();var queued=request(hash);finishOriginalQuestions(true);var original=f.f.ends.attempt();
        worker().runOnce();assertThat(status().phase()).isEqualTo("WAITING_APPROVAL");assertThat(status().serverSaved()).isFalse();
        assertThatThrownBy(()->request(hash)).isInstanceOf(PreparedUploadWindow.Expired.class);verify(f.f.store,never()).downloadAnswerBundle(any(),any());
        var approved=approveOriginal();var barrier=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var results=new ArrayList<java.util.concurrent.Future<PreparedAnswerMediaService.Summary>>();
            for(int n=0;n<8;n++)results.add(pool.submit(()->{barrier.await();return request(hash);}));barrier.countDown();
            for(var result:results){var resumed=result.get(15,java.util.concurrent.TimeUnit.SECONDS);assertThat(resumed.jobId()).isEqualTo(queued.jobId());assertThat(resumed.phase()).isEqualTo("QUEUED");}
        }
        var jdbc=f.f.ends.q.f.jdbc;assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='ANSWER_MEDIA_RESUMED'",Integer.class,f.f.ends.q.f.assessment)).isEqualTo(1);
        assertThat(jobs.fail(new PreparedJobLedger.Lease(jobs.get(queued.jobId()).orElseThrow(),1,"answer-media-worker"),"ANSWER_MEDIA_INVALID",false)).isFalse();
        worker().runOnce();assertThat(status().phase()).isEqualTo("SAVED");assertThat(status().jobId()).isEqualTo(queued.jobId());
        assertThat(f.f.ends.count("prepared_answer_media")).isEqualTo(1);assertThat(f.f.ends.count("prepared_answer_end")).isEqualTo(3);assertThat(f.f.ends.count("prepared_answer_chunk")).isEqualTo(2);
        assertThat(f.f.ends.attempt().answeringEndedAt()).isEqualTo(original.answeringEndedAt());assertThat(f.f.ends.attempt().supplementalUploadDeadline()).isEqualTo(original.supplementalUploadDeadline());
        assertThat(f.f.ends.q.grants.current(f.f.ends.q.student,f.f.ends.q.f.assessment,f.f.ends.q.attempt.id(),f.f.ends.q.client).orElseThrow().mayRecord()).isFalse();
        assertThat(approved.manifestSha256()).isEqualTo(hash);verify(f.f.store,times(1)).putAnswer(any(),any());
    }
    @Test void expiryAfterNativeDecodingAndPrivatePutCannotSaveButNewApprovalRecoversTheSameOriginalTask()throws Exception {
        var hash=nativeEnd();stagedCloudDouble();var queued=request(hash);finishOriginalQuestions(false);var original=f.f.ends.attempt();
        var expireOnce=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(i->{if(expireOnce.getAndSet(false))f.f.ends.q.now.set(java.time.Instant.parse(original.supplementalUploadDeadline()));var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerFile.class);return new PreparedPrivateOssStore.StoredAnswer(scope,scope.key("prepared-fixture"),system.assessment.defense.prepared.media.PreparedWaveAudio.inspect(i.getArgument(1)));}).when(f.f.store).putAnswer(any(),any());
        worker().runOnce();assertThat(status().phase()).isEqualTo("WAITING_APPROVAL");assertThat(status().serverSaved()).isFalse();
        assertThat(f.f.ends.q.f.jdbc.queryForObject("SELECT proof_json FROM prepared_answer_media WHERE answer_id=?",String.class,f.f.grant.answerId())).isNull();
        var first=approveOriginal();assertThat(request(hash).jobId()).isEqualTo(queued.jobId());
        f.f.ends.q.now.set(java.time.Instant.parse(first.uploadDeadline()));worker().runOnce();assertThat(status().phase()).isEqualTo("WAITING_APPROVAL");
        var second=approveOriginal();assertThat(second.approvalRevision()).isEqualTo(2);for(int n=0;n<20;n++)assertThat(request(hash).jobId()).isEqualTo(queued.jobId());
        worker().runOnce();assertThat(status().serverSaved()).isTrue();assertThat(status().jobId()).isEqualTo(queued.jobId());
        assertThat(f.f.ends.count("prepared_submission")).isZero();assertThat(f.f.ends.count("prepared_supplement_grant")).isEqualTo(2);assertThat(f.f.ends.count("prepared_answer_media")).isEqualTo(1);
        verify(f.f.store,times(2)).putAnswer(any(),any());
    }
    @Test void approvalNeverResetsNonExpiryMediaDamageOrInventsAWholeMediaReceipt()throws Exception {
        var hash=end();stagedCloudDouble();var queued=request(hash);worker().runOnce();assertThat(status().phase()).isEqualTo("NEEDS_REVIEW");
        finishOriginalQuestions(true);approveOriginal();var jdbc=f.f.ends.q.f.jdbc;
        for(String error:List.of("ANSWER_MEDIA_INVALID","ANSWER_MEDIA_BINDING_INVALID","ANSWER_MEDIA_SAVE_FAILED","AMBIGUOUS_CALL")){
            jdbc.update("UPDATE prepared_job SET error_code=? WHERE id=?",error,queued.jobId());assertThat(request(hash).phase()).isEqualTo("NEEDS_REVIEW");
            assertThat(jobs.get(queued.jobId()).orElseThrow().attempts()).isEqualTo(1);
        }
        assertThat(status().serverSaved()).isFalse();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='ANSWER_MEDIA_RESUMED'",Integer.class,f.f.ends.q.f.assessment)).isZero();
        verify(f.f.store,never()).putAnswer(any(),any());
    }
    @Test void expiredOriginalRecoveryRechecksRoleClientManifestGrantAndFreeJobIntegrity()throws Exception {
        var hash=nativeEnd();var queued=request(hash);finishOriginalQuestions(true);worker().runOnce();assertThat(status().phase()).isEqualTo("WAITING_APPROVAL");var grant=approveOriginal();var q=f.f.ends.q;var jdbc=q.f.jdbc;
        jdbc.update("UPDATE `user` SET type=1 WHERE id=?",q.student);try{assertThatThrownBy(()->request(hash)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}finally{jdbc.update("UPDATE `user` SET type=0 WHERE id=?",q.student);}
        assertThatThrownBy(()->service.request(q.student,q.f.assessment,q.attempt.id(),f.f.grant.answerId(),new PreparedAnswerMediaService.Command(UUID.randomUUID().toString(),hash))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThatThrownBy(()->request("b".repeat(64))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        String original=jdbc.queryForObject("SELECT request_sha256 FROM prepared_supplement_grant WHERE id=?",String.class,grant.id());jdbc.update("UPDATE prepared_supplement_grant SET request_sha256=REPEAT('b',64) WHERE id=?",grant.id());
        try{assertThatThrownBy(()->request(hash)).isInstanceOf(IllegalStateException.class);}finally{jdbc.update("UPDATE prepared_supplement_grant SET request_sha256=? WHERE id=?",original,grant.id());}
        for(String damage:List.of("external_sent=1","output_json='{}'","lease_owner='not-a-free-expired-task'","request_sha256=REPEAT('f',64)"))
            q.f.tx.executeWithoutResult(s->{jdbc.update("UPDATE prepared_job SET "+damage+" WHERE id=?",queued.jobId());assertThatThrownBy(()->request(hash)).isInstanceOfAny(BusinessException.class,IllegalStateException.class);s.setRollbackOnly();});
        assertThat(status().phase()).isEqualTo("WAITING_APPROVAL");assertThat(request(hash).phase()).isEqualTo("QUEUED");verify(f.f.store,never()).putAnswer(any(),any());
    }
    record Damage(String path,Object value) {}
    List<Damage> proofDamage(){return List.of(new Damage("segments",List.of()),
        new Damage("audio.sha256",null),new Damage("audio.sha256","not-a-digest"),new Damage("audio.byteSize",45),new Damage("audio.byteSize",67108865),
        new Damage("audio.sampleRate",48000),new Damage("audio.channels",2),new Damage("audio.dataOffset",45),new Damage("audio.dataSize",0),new Damage("audio.dataSize",3),
        new Damage("audio.durationMs",0),new Damage("audio.durationMs",1800001),new Damage("audio.durationMs",2),
        new Damage("segments.0.id",UUID.randomUUID().toString()),new Damage("segments.0.ordinal",2),new Damage("segments.0.sourceSha256",null),new Damage("segments.0.sourceSha256","wrong"),
        new Damage("segments.0.durationMs",0),new Damage("segments.0.durationMs",1800001),new Damage("segments.0.durationMs",5000),new Damage("segments.0.durationMs",1),new Damage("segments.0.durationMs",1500));}
    String damaged(String original,Damage damage)throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var tree=json.readTree(original);var path=damage.path().split("\\.");var parent=tree;
        for(int n=0;n<path.length-1;n++)parent=parent.isArray()?parent.get(Integer.parseInt(path[n])):parent.get(path[n]);
        ((com.fasterxml.jackson.databind.node.ObjectNode)parent).set(path[path.length-1],json.valueToTree(damage.value()));
        return system.assessment.defense.prepared.jobs.PreparedJobInput.json(json.writeValueAsString(tree),262144);
    }
    @Test void metadataAndConfirmedGarbageBytesNeverCountAsSavedAndRetriesKeepOneVerificationJob()throws Exception {
        var hash=end();var first=request(hash);assertThat(first.serverSaved()).isFalse();assertThat(first.phase()).isEqualTo("QUEUED");
        for(int i=0;i<100;i++)assertThat(request(hash)).isEqualTo(first);
        var lease=jobs.claim(PreparedJobLedger.Kind.ANSWER_MEDIA,"fixture-media",300).orElseThrow();
        var plan=service.plan(lease);assertThat(plan.locations()).hasSize(2);assertThat(plan.bundles()).hasSize(1);
        assertThat(plan.locations().getLast().offset()).isEqualTo(2);assertThat(plan.bundles().getFirst().key()).contains("/bundles/");
        assertThat(f.f.ends.q.f.jdbc.queryForObject("SELECT state FROM prepared_answer WHERE id=?",String.class,f.f.grant.answerId())).isEqualTo("UPLOAD_PENDING");
        assertThat(f.f.ends.count("prepared_answer_media")).isEqualTo(1);verify(f.f.store,never()).putAnswer(any(),any());
    }
    @Test void missingOriginalEndOrMissingBytesAndDifferentManifestCannotQueueFalseSuccess()throws Exception {
        assertThatThrownBy(()->request("a".repeat(64))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        var cmd=f.command();f.prepare(cmd);var hash=f.f.ends.end(f.f.grant.answerId(),f.f.ends.command(f.manifest(cmd))).manifestSha256();
        assertThatThrownBy(()->request(hash)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(f.f.ends.count("prepared_answer_media")).isZero();
    }
    @Test void wrongAccountClientAndRevokedRoleCannotQueueOrReadMediaReceipt()throws Exception {
        var hash=end();request(hash);
        assertThatThrownBy(()->service.status(f.f.ends.q.student+1,f.f.ends.q.f.assessment,f.f.ends.q.attempt.id(),f.f.grant.answerId())).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThatThrownBy(()->service.request(f.f.ends.q.student,f.f.ends.q.f.assessment,f.f.ends.q.attempt.id(),f.f.grant.answerId(),new PreparedAnswerMediaService.Command(UUID.randomUUID().toString(),hash))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        var lease=jobs.claim(PreparedJobLedger.Kind.ANSWER_MEDIA,"fixture-media",300).orElseThrow();
        f.f.ends.q.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.f.ends.q.student);
        try{assertThatThrownBy(()->service.plan(lease)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}
        finally{f.f.ends.q.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.f.ends.q.student);}
    }
    @Test void corruptBundlePositionCannotBeUsedForWholeAnswerProof()throws Exception {
        var hash=end();request(hash);var lease=jobs.claim(PreparedJobLedger.Kind.ANSWER_MEDIA,"fixture-media",300).orElseThrow();
        f.f.ends.q.f.jdbc.update("UPDATE prepared_answer_chunk SET bundle_offset=3 WHERE answer_id=? AND chunk_index=1",f.f.grant.answerId());
        assertThatThrownBy(()->service.plan(lease)).isInstanceOf(IllegalStateException.class);
    }
    String nativeEnd()throws Exception {
        var generated=new PreparedAnswerMediaTest();generated.folder=folder;var original=generated.recording("native.webm","libopus","webm",1);
        f.f.bytes=Files.readAllBytes(original);int split=f.f.bytes.length/2;
        var cmd=new PreparedAnswerBundle.Command(f.f.ends.q.client,f.f.segment,1,"audio/webm;codecs=opus",List.of(
            new PreparedAnswerBundle.Chunk(0,split,f.f.hash(Arrays.copyOfRange(f.f.bytes,0,split))),
            new PreparedAnswerBundle.Chunk(1,f.f.bytes.length-split,f.f.hash(Arrays.copyOfRange(f.f.bytes,split,f.f.bytes.length)))),f.f.bytes.length,f.f.hash(f.f.bytes));
        var prepared=f.prepare(cmd);f.upload(prepared.bundleId(),f.f.bytes);
        var manifest=new PreparedAnswerEnd.Manifest(1,List.of(new PreparedAnswerEnd.Segment(f.f.segment,1,cmd.mimeType(),1000L,2000L,"SEALED",null,2,f.f.bytes.length,cmd.chunks().stream().map(c->new PreparedAnswerEnd.Chunk(c.index(),c.byteSize(),c.sha256())).toList())));
        return f.f.ends.end(f.f.grant.answerId(),f.f.ends.command(manifest)).manifestSha256();
    }
    void stagedCloudDouble()throws Exception {
        doAnswer(i->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();Files.write(i.getArgument(1,Path.class),f.f.bytes,StandardOpenOption.CREATE_NEW);return null;}).when(f.f.store).downloadAnswerBundle(any(),any());
        when(f.f.store.putAnswer(any(),any())).thenAnswer(i->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerFile.class);
            return new PreparedPrivateOssStore.StoredAnswer(scope,scope.key("prepared-fixture"),system.assessment.defense.prepared.media.PreparedWaveAudio.inspect(i.getArgument(1)));});
    }
    @Test void savedMediaReceiptUsesCurrentJobProofAfterConcurrentVerification()throws Exception {
        var hash=nativeEnd();stagedCloudDouble();var queued=request(hash);var q=f.f.ends.q;
        assertThat(q.f.jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
        var snapshotCaptured=new java.util.concurrent.CountDownLatch(1);var verificationCommitted=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var recovery=pool.submit(()->q.f.tx.execute(s->{
                assertThat(q.f.jdbc.queryForObject("SELECT state FROM prepared_job WHERE id=?",String.class,queued.jobId())).isEqualTo("QUEUED");
                snapshotCaptured.countDown();
                try {assertThat(verificationCommitted.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                return status();
            }));
            assertThat(snapshotCaptured.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            PreparedAnswerMediaService.Summary saved;
            try {worker().runOnce();saved=status();assertThat(saved.serverSaved()).isTrue();}
            finally {verificationCommitted.countDown();}
            assertThat(recovery.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(saved);
        }
        assertThat(f.f.ends.count("prepared_answer_media")).isEqualTo(1);verify(f.f.store,times(1)).putAnswer(any(),any());
    }
    @Test void expiredMediaReceiptUsesCurrentApprovalReasonAfterConcurrentWindowTransition()throws Exception {
        var hash=nativeEnd();var queued=request(hash);var q=f.f.ends.q;
        var snapshotCaptured=new java.util.concurrent.CountDownLatch(1);var expiryCommitted=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var recovery=pool.submit(()->q.f.tx.execute(s->{
                assertThat(q.f.jdbc.queryForObject("SELECT state FROM prepared_job WHERE id=?",String.class,queued.jobId())).isEqualTo("QUEUED");
                snapshotCaptured.countDown();
                try {assertThat(expiryCommitted.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                return status();
            }));
            assertThat(snapshotCaptured.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            PreparedAnswerMediaService.Summary expired;
            try {finishOriginalQuestions(true);worker().runOnce();expired=status();assertThat(expired.phase()).isEqualTo("WAITING_APPROVAL");}
            finally {expiryCommitted.countDown();}
            assertThat(recovery.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(expired);
        }
        assertThat(status().serverSaved()).isFalse();verify(f.f.store,never()).putAnswer(any(),any());
    }
    @Test void realCodecVerificationAndUniqueDatabaseReceiptSaveOriginalAnswerNotFinalSubmission()throws Exception {
        String hash=nativeEnd();stagedCloudDouble();request(hash);
        var worker=new PreparedAnswerMediaWorker(jobs,service,runtime,f.f.store,f.f.policy);worker.runOnce();
        var saved=service.status(f.f.ends.q.student,f.f.ends.q.f.assessment,f.f.ends.q.attempt.id(),f.f.grant.answerId());
        assertThat(saved.serverSaved()).isTrue();assertThat(saved.phase()).isEqualTo("SAVED");assertThat(saved.durationMs()).isBetween(900L,1200L);
        for(int i=0;i<100;i++)assertThat(request(hash)).isEqualTo(saved);
        assertThat(f.f.ends.q.f.jdbc.queryForObject("SELECT state FROM prepared_answer WHERE id=?",String.class,f.f.grant.answerId())).isEqualTo("SAVED");
        assertThat(f.f.ends.attempt().state()).isEqualTo("IN_PROGRESS");verify(f.f.store,times(1)).putAnswer(any(),any());
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(saved)).doesNotContain("objectKey",f.f.ends.q.client,"inputJson","url");
    }
    @Test void codecFailureKeepsOriginalEvidenceAndNeverReturnsSavedOrZero()throws Exception {
        var hash=end();stagedCloudDouble();request(hash);new PreparedAnswerMediaWorker(jobs,service,runtime,f.f.store,f.f.policy).runOnce();
        var current=service.status(f.f.ends.q.student,f.f.ends.q.f.assessment,f.f.ends.q.attempt.id(),f.f.grant.answerId());
        assertThat(current.serverSaved()).isFalse();assertThat(current.phase()).isEqualTo("NEEDS_REVIEW");
        assertThat(f.f.ends.count("prepared_answer_chunk")).isEqualTo(2);verify(f.f.store,never()).putAnswer(any(),any());
        assertThat(Files.walk(folder.resolve("prepared-answer-spool")).anyMatch(p->p.getFileName().toString().equals("segment-1.bin"))).isTrue();
    }
    @Test void revocationAfterDecodedCloudSaveCannotCommitWholeAnswerReceipt()throws Exception {
        var hash=nativeEnd();stagedCloudDouble();request(hash);
        doAnswer(i->{f.f.ends.q.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.f.ends.q.student);var scope=i.getArgument(0,PreparedPrivateOssStore.AnswerFile.class);return new PreparedPrivateOssStore.StoredAnswer(scope,scope.key("prepared-fixture"),system.assessment.defense.prepared.media.PreparedWaveAudio.inspect(i.getArgument(1)));}).when(f.f.store).putAnswer(any(),any());
        try{new PreparedAnswerMediaWorker(jobs,service,runtime,f.f.store,f.f.policy).runOnce();}
        finally{f.f.ends.q.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.f.ends.q.student);}
        assertThat(service.status(f.f.ends.q.student,f.f.ends.q.f.assessment,f.f.ends.q.attempt.id(),f.f.grant.answerId()).serverSaved()).isFalse();
        assertThat(f.f.ends.q.f.jdbc.queryForObject("SELECT state FROM prepared_answer WHERE id=?",String.class,f.f.grant.answerId())).isEqualTo("UPLOAD_PENDING");
    }
    @Test void corruptedMatchingJobAndReceiptJsonMustNotReturnFalseSavedDespiteStateFlag()throws Exception {
        var hash=nativeEnd();stagedCloudDouble();var queued=request(hash);new PreparedAnswerMediaWorker(jobs,service,runtime,f.f.store,f.f.policy).runOnce();
        var jdbc=f.f.ends.q.f.jdbc;String original=jdbc.queryForObject("SELECT proof_json FROM prepared_answer_media WHERE answer_id=?",String.class,f.f.grant.answerId());var json=new com.fasterxml.jackson.databind.ObjectMapper();
        for(var damage:proofDamage()) {
            String corrupted=damaged(original,damage);
            jdbc.update("UPDATE prepared_answer_media SET proof_json=? WHERE answer_id=?",corrupted,f.f.grant.answerId());jdbc.update("UPDATE prepared_job SET output_json=? WHERE id=?",corrupted,queued.jobId());
            assertThatThrownBy(()->service.status(f.f.ends.q.student,f.f.ends.q.f.assessment,f.f.ends.q.attempt.id(),f.f.grant.answerId())).isInstanceOf(IllegalStateException.class);
        }
        jdbc.update("UPDATE prepared_answer_media SET proof_json=? WHERE answer_id=?",original,f.f.grant.answerId());jdbc.update("UPDATE prepared_job SET output_json=? WHERE id=?",original,queued.jobId());
        assertThat(service.status(f.f.ends.q.student,f.f.ends.q.f.assessment,f.f.ends.q.attempt.id(),f.f.grant.answerId()).serverSaved()).isTrue();
    }
    @Test void falseMediaProofCannotCommitAndExpiredLeaseCannotSaveDespiteValidCodecProof()throws Exception {
        var hash=nativeEnd();var queued=request(hash);var lease=jobs.claim(PreparedJobLedger.Kind.ANSWER_MEDIA,"fixture-proof",300).orElseThrow();var plan=service.plan(lease);
        var proof=runtime.decoder().verify(List.of(new system.assessment.defense.prepared.media.PreparedAnswerMedia.SegmentInput(f.f.segment,1,"audio/webm;codecs=opus",folder.resolve("native.webm"),1000)),plan.answerSeconds(),folder.resolve("proof.wav"));
        var input=plan.input();var scope=new PreparedPrivateOssStore.AnswerFile(input.assessment(),input.student(),input.attempt(),input.answer(),input.manifestSha256());
        var stored=new PreparedPrivateOssStore.StoredAnswer(scope,scope.key("prepared-fixture"),proof.audio());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();String original=json.writeValueAsString(proof);
        assertThatThrownBy(()->service.complete(lease,stored,null)).isInstanceOf(IllegalStateException.class);
        for(var damage:proofDamage()) {
            var corrupted=json.readValue(damaged(original,damage),system.assessment.defense.prepared.media.PreparedAnswerMedia.Proof.class);
            assertThatThrownBy(()->service.complete(lease,stored,corrupted)).as(damage.path()).isInstanceOf(IllegalStateException.class);
            assertThat(jobs.get(queued.jobId()).orElseThrow().state()).isEqualTo(PreparedJobLedger.State.RUNNING);assertThat(status().serverSaved()).isFalse();
        }
        for(var falseStored:List.of(new PreparedPrivateOssStore.StoredAnswer(scope,"other/object",proof.audio()),
                new PreparedPrivateOssStore.StoredAnswer(new PreparedPrivateOssStore.AnswerFile(input.assessment(),input.student(),input.attempt(),UUID.randomUUID().toString(),hash),scope.key("prepared-fixture"),proof.audio())))
            assertThatThrownBy(()->service.complete(lease,falseStored,proof)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.complete(lease,null,proof)).isInstanceOf(IllegalStateException.class);
        f.f.ends.q.f.jdbc.update("UPDATE prepared_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",queued.jobId());
        assertThatThrownBy(()->service.plan(lease)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThatThrownBy(()->service.complete(lease,stored,proof)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(status().serverSaved()).isFalse();assertThat(f.f.ends.count("prepared_answer_chunk")).isEqualTo(2);
    }
    @Test void wholeReceiptBindsOriginalJobAnswerAttemptClientManifestAndDeclarationRevision()throws Exception {
        var hash=end();var queued=request(hash);var jdbc=f.f.ends.q.f.jdbc;String original=jdbc.queryForObject("SELECT input_json FROM prepared_job WHERE id=?",String.class,queued.jobId());
        for(var damage:List.of(new Damage("answer",UUID.randomUUID().toString()),new Damage("attempt",UUID.randomUUID().toString()),new Damage("client",UUID.randomUUID().toString()),
                new Damage("manifestSha256","b".repeat(64)),new Damage("declarationRevision",999),new Damage("student",f.f.ends.q.student+1),new Damage("assessment",f.f.ends.q.f.assessment+1),new Damage("deployment","other-deployment"))) {
            jdbc.update("UPDATE prepared_job SET input_json=? WHERE id=?",damaged(original,damage),queued.jobId());
            assertThatThrownBy(this::status).as(damage.path()).isInstanceOf(IllegalStateException.class);
        }
        jdbc.update("UPDATE prepared_job SET input_json=? WHERE id=?",original,queued.jobId());assertThat(status().serverSaved()).isFalse();
    }
    @Test void disabledWorkerAndReviewAnswerCannotQueueOrConsumeAnOriginalJob()throws Exception {
        assertThat(status().phase()).isEqualTo("NOT_QUEUED");var hash=end();var disabled=new PreparedAnswerMediaRuntime(new MockEnvironment());
        var service=new PreparedAnswerMediaService(f.f.uploads,new PreparedAnswerUploadRepository(f.f.ends.q.f.jdbc),new PreparedAnswerBundleRepository(f.f.ends.q.f.jdbc),jobs,disabled,f.f.policy,f.f.ends.q.f.jdbc,f.f.ends.q.f.tx.getTransactionManager());
        assertThatThrownBy(()->service.request(f.f.ends.q.student,f.f.ends.q.f.assessment,f.f.ends.q.attempt.id(),f.f.grant.answerId(),new PreparedAnswerMediaService.Command(f.f.ends.q.client,hash))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        assertThat(f.f.ends.count("prepared_answer_media")).isZero();
        assertThatThrownBy(()->request("b".repeat(64))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThatThrownBy(()->this.service.request(f.f.ends.q.student,f.f.ends.q.f.assessment,f.f.ends.q.attempt.id(),f.f.grant.answerId(),null)).isInstanceOf(IllegalArgumentException.class);
        request(hash);new PreparedAnswerMediaWorker(jobs,this.service,disabled,f.f.store,f.f.policy).runOnce();assertThat(status().phase()).isEqualTo("QUEUED");
    }
    @Test void originalSingleChunkCanBeActuallyDecodedAndSavedWithoutInventingWholeOrIdleDeadline()throws Exception {
        var generated=new PreparedAnswerMediaTest();generated.folder=folder;f.f.bytes=Files.readAllBytes(generated.recording("single.webm","libopus","webm",1));
        var command=f.f.command();f.f.upload(command,f.f.bytes);var hash=f.f.ends.end(f.f.grant.answerId(),f.f.ends.command(f.f.manifest(command))).manifestSha256();
        f.f.ends.q.now.set(f.f.ends.q.now.get().plusSeconds(86400*20));request(hash);
        doAnswer(i->{Files.write(i.getArgument(1,Path.class),f.f.bytes,StandardOpenOption.CREATE_NEW);return null;}).when(f.f.store).downloadAnswerChunk(any(),any());
        stagedCloudDouble();new PreparedAnswerMediaWorker(jobs,service,runtime,f.f.store,f.f.policy).runOnce();
        assertThat(status().serverSaved()).isTrue();assertThat(f.f.ends.attempt().answeringEndedAt()).isNull();assertThat(f.f.ends.attempt().supplementalUploadDeadline()).isNull();
        verify(f.f.store,times(1)).downloadAnswerChunk(any(),any());verify(f.f.store,never()).downloadAnswerBundle(any(),any());
    }
    @Test void fixedBackfillDeadlineIsCommittedAsReviewAndCannotBeResetByMediaRequest()throws Exception {
        var hash=end();for(int ordinal=2;ordinal<=3;ordinal++){var next=f.f.ends.q.grant(ordinal);f.f.ends.end(next.answerId(),f.f.ends.command(f.f.ends.manifest()));}
        var original=f.f.ends.attempt();f.f.ends.q.now.set(java.time.Instant.parse(original.supplementalUploadDeadline()));
        assertThatThrownBy(()->request(hash)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(f.f.ends.attempt().state()).isEqualTo("REQUIRES_REVIEW");assertThat(f.f.ends.attempt().supplementalUploadDeadline()).isEqualTo(original.supplementalUploadDeadline());
        assertThat(f.f.ends.count("prepared_answer_media")).isZero();assertThat(f.f.ends.count("prepared_answer_chunk")).isEqualTo(2);
    }
}
