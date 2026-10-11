package system.assessment.defense.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class InterviewSessionStore {

    private static final int DEFAULT_MAX_AUDIO_SESSIONS = 256;

    private final long maxSessionAudioBytes;
    private final long maxTotalAudioBytes;
    private final AtomicLong totalAudioBytes = new AtomicLong();
    private final Cache<Integer, AudioBuffer> audioBuffers;
    private final Cache<String, List<InterviewLiveQuestion>> liveQuestions;
    private final Cache<String, String> followUps;

    @Autowired
    public InterviewSessionStore(
            @Value("${interview.memory.max-audio-bytes-per-session:67108864}") long maxSessionAudioBytes,
            @Value("${interview.memory.max-total-audio-bytes:268435456}") long maxTotalAudioBytes,
            @Value("${interview.memory.max-audio-sessions:256}") long maxAudioSessions,
            @Value("${interview.memory.max-cache-weight:1048576}") long maxCacheWeight,
            @Value("${interview.memory.expire-after-access:PT3H}") Duration expireAfterAccess) {
        this(maxSessionAudioBytes, maxTotalAudioBytes, maxAudioSessions, maxCacheWeight,
                expireAfterAccess, Ticker.systemTicker());
    }

    InterviewSessionStore(long maxSessionAudioBytes,
                          long maxTotalAudioBytes,
                          long maxCacheWeight,
                          Duration expireAfterAccess,
                          Ticker ticker) {
        this(maxSessionAudioBytes, maxTotalAudioBytes, DEFAULT_MAX_AUDIO_SESSIONS,
                maxCacheWeight, expireAfterAccess, ticker);
    }

    InterviewSessionStore(long maxSessionAudioBytes,
                          long maxTotalAudioBytes,
                          long maxAudioSessions,
                          long maxCacheWeight,
                          Duration expireAfterAccess,
                          Ticker ticker) {
        if (maxSessionAudioBytes <= 0 || maxTotalAudioBytes < maxSessionAudioBytes
                || maxAudioSessions <= 0 || maxCacheWeight <= 0) {
            throw new IllegalArgumentException("Interview memory limits are invalid");
        }
        this.maxSessionAudioBytes = maxSessionAudioBytes;
        this.maxTotalAudioBytes = maxTotalAudioBytes;
        RemovalListener<Integer, AudioBuffer> audioRemovalListener =
                (studentId, buffer, cause) -> release(buffer);
        this.audioBuffers = Caffeine.<Integer, AudioBuffer>newBuilder()
                .maximumSize(maxAudioSessions)
                .expireAfterAccess(expireAfterAccess)
                .ticker(ticker)
                .executor(Runnable::run)
                .removalListener(audioRemovalListener)
                .build();
        this.liveQuestions = Caffeine.<String, List<InterviewLiveQuestion>>newBuilder()
                .maximumWeight(maxCacheWeight)
                .weigher((String key, List<InterviewLiveQuestion> value) -> liveQuestionWeight(key, value))
                .expireAfterAccess(expireAfterAccess)
                .ticker(ticker)
                .build();
        this.followUps = Caffeine.<String, String>newBuilder()
                .maximumWeight(maxCacheWeight)
                .weigher((String key, String value) -> boundedWeight(key.length() + value.length()))
                .expireAfterAccess(expireAfterAccess)
                .ticker(ticker)
                .build();
    }

    public void startAudio(Integer studentId, int initialCapacity) {
        startAudioInternal(studentId, null, initialCapacity);
    }

    public boolean startAudio(Integer studentId, String turnId, int initialCapacity) {
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        return startAudioInternal(studentId, turnId, initialCapacity);
    }

    public void appendAudio(Integer studentId, ByteBuffer payload) {
        appendAudioInternal(studentId, null, payload);
    }

    public boolean appendAudio(Integer studentId, String turnId, ByteBuffer payload) {
        if (turnId == null || turnId.isBlank()) {
            return false;
        }
        return appendAudioInternal(studentId, turnId, payload);
    }

    private boolean appendAudioInternal(Integer studentId, String turnId, ByteBuffer payload) {
        ByteBuffer copy = payload.asReadOnlyBuffer();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);

        AudioBuffer buffer = audioBuffers.getIfPresent(studentId);
        if (buffer == null) {
            return false;
        }
        synchronized (buffer) {
            if (audioBuffers.getIfPresent(studentId) != buffer
                    || buffer.released.get()
                    || !Objects.equals(buffer.turnId, turnId)
                    || buffer.state != AudioTurnState.RECORDING) {
                return false;
            }
            if (bytes.length == 0) {
                return true;
            }
            if (buffer.output.size() > maxSessionAudioBytes - bytes.length || !reserve(bytes.length)) {
                audioBuffers.invalidate(studentId);
                throw new BusinessException(ErrorCodeEnums.VOICE_DATA_TOO_LARGE);
            }
            buffer.output.writeBytes(bytes);
            return true;
        }
    }

    public boolean flushAudio(Integer studentId, String turnId) {
        AudioBuffer buffer = matchingBuffer(studentId, turnId);
        if (buffer == null) {
            return false;
        }
        synchronized (buffer) {
            if (!isCurrentBuffer(studentId, turnId, buffer)) {
                return false;
            }
            buffer.state = AudioTurnState.FLUSHED;
            return true;
        }
    }

    public boolean isAudioFlushed(Integer studentId, String turnId) {
        AudioBuffer buffer = matchingBuffer(studentId, turnId);
        if (buffer == null) {
            return false;
        }
        synchronized (buffer) {
            return isCurrentBuffer(studentId, turnId, buffer)
                    && buffer.state == AudioTurnState.FLUSHED;
        }
    }

    public boolean isAudioRecording(Integer studentId, String turnId) {
        AudioBuffer buffer = matchingBuffer(studentId, turnId);
        if (buffer == null) {
            return false;
        }
        synchronized (buffer) {
            return isCurrentBuffer(studentId, turnId, buffer)
                    && buffer.state == AudioTurnState.RECORDING;
        }
    }

    public byte[] audioBytes(Integer studentId) {
        AudioBuffer buffer = audioBuffers.getIfPresent(studentId);
        if (buffer == null) {
            return null;
        }
        synchronized (buffer) {
            return buffer.released.get() ? null : buffer.output.toByteArray();
        }
    }

    public byte[] audioBytes(Integer studentId, String turnId) {
        AudioBuffer buffer = matchingBuffer(studentId, turnId);
        if (buffer == null) {
            return null;
        }
        synchronized (buffer) {
            if (!isCurrentBuffer(studentId, turnId, buffer)
                    || buffer.state != AudioTurnState.FLUSHED) {
                return null;
            }
            return buffer.output.toByteArray();
        }
    }

    public boolean hasAudio(Integer studentId) {
        return audioBuffers.getIfPresent(studentId) != null;
    }

    public void clearAudio(Integer studentId) {
        audioBuffers.invalidate(studentId);
    }

    public boolean clearAudio(Integer studentId, String turnId) {
        AudioBuffer buffer = matchingBuffer(studentId, turnId);
        return buffer != null && audioBuffers.asMap().remove(studentId, buffer);
    }

    public boolean discardRecordingAudio(Integer studentId, String turnId) {
        AudioBuffer buffer = matchingBuffer(studentId, turnId);
        if (buffer == null) {
            return false;
        }
        synchronized (buffer) {
            if (!isCurrentBuffer(studentId, turnId, buffer)
                    || buffer.state != AudioTurnState.RECORDING) {
                return false;
            }
            return audioBuffers.asMap().remove(studentId, buffer);
        }
    }

    public long totalAudioBytes() {
        return totalAudioBytes.get();
    }

    public void putLiveQuestions(String key, List<InterviewLiveQuestion> questions) {
        liveQuestions.put(key, List.copyOf(questions));
    }

    public List<InterviewLiveQuestion> getLiveQuestions(String key) {
        return liveQuestions.getIfPresent(key);
    }

    public void putFollowUp(String key, String followUp) {
        followUps.put(key, followUp);
    }

    public String getFollowUp(String key) {
        return followUps.getIfPresent(key);
    }

    public void clearInterview(String interviewKey) {
        liveQuestions.invalidate(interviewKey);
        followUps.asMap().keySet().removeIf(key -> key.startsWith(interviewKey + ":"));
    }

    /**
     * Releases all process-local state owned by one student when their control
     * connection ends. Durable Redis state is intentionally left intact so a
     * reconnect can restore the interview, while large audio buffers and local
     * cache copies are released immediately.
     */
    public void clearStudent(Integer studentId) {
        clearAudio(studentId);
        String keyPrefix = studentId + ":";
        liveQuestions.asMap().keySet().removeIf(key -> key.startsWith(keyPrefix));
        followUps.asMap().keySet().removeIf(key -> key.startsWith(keyPrefix));
    }

    public void cleanUp() {
        audioBuffers.cleanUp();
        liveQuestions.cleanUp();
        followUps.cleanUp();
    }

    private boolean reserve(int bytes) {
        while (true) {
            long current = totalAudioBytes.get();
            if (current > maxTotalAudioBytes - bytes) {
                return false;
            }
            if (totalAudioBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    private boolean startAudioInternal(Integer studentId, String turnId, int initialCapacity) {
        AudioBuffer current = audioBuffers.getIfPresent(studentId);
        if (current != null) {
            synchronized (current) {
                if (isCurrentBuffer(studentId, turnId, current)) {
                    return current.state == AudioTurnState.RECORDING;
                }
                if (audioBuffers.getIfPresent(studentId) == current
                        && !current.released.get()
                        && current.state == AudioTurnState.FLUSHED) {
                    return false;
                }
            }
        }
        audioBuffers.invalidate(studentId);
        audioBuffers.put(studentId, new AudioBuffer(turnId, Math.max(0, initialCapacity)));
        return true;
    }

    private AudioBuffer matchingBuffer(Integer studentId, String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return null;
        }
        AudioBuffer buffer = audioBuffers.getIfPresent(studentId);
        return buffer != null && Objects.equals(buffer.turnId, turnId) ? buffer : null;
    }

    private boolean isCurrentBuffer(Integer studentId, String turnId, AudioBuffer buffer) {
        return audioBuffers.getIfPresent(studentId) == buffer
                && !buffer.released.get()
                && Objects.equals(buffer.turnId, turnId);
    }

    private void release(AudioBuffer buffer) {
        if (buffer == null) {
            return;
        }
        synchronized (buffer) {
            if (buffer.released.compareAndSet(false, true)) {
                totalAudioBytes.addAndGet(-buffer.output.size());
                buffer.output.reset();
            }
        }
    }

    private static int liveQuestionWeight(String key, List<InterviewLiveQuestion> questions) {
        long weight = key.length();
        for (InterviewLiveQuestion question : questions) {
            weight += length(question.getQuestionDimension());
            weight += length(question.getQuestion());
            weight += length(question.getFollowQuestion());
        }
        return boundedWeight(weight);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static int boundedWeight(long value) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, value));
    }

    private static final class AudioBuffer {
        private final String turnId;
        private final ByteArrayOutputStream output;
        private final AtomicBoolean released = new AtomicBoolean();
        private AudioTurnState state = AudioTurnState.RECORDING;

        private AudioBuffer(String turnId, int initialCapacity) {
            this.turnId = turnId;
            this.output = new ByteArrayOutputStream(initialCapacity);
        }
    }

    private enum AudioTurnState {
        RECORDING,
        FLUSHED
    }
}
