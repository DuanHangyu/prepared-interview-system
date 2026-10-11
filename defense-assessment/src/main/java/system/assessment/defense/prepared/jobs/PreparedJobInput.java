package system.assessment.defense.prepared.jobs;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Bounded, canonical task payloads. Never include rejected payloads in exceptions. */
public final class PreparedJobInput {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private PreparedJobInput() {}

    public static String json(String value, int maxBytes) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new IllegalArgumentException("任务内容为空或超过大小限制");
        try {
            JsonNode node = JSON.readTree(value);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("任务内容必须是 JSON 对象");
            return JSON.writeValueAsString(canonical(node));
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("任务内容格式错误");
        }
    }
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode(); List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add); Collections.sort(names);
            for (String name : names) result.set(name, canonical(node.get(name)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode(); node.forEach(child -> result.add(canonical(child))); return result;
        }
        return node;
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("无法计算任务摘要"); }
    }
    public static String uuid(String value) {
        try { if (value != null && UUID.fromString(value).toString().equals(value)) return value; }
        catch (IllegalArgumentException ignored) {}
        throw new IllegalArgumentException("任务或调用标识无效");
    }
    public static String scope(String value) {
        if (value == null || !value.matches("prepared-[a-z0-9][a-z0-9-]{0,86}"))
            throw new IllegalArgumentException("独立预算范围标识无效");
        return value;
    }
    public static BigDecimal money(BigDecimal amount, boolean allowZero) {
        if (amount == null || amount.signum() < 0 || (!allowZero && amount.signum() == 0)
                || amount.stripTrailingZeros().scale() > 6 || amount.compareTo(new BigDecimal("1000000")) >= 0)
            throw new IllegalArgumentException("费用必须是有界的非负人民币金额，最多六位小数");
        return amount.setScale(6);
    }
}
