package system.assessment.defense.prepared.speech;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.math.*;
import java.net.URI;
import java.time.*;
import java.util.Set;

/** Character-billed native TTS, independently configured and sharing the existing test budget. */
@Component
public class PreparedTtsPolicy {
    public static final String ENDPOINT="https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    public record Availability(boolean enabled,String reason) {}
    public record Snapshot(String endpoint,String model,String voice,String language,String billingReference,String pricingReference,
                           String verifiedAt,long maxBilledCharacters,BigDecimal characterRate,String budgetScope) {
        public Snapshot { characterRate=PreparedJobInput.money(characterRate,false).stripTrailingZeros(); }
        public BigDecimal maximumCost(){return cost(maxBilledCharacters,characterRate);}
    }
    private final Environment env;private final Clock clock;
    @Autowired public PreparedTtsPolicy(Environment env){this(env,Clock.systemUTC());}
    public PreparedTtsPolicy(Environment env,Clock clock){this.env=env;this.clock=clock;}
    public boolean workerEnabled(){return Boolean.parseBoolean(env.getProperty("prepared.speech.worker-enabled","false"));}
    public Availability availability(){
        try {snapshot();return new Availability(true,"播报费用计入本轮 100 元共享预算");}
        catch(IllegalArgumentException|DateTimeException error){return new Availability(false,"播报尚未启用：独立凭据、当前价格或最高费用依据未核验");}
    }
    public Snapshot snapshot(){
        if(!Boolean.parseBoolean(env.getProperty("prepared.models.enabled","false")) || !workerEnabled())throw invalid();
        apiKey();String endpoint=required("endpoint");validateEndpoint(endpoint);
        String model=required("model"),voice=required("voice"),language=required("language");
        if(!"qwen3-tts-flash".equals(model) || !"Cherry".equals(voice) || !Set.of("Chinese","English","Auto").contains(language))throw invalid();
        String billing=required("billing-reference");if(!billing.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}"))throw invalid();
        String reference=required("pricing-reference");URI uri=URI.create(reference);
        if(!"https".equals(uri.getScheme()) || !"help.aliyun.com".equals(uri.getHost()) || uri.getUserInfo()!=null || uri.getPort()!=-1 || uri.getFragment()!=null)throw invalid();
        String date=required("pricing-verified-at");Instant verified=Instant.parse(date),now=clock.instant();
        if(verified.isAfter(now) || verified.isBefore(now.minus(Duration.ofDays(7))))throw invalid();
        long chars=Long.parseLong(required("max-billed-characters"));if(chars<600 || chars>1000000)throw invalid();
        BigDecimal rate=PreparedJobInput.money(new BigDecimal(required("cny-per-ten-thousand-characters")),false);
        if(rate.compareTo(new BigDecimal("1000"))>0)throw invalid();
        String scope=PreparedJobInput.scope(env.getProperty("prepared.models.budget-scope","prepared-model-tests"));
        var snapshot=new Snapshot(endpoint,model,voice,language,billing,reference,date,chars,rate,scope);
        if(snapshot.maximumCost().compareTo(new BigDecimal("80"))>=0)throw invalid();
        return snapshot;
    }
    public String apiKey(){String key=required("api-key");if(key.length()>512 || key.chars().anyMatch(Character::isWhitespace))throw invalid();return key;}
    public BigDecimal budgetCap(){return new BigDecimal(env.getProperty("prepared.models.test-budget-cny","100"));}
    public BigDecimal pauseAt(){return new BigDecimal(env.getProperty("prepared.models.pause-cny","80"));}
    public BigDecimal actualCost(Snapshot snapshot,long characters){if(characters<0 || characters>100000000)throw invalid();return cost(characters,snapshot.characterRate());}
    public static void validateEndpoint(String value){if(!ENDPOINT.equals(value))throw invalid();}
    private String required(String name){String value=env.getProperty("prepared.speech."+name,"").trim();if(value.isBlank() || value.length()>1000)throw invalid();return value;}
    private static BigDecimal cost(long characters,BigDecimal rate){return BigDecimal.valueOf(characters).multiply(rate).divide(new BigDecimal("10000"),6,RoundingMode.CEILING);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("播报费用控制配置未通过核验");}
}
