package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import system.assessment.defense.prepared.generation.PreparedGenerationPolicy;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class PreparedGenerationPolicyTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC);
    MockEnvironment configured() {
        return new MockEnvironment().withProperty("prepared.models.enabled", "true")
                .withProperty("prepared.generation.worker-enabled", "true")
                .withProperty("prepared.generation.api-key", "fixture-not-a-cloud-key")
                .withProperty("prepared.generation.endpoint", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
                .withProperty("prepared.generation.model", "fixture-model")
                .withProperty("prepared.generation.billing-reference", "isolated-fixture-account")
                .withProperty("prepared.generation.pricing-reference", "https://help.aliyun.com/zh/model-studio/models")
                .withProperty("prepared.generation.pricing-verified-at", "2026-10-03T00:00:00Z")
                .withProperty("prepared.generation.max-billed-input-tokens", "1000000")
                .withProperty("prepared.generation.max-billed-output-tokens", "65536")
                .withProperty("prepared.generation.input-cny-per-million", "1")
                .withProperty("prepared.generation.output-cny-per-million", "5");
    }
    @Test void defaultMissingOrDisabledConfigurationCannotAuthorizeAPaidCall() {
        assertThat(new PreparedGenerationPolicy(new MockEnvironment(), clock).availability().enabled()).isFalse();
        for (String field : java.util.List.of("api-key", "billing-reference", "pricing-reference", "pricing-verified-at",
                "model", "max-billed-input-tokens", "max-billed-output-tokens", "input-cny-per-million", "output-cny-per-million")) {
            var env = configured().withProperty("prepared.generation." + field, "");
            assertThat(new PreparedGenerationPolicy(env, clock).availability().enabled()).isFalse();
        }
        assertThat(new PreparedGenerationPolicy(configured().withProperty("prepared.models.enabled", "false"), clock).availability().enabled()).isFalse();
    }
    @Test void boundsAllBilledTokensAndRoundsUpWithoutLeakingCredentials() {
        var policy = new PreparedGenerationPolicy(configured(), clock);
        assertThat(policy.availability().enabled()).isTrue();
        assertThat(policy.snapshot().maximumCost()).isEqualByComparingTo("1.327680");
        assertThat(policy.snapshot().toString()).doesNotContain("fixture-not-a-cloud-key");
        assertThat(policy.actualCost(policy.snapshot(), 100, 200)).isEqualByComparingTo("0.001100");
        assertThatThrownBy(() -> policy.actualCost(policy.snapshot(), -1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsUntrustedEndpointsExpiredPricingAndInvalidBounds() {
        for (String url : java.util.List.of("http://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                "https://evil.example/compatible-mode/v1/chat/completions", "https://dashscope.aliyuncs.com@evil.example/x",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions?key=leak"))
            assertThat(new PreparedGenerationPolicy(configured().withProperty("prepared.generation.endpoint", url), clock).availability().enabled()).isFalse();
        for (String value : java.util.List.of("2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "bad"))
            assertThat(new PreparedGenerationPolicy(configured().withProperty("prepared.generation.pricing-verified-at", value), clock).availability().enabled()).isFalse();
        for (String value : java.util.List.of("0", "-1", "NaN", "1000000000"))
            assertThat(new PreparedGenerationPolicy(configured().withProperty("prepared.generation.max-billed-output-tokens", value), clock).availability().enabled()).isFalse();
    }
    @Test void rejectsMalformedCredentialPriceReferenceRatesAndUnsafeMaximumCost() {
        for(String[] field:java.util.List.of(new String[]{"api-key","key with whitespace"},new String[]{"api-key","x".repeat(513)},
                new String[]{"model","model/other"},new String[]{"billing-reference","account with spaces"},
                new String[]{"pricing-reference","http://help.aliyun.com/price"},new String[]{"pricing-reference","https://evil.example/price"},
                new String[]{"pricing-reference","https://user@help.aliyun.com/price"},new String[]{"model","x".repeat(1001)},
                new String[]{"input-cny-per-million","0"},new String[]{"input-cny-per-million","1001"},new String[]{"output-cny-per-million","-1"},
                new String[]{"output-cny-per-million","0.0000001"},new String[]{"input-cny-per-million","80"},new String[]{"max-billed-input-tokens","4095"}))
            assertThat(new PreparedGenerationPolicy(configured().withProperty("prepared.generation."+field[0],field[1]),clock).availability().enabled()).isFalse();
        assertThat(new PreparedGenerationPolicy(configured().withProperty("prepared.generation.worker-enabled","false"),clock).availability().enabled()).isFalse();
        for(String url:java.util.List.of("https://dashscope.aliyuncs.com:443/compatible-mode/v1/chat/completions",
                "https://user@dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "https://dashscope.aliyuncs.com/other",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions#fragment","invalid uri"))
            assertThat(new PreparedGenerationPolicy(configured().withProperty("prepared.generation.endpoint",url),clock).availability().enabled()).isFalse();
        var policy=new PreparedGenerationPolicy(configured(),clock);
        for(long[] pair:java.util.List.of(new long[]{0,-1},new long[]{100000001,0},new long[]{0,100000001}))
            assertThatThrownBy(()->policy.actualCost(policy.snapshot(),pair[0],pair[1])).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PreparedGenerationPolicy(configured().withProperty("prepared.generation.endpoint","https://fixture.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions"),clock).availability().enabled()).isTrue();
    }
}
