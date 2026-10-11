package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Test-harness regression only; no database, transport or cloud acceptance. */
class PreparedReviewFixtureContractTest {
    @Test void reusedReviewTestRemainsDiscoverableAndSummaryKeepsItsOwnBackendScope() {
        var constructors = PreparedReviewLiveHttpTest.class.getDeclaredConstructors();
        assertThat(constructors).as("JUnit test classes must retain one no-argument constructor").hasSize(1);
        assertThat(constructors[0].getParameterCount()).isZero();
        var original = new PreparedReviewLiveHttpTest();
        String defaultBase = "http://127.0.0.1:" + System.getenv().getOrDefault("PREPARED_FORMAL_HTTP_PORT", "13016");
        assertThat(original.base).isEqualTo(defaultBase);
        var summary = new PreparedReviewSummaryLiveHttpTest();
        assertThat(summary.http.base).isEqualTo("http://127.0.0.1:13018");
        assertThat(original.base).isEqualTo(defaultBase);
    }
}
