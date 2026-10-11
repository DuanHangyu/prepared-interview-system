package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.prepared.retention.PreparedRetentionProtection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PreparedRetentionProtectionTest {
    private static final String KEY=UUID.randomUUID().toString();
    private String input(String fields){return "{\"idempotencyKey\":\""+KEY+"\","+fields+"}";}
    @Test void boundedOriginalCommandPreservesReasonAndExactVersion(){
        var command=PreparedRetentionProtection.decode(input("\"expectedRevision\":0,\"action\":\"PROTECT\",\"reason\":\"核验原尝试，保留材料\"").getBytes(StandardCharsets.UTF_8));
        assertThat(command).isEqualTo(new PreparedRetentionProtection.Command(KEY,0,"PROTECT","核验原尝试，保留材料"));
        assertThat(new PreparedRetentionProtection.Command(KEY,9007199254740990L,"RELEASE","人工核验已结束").expectedRevision()).isEqualTo(9007199254740990L);
    }
    @ParameterizedTest @ValueSource(strings={
        "\"action\":\"PROTECT\",\"reason\":\"核验\"",
        "\"expectedRevision\":null,\"action\":\"PROTECT\",\"reason\":\"核验\"",
        "\"expectedRevision\":\"0\",\"action\":\"PROTECT\",\"reason\":\"核验\"",
        "\"expectedRevision\":0.5,\"action\":\"PROTECT\",\"reason\":\"核验\"",
        "\"expectedRevision\":-1,\"action\":\"PROTECT\",\"reason\":\"核验\"",
        "\"expectedRevision\":9007199254740991,\"action\":\"PROTECT\",\"reason\":\"核验\"",
        "\"expectedRevision\":0,\"action\":\"protect\",\"reason\":\"核验\"",
        "\"expectedRevision\":0,\"action\":null,\"reason\":\"核验\"",
        "\"expectedRevision\":0,\"action\":\"DELETE\",\"reason\":\"核验\"",
        "\"expectedRevision\":0,\"action\":\"RELEASE\",\"reason\":\"  \"",
        "\"expectedRevision\":0,\"action\":\"PROTECT\",\"reason\":null",
        "\"expectedRevision\":0,\"action\":\"PROTECT\",\"reason\":\"核验\",\"held\":false",
        "\"expectedRevision\":0,\"expectedRevision\":1,\"action\":\"PROTECT\",\"reason\":\"核验\""
    }) void rejectsMissingCoercedUnsafeOrReboundCommands(String fields){
        assertThatThrownBy(()->PreparedRetentionProtection.decode(input(fields).getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsInvalidUtf8OversizedAndTrailingInputWithoutEchoingContent(){
        assertThatThrownBy(()->PreparedRetentionProtection.decode(new byte[]{(byte)0xc3,0x28})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedRetentionProtection.decode(new byte[16385])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedRetentionProtection.decode(null)).isInstanceOf(IllegalArgumentException.class);
        String valid=input("\"expectedRevision\":0,\"action\":\"PROTECT\",\"reason\":\"核验\"");
        assertThatThrownBy(()->PreparedRetentionProtection.decode((valid+" {}").getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedRetentionProtection.Command(KEY,0,"PROTECT","x".repeat(1001))).isInstanceOf(IllegalArgumentException.class);
        for(String reason:new String[]{"\u0000","\ud800","\udc00"})assertThatThrownBy(()->new PreparedRetentionProtection.Command(KEY,0,"PROTECT",reason)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedRetentionProtection.Command("invalid",0,"PROTECT","private reason")).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private reason");
    }
}
