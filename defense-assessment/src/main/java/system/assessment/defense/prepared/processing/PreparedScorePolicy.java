package system.assessment.defense.prepared.processing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.generation.PreparedGenerationPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Set;

/** Separate credentials, pinned parameter-compatible models and verified worst-tier pricing.
 * Reserve the FULL verified model context, not a text-length/token estimate. This is deliberately
 * conservative; real context/quota/pricing verification remains an operator launch prerequisite. */
@Component
public class PreparedScorePolicy {
    private final Environment env;private final PreparedGenerationPolicy billing;
    @Autowired public PreparedScorePolicy(Environment env){this(env,Clock.systemUTC());}
    public PreparedScorePolicy(Environment env,Clock clock){this.env=env;billing=new PreparedGenerationPolicy(env,clock,"prepared.scoring.");}
    public boolean runtimeEnabled(){return Boolean.parseBoolean(env.getProperty("prepared.models.enabled","false"))&&billing.workerEnabled();}
    public PreparedGenerationPolicy.Snapshot snapshot(){
        var p=billing.snapshot();long context=verifiedContextTokenLimit();
        validateHistorical(p);
        if(context<4096||context>10000000||p.maxInputTokens()<context)throw new IllegalArgumentException("完整模型上下文费用上界未核验");
        return p;
    }
    public static void validateHistorical(PreparedGenerationPolicy.Snapshot p){
        if(p==null)throw new IllegalArgumentException("评分模型快照缺失");PreparedGenerationPolicy.validateEndpoint(p.endpoint());
        if(!Set.of("qwen3.6-plus","qwen3.6-plus-2026-04-02").contains(p.model())||p.maxOutputTokens()<4106)
            throw new IllegalArgumentException("评分型号或含十 token 余量的输出费用上界未核验");
    }
    public String apiKey(){return billing.apiKey();}
    public long verifiedContextTokenLimit(){return Long.parseLong(env.getProperty("prepared.scoring.verified-context-token-limit","0"));}
    public BigDecimal budgetCap(){return billing.budgetCap();}
    public BigDecimal pauseAt(){return billing.pauseAt();}
    public BigDecimal actualCost(PreparedGenerationPolicy.Snapshot p,long input,long output){return billing.actualCost(p,input,output);}
}
