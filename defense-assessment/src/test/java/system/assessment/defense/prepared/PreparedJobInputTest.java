package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class PreparedJobInputTest {
    @Test void jsonIsCanonicalWithoutLosingArrayOrderOrUnicode() {
        assertThat(PreparedJobInput.json("{\"b\":2,\"a\":[\"题目\",1]}", 65536))
                .isEqualTo("{\"a\":[\"题目\",1],\"b\":2}");
        assertThat(PreparedJobInput.digest("内容")).hasSize(64);
    }
    @Test void malformedDuplicateTrailingAndNonObjectPayloadsAreRejected() {
        for (String value : new String[]{"{", "{\"a\":1,\"a\":2}", "{} {}", "[]", "null", ""}) {
            assertThatThrownBy(() -> PreparedJobInput.json(value, 65536)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> PreparedJobInput.json(null, 65536)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PreparedJobInput.json("{\"text\":\"" + "题".repeat(22000) + "\"}", 65536))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void identifiersAndMoneyCannotBeAmbiguousOrUnbounded() {
        assertThat(PreparedJobInput.uuid("11111111-1111-4111-8111-111111111111")).isNotNull();
        assertThat(PreparedJobInput.scope("prepared-local-model-tests-v1")).isNotNull();
        assertThat(PreparedJobInput.money(new BigDecimal("0.000001"), false)).isEqualByComparingTo("0.000001");
        for (String id : new String[]{"1-1-1-1-1", "../other", "", "11111111-1111-4111-8111-11111111111A"}) {
            assertThatThrownBy(() -> PreparedJobInput.uuid(id)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String scope : new String[]{"legacy-tests", "prepared-../escape", "prepared-" + "a".repeat(100)}) {
            assertThatThrownBy(() -> PreparedJobInput.scope(scope)).isInstanceOf(IllegalArgumentException.class);
        }
        for (BigDecimal amount : new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("0.0000001"), new BigDecimal("1000000")}) {
            assertThatThrownBy(() -> PreparedJobInput.money(amount, false)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> PreparedJobInput.money(null, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
