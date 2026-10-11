package system.assessment.defense.application.service;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterviewSessionStoreTest {

    @Test
    void rejectsOversizedSessionAndReleasesItsReservedMemory() {
        MutableTicker ticker = new MutableTicker();
        InterviewSessionStore store = new InterviewSessionStore(8, 16, 1_024, Duration.ofMinutes(5), ticker);

        store.startAudio(10, 4);
        store.appendAudio(10, ByteBuffer.wrap(new byte[6]));

        assertThatThrownBy(() -> store.appendAudio(10, ByteBuffer.wrap(new byte[3])))
                .isInstanceOf(BusinessException.class);
        assertThat(store.hasAudio(10)).isFalse();
        assertThat(store.totalAudioBytes()).isZero();
    }

    @Test
    void enforcesGlobalAudioLimitAcrossStudents() {
        MutableTicker ticker = new MutableTicker();
        InterviewSessionStore store = new InterviewSessionStore(10, 12, 1_024, Duration.ofMinutes(5), ticker);

        store.startAudio(10, 4);
        store.appendAudio(10, ByteBuffer.wrap(new byte[8]));
        store.startAudio(11, 4);

        assertThatThrownBy(() -> store.appendAudio(11, ByteBuffer.wrap(new byte[5])))
                .isInstanceOf(BusinessException.class);
        assertThat(store.audioBytes(10)).hasSize(8);
        assertThat(store.hasAudio(11)).isFalse();
        assertThat(store.totalAudioBytes()).isEqualTo(8);
    }

    @Test
    void expiresAudioAndQuestionCachesAfterInactivity() {
        MutableTicker ticker = new MutableTicker();
        InterviewSessionStore store = new InterviewSessionStore(32, 64, 1_024, Duration.ofMinutes(5), ticker);
        InterviewLiveQuestion question = new InterviewLiveQuestion("技术", "请解释缓存策略", false, null);

        store.startAudio(10, 4);
        store.appendAudio(10, ByteBuffer.wrap(new byte[4]));
        store.putLiveQuestions("10:20", List.of(question));
        store.putFollowUp("10:20:1", "请说明淘汰策略");

        ticker.advance(Duration.ofMinutes(6));
        store.cleanUp();

        assertThat(store.hasAudio(10)).isFalse();
        assertThat(store.getLiveQuestions("10:20")).isNull();
        assertThat(store.getFollowUp("10:20:1")).isNull();
        assertThat(store.totalAudioBytes()).isZero();
    }

    @Test
    void clearingInterviewRemovesAllQuestionAndFollowUpState() {
        InterviewSessionStore store = new InterviewSessionStore(
                32, 64, 1_024, Duration.ofMinutes(5), Ticker.systemTicker());
        store.putLiveQuestions("10:20", List.of(
                new InterviewLiveQuestion("技术", "问题一", false, null)));
        store.putFollowUp("10:20:1", "追问一");
        store.putFollowUp("10:20:2", "追问二");
        store.putFollowUp("10:21:1", "其他考核追问");

        store.clearInterview("10:20");

        assertThat(store.getLiveQuestions("10:20")).isNull();
        assertThat(store.getFollowUp("10:20:1")).isNull();
        assertThat(store.getFollowUp("10:20:2")).isNull();
        assertThat(store.getFollowUp("10:21:1")).isEqualTo("其他考核追问");
    }

    @Test
    void clearingStudentReleasesAudioAndOnlyThatStudentsLocalCaches() {
        InterviewSessionStore store = new InterviewSessionStore(
                32, 64, 1_024, Duration.ofMinutes(5), Ticker.systemTicker());
        InterviewLiveQuestion question = new InterviewLiveQuestion("技术", "问题一", false, null);
        store.startAudio(10, "turn-1", 4);
        store.appendAudio(10, "turn-1", ByteBuffer.wrap(new byte[]{1, 2, 3}));
        store.putLiveQuestions("10:20", List.of(question));
        store.putFollowUp("10:20:1", "追问一");
        store.putLiveQuestions("11:20", List.of(question));
        store.putFollowUp("11:20:1", "其他学生追问");

        store.clearStudent(10);

        assertThat(store.hasAudio(10)).isFalse();
        assertThat(store.totalAudioBytes()).isZero();
        assertThat(store.getLiveQuestions("10:20")).isNull();
        assertThat(store.getFollowUp("10:20:1")).isNull();
        assertThat(store.getLiveQuestions("11:20")).containsExactly(question);
        assertThat(store.getFollowUp("11:20:1")).isEqualTo("其他学生追问");
    }

    @Test
    void delayedOldTurnFramesCannotCreateOrContaminateAnotherTurn() {
        InterviewSessionStore store = new InterviewSessionStore(
                32, 64, 1_024, Duration.ofMinutes(5), Ticker.systemTicker());

        store.startAudio(10, "turn-1", 4);
        assertThat(store.appendAudio(10, "turn-1", ByteBuffer.wrap(new byte[]{1, 2}))).isTrue();
        assertThat(store.flushAudio(10, "turn-1")).isTrue();
        assertThat(store.clearAudio(10, "turn-1")).isTrue();

        assertThat(store.appendAudio(10, "turn-1", ByteBuffer.wrap(new byte[]{9}))).isFalse();
        assertThat(store.hasAudio(10)).isFalse();

        store.startAudio(10, "turn-2", 4);
        assertThat(store.appendAudio(10, "turn-1", ByteBuffer.wrap(new byte[]{8}))).isFalse();
        assertThat(store.appendAudio(10, "turn-2", ByteBuffer.wrap(new byte[]{3, 4}))).isTrue();
        assertThat(store.flushAudio(10, "turn-2")).isTrue();

        assertThat(store.audioBytes(10, "turn-2")).containsExactly(3, 4);
    }

    @Test
    void binaryFrameWithoutStartedTurnIsRejectedWithoutAllocatingMemory() {
        InterviewSessionStore store = new InterviewSessionStore(
                32, 64, 1_024, Duration.ofMinutes(5), Ticker.systemTicker());

        assertThat(store.appendAudio(10, "missing-turn", ByteBuffer.wrap(new byte[]{1, 2}))).isFalse();

        assertThat(store.hasAudio(10)).isFalse();
        assertThat(store.totalAudioBytes()).isZero();
    }

    @Test
    void flushedTurnBlocksTheNextTurnUntilFinalizationClearsIt() {
        InterviewSessionStore store = new InterviewSessionStore(
                32, 64, 1_024, Duration.ofMinutes(5), Ticker.systemTicker());
        store.startAudio(10, "turn-1", 4);
        store.appendAudio(10, "turn-1", ByteBuffer.wrap(new byte[]{1, 2}));
        store.flushAudio(10, "turn-1");

        assertThat(store.startAudio(10, "turn-2", 4)).isFalse();
        assertThat(store.clearAudio(10, "turn-1")).isTrue();
        assertThat(store.startAudio(10, "turn-2", 4)).isTrue();
    }

    private static final class MutableTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
