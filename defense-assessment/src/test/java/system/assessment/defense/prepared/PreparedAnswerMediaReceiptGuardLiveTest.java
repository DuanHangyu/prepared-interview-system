package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.PreparedAnswerMediaService;
import system.assessment.defense.prepared.jobs.PreparedJobLedger;
import system.assessment.defense.prepared.media.PreparedAnswerMedia;
import system.assessment.defense.prepared.media.PreparedWaveAudio;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native original receipt/rollback and real local codec. Cloud IO remains an explicit
 * adapter double; these defensive tests are not device, cloud or capacity acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAnswerMediaReceiptGuardLiveTest {
    final PreparedAnswerMediaLiveTest f=new PreparedAnswerMediaLiveTest();
    @TempDir Path folder;
    @BeforeEach void setup()throws Exception{folder=folder.toRealPath();f.folder=folder;f.setup();}
    @AfterEach void cleanup(){f.cleanup();}

    private org.springframework.jdbc.core.JdbcTemplate jdbc(){return f.f.f.ends.q.f.jdbc;}
    private org.springframework.transaction.support.TransactionTemplate tx(){return f.f.f.ends.q.f.tx;}
    private int assessment(){return f.f.f.ends.q.f.assessment;}
    private String answer(){return f.f.f.grant.answerId();}
    private PreparedJobLedger.Lease claim(){return f.jobs.claim(PreparedJobLedger.Kind.ANSWER_MEDIA,"receipt-guard",300).orElseThrow();}
    private void rollbackFault(String sql,Object[] args,Runnable assertion){
        tx().executeWithoutResult(status->{assertThat(jdbc().update(sql,args)).isGreaterThan(0);assertion.run();status.setRollbackOnly();});
    }

    @Test void missingLeaseWrongJobKindAndMalformedCommandNeverStartMediaIo()throws Exception{
        assertThatThrownBy(()->f.service.plan(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.service.beginMediaIo(null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.service.plan(new PreparedJobLedger.Lease(null,1,"receipt-guard")))
                .isInstanceOf(IllegalStateException.class).hasMessage("原录音核验任务无法核验");
        String jobId=jdbc().queryForList("SELECT id FROM prepared_job WHERE assessment_id=? AND kind='TTS'",String.class,assessment()).getFirst();
        var job=f.jobs.get(jobId).orElseThrow();
        assertThatThrownBy(()->f.service.plan(new PreparedJobLedger.Lease(job,1,"receipt-guard")))
                .isInstanceOf(IllegalStateException.class).hasMessage("原录音核验任务无法核验");
        String client=f.f.f.ends.q.client;
        assertThatThrownBy(()->new PreparedAnswerMediaService.Command(client,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedAnswerMediaService.Command(client,"invalid")).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.status().phase()).isEqualTo("NOT_QUEUED");assertThat(f.f.f.ends.count("prepared_answer_media")).isZero();
        verify(f.f.f.store,never()).putAnswer(any(),any());
    }

    @Test void queuedJobOrAnswerSavedFlagsWithoutWholeProofCannotInventASavedReceipt()throws Exception{
        var queued=f.request(f.end());
        rollbackFault("UPDATE prepared_job SET state='SUCCEEDED',output_json='{}' WHERE id=? AND assessment_id=?",
                new Object[]{queued.jobId(),assessment()},()->assertThatThrownBy(f::status)
                        .isInstanceOf(IllegalStateException.class).hasMessage("整题媒体状态不一致"));
        rollbackFault("UPDATE prepared_answer SET state='SAVED',revision=revision+1 WHERE id=? AND assessment_id=?",
                new Object[]{answer(),assessment()},()->assertThatThrownBy(f::status)
                        .isInstanceOf(IllegalStateException.class).hasMessage("整题媒体状态不一致"));
        assertThat(f.status()).isEqualTo(queued);assertThat(f.status().serverSaved()).isFalse();
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='ANSWER_MEDIA_SAVED'",Integer.class,assessment())).isZero();
        verify(f.f.f.store,never()).putAnswer(any(),any());
    }

    @Test void savedReceiptRechecksJobOutputStateAndOriginalAnswerRevisionOnEveryRead()throws Exception{
        var hash=f.nativeEnd();f.stagedCloudDouble();var queued=f.request(hash);f.worker().runOnce();var saved=f.status();
        assertThat(saved.serverSaved()).isTrue();
        for(String sql:List.of("UPDATE prepared_job SET state='QUEUED' WHERE id=? AND assessment_id=?",
                "UPDATE prepared_job SET output_json='{}' WHERE id=? AND assessment_id=?")){
            rollbackFault(sql,new Object[]{queued.jobId(),assessment()},()->assertThatThrownBy(f::status)
                    .isInstanceOf(IllegalStateException.class).hasMessage("整题媒体回执损坏"));
            assertThat(f.status()).isEqualTo(saved);
        }
        for(String sql:List.of("UPDATE prepared_answer SET state='UPLOAD_PENDING' WHERE id=? AND assessment_id=?",
                "UPDATE prepared_answer SET revision=revision+1 WHERE id=? AND assessment_id=?")){
            rollbackFault(sql,new Object[]{answer(),assessment()},()->assertThatThrownBy(f::status)
                    .isInstanceOf(IllegalStateException.class).hasMessage("整题媒体回执损坏"));
            assertThat(f.status()).isEqualTo(saved);
        }
        verify(f.f.f.store,times(1)).putAnswer(any(),any());assertThat(f.f.f.ends.count("prepared_answer_media")).isEqualTo(1);
    }

    @Test void confirmedChunkMetadataCannotSilentlyChangeTheOriginalEndManifest()throws Exception{
        f.request(f.end());var lease=claim();var original=f.service.plan(lease);
        // Fixed local fault whitelist. Constraints/FKs remain enabled; each fault rolls back.
        for(String fields:List.of("deployment_id='prepared-other'","object_key='prepared/owned-corrupted-key'",
                "byte_size=byte_size+1","sha256=REPEAT('c',64)","chunk_index=2","confirmed_at=NULL")){
            rollbackFault("UPDATE prepared_answer_chunk SET "+fields+" WHERE answer_id=? AND assessment_id=? AND chunk_index=0",
                    new Object[]{answer(),assessment()},()->assertThatThrownBy(()->f.service.plan(lease))
                            .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409));
            assertThat(f.service.plan(lease)).isEqualTo(original);
        }
        for(String fields:List.of("ordinal=2","mime_type='audio/ogg'")){
            rollbackFault("UPDATE prepared_answer_segment SET "+fields+" WHERE answer_id=? AND assessment_id=?",
                    new Object[]{answer(),assessment()},()->assertThatThrownBy(()->f.service.plan(lease))
                            .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409));
            assertThat(f.service.plan(lease)).isEqualTo(original);
        }
        assertThat(f.status().serverSaved()).isFalse();verify(f.f.f.store,never()).putAnswer(any(),any());
    }

    @Test void aForeignSegmentWithinTheSameAnswerCannotReplaceTheOriginalSegment()throws Exception{
        f.request(f.end());var lease=claim();var original=f.service.plan(lease);String replacement=UUID.randomUUID().toString();var q=f.f.f.ends.q;
        tx().executeWithoutResult(status->{
            assertThat(jdbc().update("INSERT INTO prepared_answer_segment(id,assessment_id,student_id,attempt_id,answer_id,ordinal,mime_type) VALUES (?,?,?,?,?,2,'audio/webm;codecs=opus')",
                    replacement,assessment(),q.student,q.attempt.id(),answer())).isEqualTo(1);
            assertThat(jdbc().update("UPDATE prepared_answer_chunk SET segment_id=? WHERE answer_id=? AND assessment_id=?",replacement,answer(),assessment())).isEqualTo(2);
            assertThatThrownBy(()->f.service.plan(lease)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
            status.setRollbackOnly();
        });
        assertThat(f.service.plan(lease)).isEqualTo(original);assertThat(f.status().serverSaved()).isFalse();
    }

    @Test void bundleAndChunkConfirmationInstantsMustStillAgreeDuringAssembly()throws Exception{
        f.request(f.end());var lease=claim();var original=f.service.plan(lease);
        rollbackFault("UPDATE prepared_answer_chunk SET confirmed_at=DATE_ADD(confirmed_at,INTERVAL 1000 MICROSECOND) WHERE answer_id=? AND assessment_id=? AND chunk_index=0",
                new Object[]{answer(),assessment()},()->assertThatThrownBy(()->f.service.plan(lease))
                        .isInstanceOf(IllegalStateException.class).hasMessage("原批次确认绑定损坏"));
        assertThat(f.service.plan(lease)).isEqualTo(original);assertThat(f.status().serverSaved()).isFalse();
    }

    @Test void actualCodecProofCannotCommitAgainstDifferentStoredWholeAudioBytes()throws Exception{
        var hash=f.nativeEnd();var queued=f.request(hash);var lease=claim();var plan=f.service.plan(lease);
        var proof=f.runtime.decoder().verify(List.of(new PreparedAnswerMedia.SegmentInput(f.f.f.segment,1,"audio/webm;codecs=opus",folder.resolve("native.webm"),1000)),
                plan.answerSeconds(),folder.resolve("receipt-guard.wav"));
        var input=plan.input();var scope=new PreparedPrivateOssStore.AnswerFile(input.assessment(),input.student(),input.attempt(),input.answer(),hash);
        var audio=proof.audio();String changed=(audio.sha256().charAt(0)=='a'?"b":"a")+audio.sha256().substring(1);
        var falseAudio=new PreparedWaveAudio.Verified(audio.byteSize(),changed,audio.durationMs(),audio.sampleRate(),audio.channels(),audio.dataOffset(),audio.dataSize());
        var stored=new PreparedPrivateOssStore.StoredAnswer(scope,scope.key("prepared-fixture"),falseAudio);
        assertThatThrownBy(()->f.service.complete(lease,stored,proof)).isInstanceOf(IllegalStateException.class).hasMessage("完整媒体证明无法核验");
        assertThat(f.jobs.get(queued.jobId()).orElseThrow().state()).isEqualTo(PreparedJobLedger.State.RUNNING);
        assertThat(f.status().serverSaved()).isFalse();assertThat(f.f.f.ends.count("prepared_answer_media")).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT proof_json FROM prepared_answer_media WHERE answer_id=?",String.class,answer())).isNull();
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='ANSWER_MEDIA_SAVED'",Integer.class,assessment())).isZero();
    }
}
