package system.assessment.defense.prepared.processing;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.attempt.PreparedSubmission;
import system.assessment.defense.prepared.generation.PreparedGenerationPolicy;
import system.assessment.defense.prepared.jobs.*;
import java.util.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;

/** Original receipt is never mutated. Paid synchronous send once, durable reply/fee checkpoint,
 * and fresh authorized result publication independent of the model request's old lease. */
@Component
public class PreparedScoreWorker {
    public record Input(int schemaVersion,PreparedSubmission.Work original,long expectedRevision,PreparedProcessingService.RegradeInput regrade,long verifiedContextTokenLimit,
                        PreparedScoreClient.Input request,PreparedGenerationPolicy.Snapshot policy){}
    private final PreparedJobLedger jobs;private final PreparedProcessingService results;private final PreparedScorePolicy policy;private final PreparedScoreClient client;
    public PreparedScoreWorker(PreparedJobLedger jobs,PreparedProcessingService results,PreparedScorePolicy policy,PreparedScoreClient client){this.jobs=jobs;this.results=results;this.policy=policy;this.client=client;}
    @Scheduled(scheduler="preparedScoreScheduler",initialDelay=30000,fixedDelay=3000) public void runOnce(){
        if(!policy.runtimeEnabled())return;var claimed=jobs.claim(Kind.SCORE,"score-worker",300);if(claimed.isEmpty())return;var lease=claimed.orElseThrow();
        Optional<ScoreCall> saved=Optional.empty();PreparedProcessingService.Plan plan;
        try{saved=jobs.scoreCall(lease.job().id());plan=results.plan(lease);}catch(RuntimeException invalid){saved.ifPresent(c->jobs.uncertain(c.callId()));jobs.fail(lease,"SCORE_ORIGINAL_BINDING_INVALID",false);return;}
        if(saved.isEmpty()){send(lease,plan);return;}
        var checkpoint=saved.orElseThrow();Input input;
        try{input=PreparedScoreClient.decode(checkpoint.inputJson(),Input.class);PreparedScorePolicy.validateHistorical(input.policy());
            if(input.schemaVersion()!=1||input.verifiedContextTokenLimit()<4096||input.verifiedContextTokenLimit()>input.policy().maxInputTokens()||!input.original().equals(plan.original().binding().input())||input.expectedRevision()!=plan.expectedRevision()
                ||!Objects.equals(input.regrade(),plan.regrade())||!input.request().equals(request(plan))||checkpoint.replyJson()==null)throw new IllegalArgumentException("评分原输入或已知响应缺失");
        }catch(RuntimeException invalid){jobs.uncertain(checkpoint.callId());jobs.fail(lease,"SCORE_CHECKPOINT_INVALID",false);return;}
        publish(lease,checkpoint.replyJson(),input,plan);
    }
    private PreparedScoreClient.Input request(PreparedProcessingService.Plan plan){
        var q=plan.original().question();var r=plan.source();var in=new PreparedScoreClient.Input(1,q.questionId(),q.body(),q.rubric(),q.maxScore(),r.id(),r.contentSha256(),plan.transcript().sentences());in.validate();return in;
    }
    private void send(Lease lease,PreparedProcessingService.Plan plan){
        String call=UUID.randomUUID().toString();boolean reserved=false,dispatched=false;
        try{
            var p=policy.snapshot();var input=new Input(1,plan.original().binding().input(),plan.expectedRevision(),plan.regrade(),policy.verifiedContextTokenLimit(),request(plan),p);String snapshot=PreparedScoreClient.encode(input);
            var fresh=results.plan(lease);if(!input.original().equals(fresh.original().binding().input())||!input.request().equals(request(fresh)))throw new IllegalArgumentException("原输入授权变化");
            jobs.initializeBudget(p.budgetScope(),policy.budgetCap(),policy.pauseAt());var approved=jobs.reserve(lease,p.budgetScope(),call,p.maximumCost());
            if(!approved.approved()){jobs.fail(lease,"SCORE_BUDGET_NOT_APPROVED",false);return;}reserved=true;
            if(!jobs.dispatchScore(lease,call,snapshot)){jobs.cancelBeforeDispatch(call);jobs.fail(lease,"SCORE_DISPATCH_NOT_APPROVED",false);return;}dispatched=true;
            var reply=client.score(p,input.request(),policy.apiKey());
            // Credible fees and raw reply persist atomically BEFORE score interpretation.
            String raw=PreparedScoreClient.encode(reply);jobs.saveScoreReply(lease,call,raw,policy.actualCost(p,reply.inputTokens(),reply.outputTokens()),reply.providerRequestId());
            publish(lease,raw,input,plan);
        }catch(Exception failure){
            if(dispatched){var known=jobs.scoreCall(lease.job().id());
                if(known.isPresent()&&known.orElseThrow().replyJson()!=null){jobs.retryScorePublication(lease);return;}
                jobs.uncertain(call);jobs.fail(lease,"SCORE_RESPONSE_UNKNOWN",false);
            }else{if(reserved&&!jobs.cancelBeforeDispatch(call))jobs.uncertain(call);jobs.fail(lease,"SCORE_PREPARATION_FAILED",false);}
        }
    }
    private void publish(Lease lease,String raw,Input input,PreparedProcessingService.Plan plan){
        try{
            var reply=PreparedScoreClient.decode(raw,PreparedScoreClient.Reply.class);
            if(!"stop".equals(reply.finishReason()))throw new IllegalArgumentException("评分不完整");
            var score=PreparedProcessingCodec.score(reply.content(),plan.original().question(),plan.source(),plan.transcript());
            results.completeScore(lease,PreparedProcessingCodec.encode(score),new PreparedProcessingService.Execution("dashscope",input.policy().model(),PreparedJobInput.digest(PreparedScoreClient.encode(input.policy()))));
        }catch(org.springframework.dao.TransientDataAccessException temporary){jobs.retryScorePublication(lease);}
        catch(RuntimeException invalid){jobs.fail(lease,"SCORE_RESULT_OR_PUBLICATION_INVALID",false);}
    }
}
