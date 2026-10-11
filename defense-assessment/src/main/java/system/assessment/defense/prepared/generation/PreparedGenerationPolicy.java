package system.assessment.defense.prepared.generation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.math.*;
import java.net.URI;
import java.time.*;

/** Fail closed until independent billing identity and conservative current pricing are verified. */
@Component
public class PreparedGenerationPolicy {
    public record Availability(boolean enabled, String reason) {}
    public record Snapshot(String endpoint, String model, String billingReference, String pricingReference,
                           String verifiedAt, long maxInputTokens, long maxOutputTokens,
                           BigDecimal inputRate, BigDecimal outputRate, String budgetScope) {
        public Snapshot {
            // JSON databases normalize decimal scale; semantic pricing equality must survive persistence.
            inputRate = PreparedJobInput.money(inputRate, false).stripTrailingZeros();
            outputRate = PreparedJobInput.money(outputRate, false).stripTrailingZeros();
        }
        public BigDecimal maximumCost() { return cost(maxInputTokens, maxOutputTokens, inputRate, outputRate); }
    }
    private final Environment env;
    private final Clock clock;
    private final String prefix;
    @Autowired public PreparedGenerationPolicy(Environment env) { this(env, Clock.systemUTC()); }
    public PreparedGenerationPolicy(Environment env, Clock clock) { this(env,clock,"prepared.generation."); }
    /** Internal composition for the same token billing/endpoint rules, not a second Spring bean. */
    public PreparedGenerationPolicy(Environment env, Clock clock, String prefix) {
        if(!java.util.Set.of("prepared.generation.","prepared.scoring.").contains(prefix))throw invalid();
        this.env = env; this.clock = clock; this.prefix=prefix;
    }
    public boolean workerEnabled() { return Boolean.parseBoolean(env.getProperty(prefix+"worker-enabled", "false")); }
    public Availability availability() {
        try { snapshot(); return new Availability(true, "可生成建议；费用计入本轮 100 元共享预算"); }
        catch (IllegalArgumentException | DateTimeException error) { return new Availability(false, "AI 备题暂未启用：独立凭据、计费范围或最高费用依据尚未核验；可继续手工备题"); }
    }
    public Snapshot snapshot() {
        if (!Boolean.parseBoolean(env.getProperty("prepared.models.enabled", "false")) || !workerEnabled()) throw invalid();
        apiKey();
        String endpoint = required("endpoint"); validateEndpoint(endpoint);
        String model = required("model");
        if (!model.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")) throw invalid();
        String billing = required("billing-reference");
        if (!billing.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")) throw invalid();
        String reference = required("pricing-reference");
        URI price = URI.create(reference);
        if (!"https".equals(price.getScheme()) || !"help.aliyun.com".equals(price.getHost()) || price.getUserInfo() != null) throw invalid();
        String date = required("pricing-verified-at");
        Instant verified = Instant.parse(date), now = clock.instant();
        if (verified.isAfter(now) || verified.isBefore(now.minus(Duration.ofDays(7)))) throw invalid();
        long input = tokens("max-billed-input-tokens"), output = tokens("max-billed-output-tokens");
        BigDecimal in = rate("input-cny-per-million"), out = rate("output-cny-per-million");
        String scope = PreparedJobInput.scope(env.getProperty("prepared.models.budget-scope", "prepared-model-tests"));
        Snapshot result = new Snapshot(endpoint, model, billing, reference, date, input, output, in, out, scope);
        if (result.maximumCost().signum() <= 0 || result.maximumCost().compareTo(new BigDecimal("80")) >= 0) throw invalid();
        return result;
    }
    public String apiKey() {
        String key = required("api-key");
        if (key.length() > 512 || key.chars().anyMatch(Character::isWhitespace)) throw invalid();
        return key;
    }
    public BigDecimal budgetCap() { return new BigDecimal(env.getProperty("prepared.models.test-budget-cny", "100")); }
    public BigDecimal pauseAt() { return new BigDecimal(env.getProperty("prepared.models.pause-cny", "80")); }
    public BigDecimal actualCost(Snapshot snapshot, long input, long output) {
        if (input < 0 || output < 0 || input > 100000000 || output > 100000000) throw invalid();
        return cost(input, output, snapshot.inputRate(), snapshot.outputRate());
    }
    public static void validateEndpoint(String value) {
        try {
            URI uri = URI.create(value); String host = uri.getHost();
            boolean allowed = "dashscope.aliyuncs.com".equals(host)
                    || host != null && host.matches("[a-z0-9-]{1,64}\\.cn-beijing\\.maas\\.aliyuncs\\.com");
            if (!allowed || !"https".equals(uri.getScheme()) || uri.getPort() != -1 || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !"/compatible-mode/v1/chat/completions".equals(uri.getPath())) throw invalid();
        } catch (RuntimeException error) { throw invalid(); }
    }
    private long tokens(String name) {
        long value = Long.parseLong(required(name));
        if (value < 4096 || value > 10000000) throw invalid(); return value;
    }
    private BigDecimal rate(String name) {
        BigDecimal value = PreparedJobInput.money(new BigDecimal(required(name)), false);
        if (value.compareTo(new BigDecimal("1000")) > 0) throw invalid(); return value;
    }
    private String required(String name) {
        String value = env.getProperty(prefix + name, "").trim();
        if (value.isBlank() || value.length() > 1000) throw invalid(); return value;
    }
    private static BigDecimal cost(long input, long output, BigDecimal in, BigDecimal out) {
        return BigDecimal.valueOf(input).multiply(in).add(BigDecimal.valueOf(output).multiply(out))
                .divide(new BigDecimal("1000000"), 6, RoundingMode.CEILING);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("AI 费用控制配置未通过核验"); }
}
