package system.assessment.defense.prepared.generation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.prepared.jobs.PreparedJobLedger;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.*;
import java.math.BigDecimal;
import java.util.UUID;

/** Sequential paid calls outside all database transactions; never on the student request path. */
@Component
public class PreparedGenerationWorker {
    private final PreparedJobLedger ledger;
    private final PreparedGenerationPolicy policy;
    private final PreparedGenerationClient client;
    private final PreparedGenerationCodec codec;
    private final ResourceAccessControlService access;
    public PreparedGenerationWorker(PreparedJobLedger ledger,PreparedGenerationPolicy policy,PreparedGenerationClient client,
                                    PreparedGenerationCodec codec,ResourceAccessControlService access) {
        this.ledger=ledger;this.policy=policy;this.client=client;this.codec=codec;this.access=access;
    }
    @Scheduled(scheduler="preparedPreparationScheduler",initialDelay=30000,fixedDelay=3000)
    public void runOnce() {
        if(!policy.workerEnabled()) return;
        var optional=ledger.claim(Kind.QUESTIONS,"generation-worker",300);if(optional.isEmpty()) return;
        Lease lease=optional.get();String call=UUID.randomUUID().toString();boolean reserved=false,sent=false,settled=false;
        try {
            var input=PreparedGeneration.input(lease.job().inputJson());
            access.requireAssessmentManager(lease.job().actorId(),lease.job().assessmentId());
            if(!input.policy().equals(policy.snapshot())) throw new IllegalArgumentException("配置变化");
            String key=policy.apiKey();
            ledger.initializeBudget(input.policy().budgetScope(),policy.budgetCap(),policy.pauseAt());
            if(!ledger.reserve(lease,input.policy().budgetScope(),call,input.policy().maximumCost()).approved()) {
                ledger.fail(lease,"BUDGET_PAUSED",false);return;
            }
            reserved=true;
            if(!ledger.markDispatched(lease,call)) {
                ledger.cancelBeforeDispatch(call);ledger.fail(lease,"DISPATCH_NOT_AUTHORIZED",false);return;
            }
            sent=true;
            var reply=client.generate(input.policy(),input.command(),key);
            ledger.settle(call,policy.actualCost(input.policy(),reply.inputTokens(),reply.outputTokens()),reply.providerId());settled=true;
            if(reply.inputTokens()>input.policy().maxInputTokens() || reply.outputTokens()>input.policy().maxOutputTokens()
                    || !"stop".equals(reply.finishReason())) throw new IllegalArgumentException("输出边界不符合要求");
            var command=input.command();
            codec.decode(lease.job().id(),reply.content(),command.count(),command.maxScore(),command.answerSeconds());
            ledger.succeed(lease,reply.content());
        } catch(Exception error) {
            // Do not log secrets, teacher text or vendor response bodies. A failed DB write leaves the lease for recovery.
            if(sent && !settled) {ledger.uncertain(call);ledger.fail(lease,"AMBIGUOUS_CALL",false);}
            else if(settled) ledger.fail(lease,"INVALID_OUTPUT",false);
            else {if(reserved) ledger.cancelBeforeDispatch(call);ledger.fail(lease,"CONFIG_OR_ACCESS_CHANGED",false);}
        }
    }
}
