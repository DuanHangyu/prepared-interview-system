package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.attempt.PreparedSupplement;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PreparedSupplementCommandTest {
    String raw(){return "{\"idempotencyKey\":\""+UUID.randomUUID()+"\",\"expectedRevision\":0,\"expectedAttemptRevision\":4,\"manifestSha256\":\""+"a".repeat(64)+"\",\"uploadDeadline\":\"2026-10-08T03:00:00.123Z\",\"reason\":\"核验原录音后批准补传\"}";}
    @Test void browserIsoWholeMillisecondsNormalizeToOneImmutableDeadline(){
        var command=PreparedSupplement.decode(raw().replace(".123Z",".000Z").getBytes(StandardCharsets.UTF_8));
        assertThat(command.uploadDeadline()).isEqualTo("2026-10-08T03:00:00Z");
    }
    @Test void explicitVersionsOriginalManifestDeadlineAndReasonAreRequired(){
        var command=PreparedSupplement.decode(raw().getBytes(StandardCharsets.UTF_8));
        assertThat(command.expectedRevision()).isZero();assertThat(command.expectedAttemptRevision()).isEqualTo(4);
        assertThat(command.uploadDeadline()).isEqualTo("2026-10-08T03:00:00.123Z");
        String good=raw();
        for(String bad:List.of(good.replace("\"expectedRevision\":0,",""),good.replace("\"expectedAttemptRevision\":4,",""),
                good.replace("\"expectedRevision\":0","\"expectedRevision\":\"0\""),good.replace("\"expectedRevision\":0","\"expectedRevision\":0.5"),
                good.replace("\"expectedRevision\":0","\"expectedRevision\":-1"),good.replace("\"expectedAttemptRevision\":4","\"expectedAttemptRevision\":9007199254740992"),
                good.replace("\"expectedRevision\":0","\"expectedRevision\":9007199254740991"),good.replace("\"expectedAttemptRevision\":4","\"expectedAttemptRevision\":-1"),
                good.replace("{","{\"studentId\":1,"),good.replace("\"expectedRevision\":0","\"expectedRevision\":0,\"expectedRevision\":0"),good+"{}",
                good.replace("a".repeat(64),"A".repeat(64)),good.replace("核验原录音后批准补传"," "),good.replace("核验原录音后批准补传","x".repeat(1001)),
                good.replace("2026-10-08T03:00:00.123Z","2026-10-08T03:00:00.123456Z"),good.replace("2026-10-08T03:00:00.123Z","not-a-time"),
                good.replace("2026-10-08T03:00:00.123Z","0999-10-08T03:00:00Z"),good.replace("2026-10-08T03:00:00.123Z","+010000-10-08T03:00:00Z"),
                good.replace("2026-10-08T03:00:00.123Z","2026-10-08T03:00:00+00:00")))
            assertThatThrownBy(()->PreparedSupplement.decode(bad.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedSupplement.decode(new byte[]{(byte)0xc3,0x28})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedSupplement.decode(new byte[16385])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedSupplement.decode(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
