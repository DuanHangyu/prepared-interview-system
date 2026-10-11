package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.attempt.PreparedAnswerBundle;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PreparedAnswerBundleCommandTest {
    final String client=UUID.randomUUID().toString(),segment=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString(),answer=UUID.randomUUID().toString();
    final PreparedAnswerBundle.Chunk chunk=new PreparedAnswerBundle.Chunk(0,3,"a".repeat(64));
    PreparedAnswerBundle.Command command(List<PreparedAnswerBundle.Chunk> chunks){return new PreparedAnswerBundle.Command(client,segment,1,"audio/webm",chunks,3,"a".repeat(64));}
    @Test void canonicalOriginalCommandIsImmutableDeterministicAndIdentityBound() {
        var mutable=new ArrayList<>(List.of(chunk));var command=command(mutable);mutable.clear();
        assertThat(command.chunks()).containsExactly(chunk);assertThatThrownBy(()->command.chunks().clear()).isInstanceOf(UnsupportedOperationException.class);
        String json=PreparedAnswerBundle.encode(command);assertThat(PreparedAnswerBundle.decode(json)).isEqualTo(command);
        var scope=command.scope(12,8,attempt,answer);assertThat(command.scope(12,8,attempt,answer)).isEqualTo(scope);
        assertThat(command.scope(13,8,attempt,answer).id()).isNotEqualTo(scope.id());assertThat(command.scope(12,9,attempt,answer).id()).isNotEqualTo(scope.id());
        assertThat(command.originalChunks()).hasSize(1);assertThat(command.originalChunks().getFirst().scope(12,8,attempt,answer)).isEqualTo(scope.chunks().getFirst());
    }
    @Test void invalidGroupScopesCountsSizeFormatOrderAndDigestAreRejected() {
        for(var chunks:List.of(List.<PreparedAnswerBundle.Chunk>of(),Collections.nCopies(33,chunk),List.of(chunk,chunk)))
            assertThatThrownBy(()->command(chunks).scope(12,8,attempt,answer)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->command(null).scope(12,8,attempt,answer)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->command(Collections.singletonList(null)).scope(12,8,attempt,answer)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedAnswerBundle.Command(client,segment,0,"audio/webm",List.of(chunk),3,"a".repeat(64)).scope(12,8,attempt,answer)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedAnswerBundle.Command("lost",segment,1,"audio/webm",List.of(chunk),3,"a".repeat(64)).scope(12,8,attempt,answer)).isInstanceOf(IllegalArgumentException.class);
        for(String id:List.of("../old","A".repeat(64),"a".repeat(63)))assertThatThrownBy(()->PreparedAnswerBundle.id(id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedAnswerBundle.id(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedAnswerBundle.decode("{")).isInstanceOf(IllegalStateException.class);
    }
}
