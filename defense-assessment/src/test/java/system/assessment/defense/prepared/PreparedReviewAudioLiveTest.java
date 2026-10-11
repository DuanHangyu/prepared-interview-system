package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.processing.PreparedReviewAudioService;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual independent MySQL/native original receipt. Short private URL is an explicit SDK double. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedReviewAudioLiveTest {
    final PreparedProcessingLiveTest f=new PreparedProcessingLiveTest();@TempDir Path folder;
    PreparedPrivateOssStore store;PreparedReviewAudioService audio;String answer;Clock clock;
    @BeforeEach void setup()throws Exception{f.folder=folder;f.setup();answer=f.jdbc.queryForObject("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? ORDER BY ordinal LIMIT 1",String.class,f.assessment);
        store=mock(PreparedPrivateOssStore.class);clock=Clock.fixed(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),ZoneOffset.UTC);audio=new PreparedReviewAudioService(f.service,store,clock);
        when(store.previewAnswer(any())).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var media=i.getArgument(0,PreparedPrivateOssStore.StoredAnswer.class);
            return new PreparedPrivateOssStore.Preview("https://prepared-fixture.oss-cn-beijing.aliyuncs.com/"+media.objectKey()+"?Signature=explicit-fixture",clock.instant().plusSeconds(300).toString());});}
    @AfterEach void cleanup(){f.cleanup();}
    int count(){return f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_AUDIO_AUTHORIZED'",Integer.class,f.assessment);}
    @Test void originalAudioAccessWorksBeforeModelResultsAndAuditsExactContentWithoutUrlsOrGradeChanges(){
        var original=f.f.status();var result=audio.preview(f.actor,f.assessment,f.attempt,answer);assertThat(result.studentId()).isEqualTo(f.student);assertThat(result.durationMs()).isBetween(900L,1200L);
        var audit=f.jdbc.queryForMap("SELECT * FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_AUDIO_AUTHORIZED'",f.assessment);
        assertThat(audit.get("actor_id")).isEqualTo(f.actor);assertThat(audit.get("resource_id")).isEqualTo(answer);assertThat(audit.get("resource_sha256")).isEqualTo(result.sha256());
        assertThat(f.jdbc.queryForObject("SELECT authorization_expires_at FROM prepared_audit WHERE assessment_id=? AND action='ORIGINAL_AUDIO_AUTHORIZED'",java.sql.Timestamp.class,f.assessment).toInstant()).isEqualTo(Instant.parse(result.expiresAt()));assertThat(audit.toString()).doesNotContain("Signature","explicit-fixture","url");
        assertThat(f.count("prepared_processing_head")).isZero();assertThat(f.count("prepared_result_command")).isZero();assertThat(f.f.status()).isEqualTo(original);
    }
    @Test void subjectRoleArchiveAndWrongAnswerNeverReadPrivateStorageAndAdminStillRequiresOriginalReceipt(){
        assertThatThrownBy(()->audio.preview(f.student,f.assessment,f.attempt,answer)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->audio.preview(f.actor,f.assessment,f.attempt,UUID.randomUUID().toString())).isInstanceOf(BusinessException.class);
        f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",f.assessment);try{assertThatThrownBy(()->audio.preview(f.actor,f.assessment,f.attempt,answer)).isInstanceOf(BusinessException.class);}finally{f.jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",f.assessment);}
        verifyNoInteractions(store);assertThat(count()).isZero();
        f.jdbc.update("UPDATE `user` SET type=2 WHERE id=?",f.actor);try{assertThat(audio.preview(f.actor,f.assessment,f.attempt,answer).answerId()).isEqualTo(answer);}finally{f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.actor);}
    }
    @Test void revocationWhilePrivateReadIsInFlightCannotDeliverOrAuditTheAlreadyGeneratedSignature()throws Exception {
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",f.actor);
            return new PreparedPrivateOssStore.Preview("https://private.invalid/?Signature=not-delivered",clock.instant().plusSeconds(300).toString());}).when(store).previewAnswer(any());
        try{assertThatThrownBy(()->audio.preview(f.actor,f.assessment,f.attempt,answer)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(system.assessment.defense.infrastructure.emuns.ErrorCodeEnums.NO_PERMISSION.getCode());assertThat(count()).isZero();}
        finally{f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",f.actor);}assertThat(f.f.status().submitted()).isTrue();
    }
    @Test void changedProofCannotBeAuditedAsOriginalAndDamagedReceiptNeverOpensStorage(){
        var media=f.service.reviewMedia(f.actor,f.assessment,f.attempt,answer);var wrong=new PreparedPrivateOssStore.StoredAnswer(media.scope(),media.objectKey(),new system.assessment.defense.prepared.media.PreparedWaveAudio.Verified(media.proof().byteSize(),"e".repeat(64),media.proof().durationMs(),16000,1,media.proof().dataOffset(),media.proof().dataSize()));
        assertThatThrownBy(()->f.service.recordAudioAccess(f.actor,f.assessment,f.attempt,answer,wrong,clock.instant().plusSeconds(300))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);assertThat(count()).isZero();
        var tx=f.f.m.f.f.ends.q.f.tx;tx.executeWithoutResult(s->{f.jdbc.update("UPDATE prepared_submission SET snapshot_sha256=? WHERE assessment_id=?","e".repeat(64),f.assessment);
            assertThatThrownBy(()->audio.preview(f.actor,f.assessment,f.attempt,answer)).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});verifyNoInteractions(store);
        assertThatThrownBy(()->f.service.recordAudioAccess(f.actor,f.assessment,f.attempt,answer,null,clock.instant())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.service.recordAudioAccess(f.actor,f.assessment,f.attempt,answer,media,null)).isInstanceOf(IllegalArgumentException.class);
    }
}
