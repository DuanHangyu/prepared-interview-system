package system.assessment.defense.prepared.processing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.media.PreparedWaveAudio;
import java.math.*;
import java.net.URI;
import java.time.*;

/** Independent file-ASR config. Test rates are not official prices or proof of model quota. */
@Component
public class PreparedAsrPolicy {
    public static final String ENDPOINT="https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription";
    public record Availability(boolean enabled,String reason) {}
    public record Snapshot(String endpoint,String model,String billingReference,String pricingReference,String verifiedAt,BigDecimal secondRate,String budgetScope) {
        public Snapshot {secondRate=PreparedJobInput.money(secondRate,false).stripTrailingZeros();}
        public BigDecimal cost(long seconds){if(seconds<0||seconds>100000000)throw invalid();return PreparedJobInput.money(BigDecimal.valueOf(seconds).multiply(secondRate).setScale(6,RoundingMode.CEILING),true);}
        public BigDecimal maximumCost(PreparedWaveAudio.Verified audio){
            if(audio==null||audio.channels()!=1||audio.sampleRate()!=16000||audio.durationMs()<1||audio.durationMs()>1800000)throw invalid();
            return cost((audio.durationMs()+999)/1000);
        }
    }
    private final Environment env;private final Clock clock;
    @Autowired public PreparedAsrPolicy(Environment env){this(env,Clock.systemUTC());}
    public PreparedAsrPolicy(Environment env,Clock clock){this.env=env;this.clock=clock;}
    public boolean runtimeEnabled(){try{return "true".equals(env.getProperty("prepared.models.enabled","false"))&&"true".equals(env.getProperty("prepared.asr.worker-enabled","false"))&&!apiKey().isBlank();}catch(IllegalArgumentException e){return false;}}
    public Availability availability(){try{snapshot();return new Availability(true,"文件转写费用计入本轮 100 元共享预算");}catch(RuntimeException e){return new Availability(false,"独立文件转写凭据、当前价格及配额尚待核验");}}
    public Snapshot snapshot(){
        if(!runtimeEnabled())throw invalid();String endpoint=required("endpoint"),model=required("model"),billing=required("billing-reference");validateEndpoint(endpoint);
        if(!"qwen3-asr-flash-filetrans".equals(model)||!billing.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}"))throw invalid();
        String reference=required("pricing-reference");URI ref=URI.create(reference);
        if(!"https".equals(ref.getScheme())||!"help.aliyun.com".equals(ref.getHost())||ref.getUserInfo()!=null||ref.getPort()!=-1||ref.getFragment()!=null)throw invalid();
        String date=required("pricing-verified-at");Instant verified=Instant.parse(date),now=clock.instant();if(verified.isAfter(now)||verified.isBefore(now.minus(Duration.ofDays(7))))throw invalid();
        var rate=PreparedJobInput.money(new BigDecimal(required("cny-per-second")),false);
        var s=new Snapshot(endpoint,model,billing,reference,date,rate,PreparedJobInput.scope(env.getProperty("prepared.models.budget-scope","prepared-model-tests")));
        if(s.cost(1800).compareTo(new BigDecimal("80"))>=0)throw invalid();return s;
    }
    /** Existing task is queried with the same independent identity; price updates never reprice it. */
    public boolean canPoll(Snapshot previous){try{return runtimeEnabled()&&previous!=null&&required("endpoint").equals(previous.endpoint())&&required("model").equals(previous.model())&&required("billing-reference").equals(previous.billingReference());}catch(RuntimeException e){return false;}}
    public String apiKey(){String key=required("api-key");if(key.length()>512||key.chars().anyMatch(Character::isWhitespace))throw invalid();return key;}
    public BigDecimal budgetCap(){return new BigDecimal(env.getProperty("prepared.models.test-budget-cny","100"));}
    public BigDecimal pauseAt(){return new BigDecimal(env.getProperty("prepared.models.pause-cny","80"));}
    public static void validateEndpoint(String value){try{URI u=URI.create(value);String h=u.getHost();
        if(!"https".equals(u.getScheme())||h==null||!(h.equals("dashscope.aliyuncs.com")||h.matches("[a-z0-9-]{1,64}\\.cn-beijing\\.maas\\.aliyuncs\\.com"))
            ||u.getUserInfo()!=null||u.getPort()!=-1||u.getQuery()!=null||u.getFragment()!=null||!"/api/v1/services/audio/asr/transcription".equals(u.getRawPath()))throw invalid();
    }catch(RuntimeException e){throw invalid();}}
    private String required(String name){String value=env.getProperty("prepared.asr."+name,"").trim();if(value.isBlank()||value.length()>1000)throw invalid();return value;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("文件转写费用或归属配置未通过核验");}
}
