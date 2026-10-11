package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.generation.PreparedGenerationCodec;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PreparedGenerationCodecTest {
    final PreparedGenerationCodec codec = new PreparedGenerationCodec();
    final String job = "11111111-1111-4111-8111-111111111111";
    final String valid = "{\"questions\":[{\"body\":\"说明一种方案\",\"rubric\":\"依据充分\",\"maxScore\":10,\"answerSeconds\":120}]}";

    @Test void strictSuggestionsHaveServerDerivedStableIdsAndNoApprovalOrExecutableFields() {
        var result = codec.decode(job, valid, 1, 10, 120);
        assertThat(result).hasSize(1);
        assertThat(UUID.fromString(result.getFirst().questionId()).toString()).isEqualTo(result.getFirst().questionId());
        assertThat(codec.decode(job, valid, 1, 10, 120)).isEqualTo(result);
        assertThat(codec.decode("22222222-2222-4222-8222-222222222222", valid, 1, 10, 120)).isNotEqualTo(result);
    }
    @Test void rejectsMalformedDuplicateTrailingUnknownOrWrongTypedModelData() {
        for (String data : List.of("null", "[]", "{}", valid + " {}", valid.replace("\"body\":", "\"body\":\"重复\",\"body\":"),
                valid.replace("\"body\":", "\"approved\":true,\"body\":"), valid.replace("10,", "10.5,"),
                valid.replace("10,", "\"10\","), valid.replace("说明一种方案", " "),
                valid.replace("120}", "0}"), valid.replace("依据充分", "x".repeat(20001)),
                valid.replace("说明一种方案", "x".repeat(10001)), "x".repeat(262145))) {
            assertThatThrownBy(() -> codec.decode(job, data, 1, 10, 120)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsCountAndRequestedRuleChangesAndBadIdentity() {
        assertThatThrownBy(() -> codec.decode(job, valid, 2, 10, 120)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(job, valid, 1, 20, 120)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(job, valid, 1, 10, 121)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("../job", valid, 1, 10, 120)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void nullArraysOversizedIntegersAndAllRulesOutsideBoundsAreRejected() {
        for(String raw:List.of("{\"questions\":null}","{\"questions\":{}}","{\"questions\":[null]}",
                valid.replace("10,","100000000000,"),valid.replace("120}","100000000000}"),
                valid.replace("120}","\"120\"}"),valid.replace("120}","120.5}"),valid.replace("\"依据充分\"","null"),
                valid.replace("\"说明一种方案\"","null"),valid.replace("依据充分"," ")))
            assertThatThrownBy(()->codec.decode(job,raw,1,10,120)).isInstanceOf(IllegalArgumentException.class);
        for(int count:List.of(0,21)) assertThatThrownBy(()->codec.decode(job,valid,count,10,120)).isInstanceOf(IllegalArgumentException.class);
        for(int score:List.of(0,10001)) assertThatThrownBy(()->codec.decode(job,valid.replace("10,",score+","),1,score,120)).isInstanceOf(IllegalArgumentException.class);
        for(int seconds:List.of(0,1801)) assertThatThrownBy(()->codec.decode(job,valid.replace("120}",seconds+"}"),1,10,seconds)).isInstanceOf(IllegalArgumentException.class);
    }
}
