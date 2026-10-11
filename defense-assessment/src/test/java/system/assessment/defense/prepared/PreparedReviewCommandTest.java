package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.processing.PreparedReviewCommands;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PreparedReviewCommandTest {
    String raw(){return "{\"idempotencyKey\":\""+UUID.randomUUID()+"\",\"expectedRevision\":2,\"scoreId\":\""+UUID.randomUUID()+"\",\"reason\":\"已核验\"}";}
    @Test void missingFloatStringNegativeVersionExtraIdentityDuplicateAndMalformedUtf8FailClosed(){
        String good=raw();assertThat(PreparedReviewCommands.confirm(good.getBytes(StandardCharsets.UTF_8)).expectedRevision()).isEqualTo(2);
        for(String s:List.of(good.replace("\"expectedRevision\":2,",""),good.replace(":2,",":2.1,"),good.replace(":2,",":\"2\","),good.replace(":2,",":-1,"),good.replace(":2,",":9007199254740992,"),good.replace("{","{\"studentId\":1,"),good.replace(":2,",":2,\"expectedRevision\":2,"),good+"{}",good.replace("已核验"," ")))
            assertThatThrownBy(()->PreparedReviewCommands.confirm(s.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedReviewCommands.confirm(new byte[]{(byte)0xC3,0x28})).isInstanceOf(IllegalArgumentException.class);
        assertThat(PreparedReviewCommands.regrade(good.replace("scoreId","transcriptId").getBytes(StandardCharsets.UTF_8)).reason()).isEqualTo("已核验");
    }
}
