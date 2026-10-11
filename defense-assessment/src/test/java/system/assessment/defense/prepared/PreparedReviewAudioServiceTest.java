package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.processing.*;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import system.assessment.defense.prepared.media.PreparedWaveAudio;
import java.io.IOException;
import java.time.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit unit doubles: not real storage, permissions or browser playback acceptance. */
class PreparedReviewAudioServiceTest {
    String attempt="11111111-1111-4111-8111-111111111111",answer="22222222-2222-4222-8222-222222222222";
    Clock clock=Clock.fixed(Instant.parse("2026-10-05T01:00:00Z"),ZoneOffset.UTC);
    PreparedProcessingService processing;PreparedPrivateOssStore store;PreparedReviewAudioService service;
    PreparedPrivateOssStore.StoredAnswer media;PreparedPrivateOssStore.Preview preview;
    @BeforeEach void setup()throws Exception {
        processing=mock(PreparedProcessingService.class);store=mock(PreparedPrivateOssStore.class);service=new PreparedReviewAudioService(processing,store,clock);
        var scope=new PreparedPrivateOssStore.AnswerFile(21,8,attempt,answer,"a".repeat(64));String key=scope.key("prepared-fixture");
        media=new PreparedPrivateOssStore.StoredAnswer(scope,key,new PreparedWaveAudio.Verified(32044,"b".repeat(64),1000,16000,1,44,32000));
        preview=new PreparedPrivateOssStore.Preview("https://prepared-fixture.oss-cn-beijing.aliyuncs.com/"+key+"?Signature=secret",clock.instant().plusSeconds(300).toString());
        when(processing.reviewMedia(7,21,attempt,answer)).thenReturn(media);when(store.previewAnswer(media)).thenReturn(preview);
    }
    @Test void authorizesBeforeIoAndReauthorizesAuditedExactSourceBeforeReturningShortUrl()throws Exception {
        var reply=service.preview(7,21,attempt,answer);assertThat(reply.actorId()).isEqualTo(7);assertThat(reply.studentId()).isEqualTo(8);
        assertThat(reply.answerId()).isEqualTo(answer);assertThat(reply.sha256()).isEqualTo(media.proof().sha256());assertThat(reply.durationMs()).isEqualTo(1000);
        assertThat(reply.toString()).doesNotContain("Signature=","secret");var order=inOrder(processing,store);
        order.verify(processing).reviewMedia(7,21,attempt,answer);order.verify(store).previewAnswer(media);
        order.verify(processing).recordAudioAccess(7,21,attempt,answer,media,Instant.parse(preview.expiresAt()));
        verify(store,never()).asrInput(any());
    }
    @Test void revokedInitialPermissionNeverOpensStorageAndRevocationDuringIoNeverReturnsTheSignature() {
        when(processing.reviewMedia(7,21,attempt,answer)).thenThrow(new BusinessException(403,"管理权限已变化"));
        assertThatThrownBy(()->service.preview(7,21,attempt,answer)).isInstanceOf(BusinessException.class);verifyNoInteractions(store);
        doReturn(media).when(processing).reviewMedia(7,21,attempt,answer);
        doThrow(new BusinessException(403,"管理权限已变化")).when(processing).recordAudioAccess(7,21,attempt,answer,media,Instant.parse(preview.expiresAt()));
        assertThatThrownBy(()->service.preview(7,21,attempt,answer)).isInstanceOf(BusinessException.class).hasMessageNotContaining("Signature");
    }
    @Test void privateReadFailureIsRetryableSanitizedAndNeverAuditsOrMutatesOriginals()throws Exception {
        when(store.previewAnswer(media)).thenThrow(new IOException("secret-storage-link"));
        assertThatThrownBy(()->service.preview(7,21,attempt,answer)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        verify(processing,never()).recordAudioAccess(anyInt(),anyInt(),anyString(),anyString(),any(),any());
    }
    @Test void expiredUnboundedMalformedSignatureOrWrongSourceCannotReachFinalAuthorization()throws Exception {
        for(String expiry:new String[]{"bad",clock.instant().toString(),clock.instant().plusSeconds(301).toString()}) {
            when(store.previewAnswer(media)).thenReturn(new PreparedPrivateOssStore.Preview(preview.url(),expiry));
            assertThatThrownBy(()->service.preview(7,21,attempt,answer)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        }
        when(processing.reviewMedia(7,21,attempt,answer)).thenReturn(new PreparedPrivateOssStore.StoredAnswer(new PreparedPrivateOssStore.AnswerFile(22,8,attempt,answer,"a".repeat(64)),media.objectKey(),media.proof()));
        assertThatThrownBy(()->service.preview(7,21,attempt,answer)).isInstanceOf(IllegalStateException.class);
        verify(processing,never()).recordAudioAccess(anyInt(),anyInt(),anyString(),anyString(),any(),any());
    }
    @Test void readbackConcurrencyIsBoundedWithoutBlockingOrCallingStorageForExcessRequests()throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(store.previewAnswer(media)).thenAnswer(i->{if(calls.incrementAndGet()<=2){entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}return preview;});
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var first=executor.submit(()->service.preview(7,21,attempt,answer));var second=executor.submit(()->service.preview(7,21,attempt,answer));
            try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->service.preview(7,21,attempt,answer)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);}
            finally{release.countDown();}assertThat(first.get(5,TimeUnit.SECONDS)).isNotNull();assertThat(second.get(5,TimeUnit.SECONDS)).isNotNull();
        }
        assertThat(calls.get()).isEqualTo(2);assertThat(service.preview(7,21,attempt,answer)).isNotNull();
    }
    @Test void serverTimeReflectsDeliveryAfterPrivateIoAndFinalAuthorizationRatherThanRequestStart()throws Exception {
        var now=new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
        service=new PreparedReviewAudioService(processing,store,movingClock(now));
        when(store.previewAnswer(media)).thenAnswer(i->{now.updateAndGet(v->v.plusSeconds(3));return preview;});
        doAnswer(i->{now.updateAndGet(v->v.plusSeconds(2));return null;}).when(processing)
            .recordAudioAccess(7,21,attempt,answer,media,Instant.parse(preview.expiresAt()));
        var reply=service.preview(7,21,attempt,answer);
        assertThat(reply.serverTime()).isEqualTo(clock.instant().plusSeconds(5).toString());
        assertThat(Duration.between(Instant.parse(reply.serverTime()),Instant.parse(reply.expiresAt()))).isEqualTo(Duration.ofSeconds(295));
    }
    @Test void capabilityExpiringDuringFinalAuthorizationIsNotDeliveredAndReadCapacityIsReleased()throws Exception {
        var now=new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
        service=new PreparedReviewAudioService(processing,store,movingClock(now));
        doAnswer(i->{now.set(Instant.parse(preview.expiresAt()));return null;}).when(processing)
            .recordAudioAccess(7,21,attempt,answer,media,Instant.parse(preview.expiresAt()));
        assertThatThrownBy(()->service.preview(7,21,attempt,answer)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("授权已到期").hasMessageNotContaining("Signature").extracting("code").isEqualTo(503);
        var renewed=new PreparedPrivateOssStore.Preview(preview.url(),now.get().plusSeconds(300).toString());
        when(store.previewAnswer(media)).thenReturn(renewed);
        assertThat(service.preview(7,21,attempt,answer).serverTime()).isEqualTo(now.get().toString());
        verify(store,never()).asrInput(any());
    }
    static Clock movingClock(java.util.concurrent.atomic.AtomicReference<Instant> now) {
        return new Clock(){
            @Override public ZoneId getZone(){return ZoneOffset.UTC;}
            @Override public Clock withZone(ZoneId zone){if(!ZoneOffset.UTC.equals(zone))throw new IllegalArgumentException();return this;}
            @Override public Instant instant(){return now.get();}
        };
    }
}
