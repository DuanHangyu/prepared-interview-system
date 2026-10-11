package system.assessment.defense.prepared.speech;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.*;
import system.assessment.defense.prepared.media.PreparedSpeechText;
import system.assessment.defense.prepared.paper.PreparedPaperRepository;
import system.assessment.defense.prepared.storage.*;
import system.assessment.defense.prepared.audio.PreparedAudioRepository;
import java.nio.file.Path;
import java.util.UUID;

/** One sequential bounded paid call per leased part; no provider call on a student request.
 * Known charges are settled before download/save. Any sent failure is review-only, not paid retry.
 */
@Component
public class PreparedTtsWorker {
    private final PreparedJobLedger ledger;private final PreparedTtsPolicy policy;private final PreparedTtsClient client;
    private final PreparedStoragePolicy storagePolicy;private final PreparedPrivateOssStore store;private final PreparedSpeechSpool spool;
    private final PreparedPaperRepository papers;private final ResourceAccessControlService access;
    private final PreparedAudioRepository audio;
    public PreparedTtsWorker(PreparedJobLedger ledger,PreparedTtsPolicy policy,PreparedTtsClient client,
                             PreparedStoragePolicy storagePolicy,PreparedPrivateOssStore store,PreparedSpeechSpool spool,
                             PreparedPaperRepository papers,ResourceAccessControlService access,PreparedAudioRepository audio) {
        this.ledger=ledger;this.policy=policy;this.client=client;this.storagePolicy=storagePolicy;this.store=store;
        this.spool=spool;this.papers=papers;this.access=access;this.audio=audio;
    }
    @Scheduled(scheduler="preparedPreparationScheduler",initialDelay=30000,fixedDelay=3000)
    public void runOnce(){
        if(!policy.workerEnabled())return;
        var candidate=ledger.claim(Kind.TTS,"tts-worker",300);if(candidate.isEmpty())return;
        Lease lease=candidate.get();String call=UUID.randomUUID().toString();boolean reserved=false,sent=false,settled=false;
        String failure="CONFIG_OR_ACCESS_CHANGED";
        try {
            var input=PreparedSpeech.part(lease.job().inputJson());access.requireAssessmentManager(lease.job().actorId(),lease.job().assessmentId());
            if(!audio.partIsWanted(lease.job().id()))throw new IllegalArgumentException("整题任务已结束");
            if(!input.policy().equals(policy.snapshot()))throw new IllegalArgumentException("播报配置变化");
            var storage=storagePolicy.snapshot();
            var question=papers.load(lease.job().assessmentId()).questions().stream().filter(q->q.questionId().equals(input.questionId())).findFirst().orElseThrow();
            if(!PreparedJobInput.digest(question.body()).equals(input.bodySha256()))throw new IllegalArgumentException("题目文字变化");
            var parts=PreparedSpeechText.split(question.body());
            if(parts.size()!=input.partCount() || !parts.get(input.partIndex()).equals(input.text()))throw new IllegalArgumentException("分段不匹配");
            String key=policy.apiKey();Path target=spool.stage(lease);
            ledger.initializeBudget(input.policy().budgetScope(),policy.budgetCap(),policy.pauseAt());
            if(!ledger.reserve(lease,input.policy().budgetScope(),call,input.policy().maximumCost()).approved()) {
                ledger.fail(lease,"BUDGET_PAUSED",false);return;
            }
            reserved=true;
            if(!ledger.markDispatched(lease,call)) {
                ledger.cancelBeforeDispatch(call);ledger.fail(lease,"DISPATCH_NOT_AUTHORIZED",false);return;
            }
            sent=true;var reply=client.synthesize(input.policy(),input.text(),key);
            ledger.settle(call,policy.actualCost(input.policy(),reply.characters()),reply.providerId());settled=true;failure="INVALID_OUTPUT";
            if(reply.characters()>input.policy().maxBilledCharacters() || !"stop".equals(reply.finishReason()) || reply.audioUri()==null)
                throw new IllegalArgumentException("播报结果不完整");
            if(!ledger.heartbeat(lease,300))return;failure="AUDIO_SAVE_FAILED";
            var downloaded=client.download(reply.audioUri(),target);if(!ledger.heartbeat(lease,300))return;
            var scope=new PreparedPrivateOssStore.Broadcast(lease.job().assessmentId(),input.questionId(),input.assetId());
            var saved=store.putBroadcast(scope,target);
            if(!scope.equals(saved.scope()) || !scope.key(storage.deploymentId()).equals(saved.objectKey()) || !downloaded.equals(saved.proof()))
                throw new IllegalArgumentException("保存内容不一致");
            if(ledger.succeed(lease,PreparedSpeech.encode(new PreparedSpeech.PartOutput(1,input.questionId(),input.assetId(),saved.objectKey(),saved.proof()))))
                try{spool.release(lease,target);}catch(java.io.IOException ignored){ /* Verified remote proof remains durable; cleanup can retry later. */ }
        }catch(Exception error){
            // No teacher text, vendor bodies, signed URLs or secrets in public errors/logs.
            if(sent && !settled){ledger.uncertain(call);ledger.fail(lease,"AMBIGUOUS_CALL",false);}
            else if(settled)ledger.fail(lease,failure,false);
            else{if(reserved)ledger.cancelBeforeDispatch(call);ledger.fail(lease,"CONFIG_OR_ACCESS_CHANGED",false);}
        }
    }
}
