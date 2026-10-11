package system.assessment.defense.application.manage;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RealtimeVoiceOutputManagerBoundaryTest {

    private RealtimeVoiceOutputManager manager;

    @BeforeEach
    void setUp() {
        manager = new RealtimeVoiceOutputManager(
                mock(OkHttpClient.class),
                new ObjectMapper(),
                mock(RealtimeSpeechEventFactory.class));
        ReflectionTestUtils.setField(manager, "maxEventChars", 1_024);
        ReflectionTestUtils.setField(manager, "maxResponseAudioBytes", 4L);
    }

    @Test
    void closesTheUpstreamBeforeForwardingAudioBeyondTheCumulativeLimit() throws Exception {
        WebSocket upstream = mock(WebSocket.class);
        List<byte[]> forwarded = new ArrayList<>();
        AtomicBoolean hasAudio = new AtomicBoolean();
        AtomicBoolean responseRequested = new AtomicBoolean();
        AtomicLong audioBytes = new AtomicLong();
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        String oversizedAudio = Base64.getEncoder().encodeToString(new byte[5]);

        manager.handleServerEvent(upstream,
                "{\"type\":\"response.audio.delta\",\"delta\":\"" + oversizedAudio + "\"}",
                "question", forwarded::add, hasAudio, responseRequested, audioBytes, done);

        assertThat(forwarded).isEmpty();
        assertThat(hasAudio).isFalse();
        assertThat(done.get()).isFalse();
        verify(upstream).close(1009, "voice audio too large");
    }

    @Test
    void closesTheUpstreamBeforeParsingAnOversizedEvent() throws Exception {
        WebSocket upstream = mock(WebSocket.class);
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        ReflectionTestUtils.setField(manager, "maxEventChars", 8);

        manager.handleServerEvent(upstream, "x".repeat(9), "question", ignored -> {
        }, new AtomicBoolean(), new AtomicBoolean(), new AtomicLong(), done);

        assertThat(done.get()).isFalse();
        verify(upstream).close(1009, "voice event too large");
    }
}
