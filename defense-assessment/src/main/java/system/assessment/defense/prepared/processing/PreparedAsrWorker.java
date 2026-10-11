package system.assessment.defense.prepared.processing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.attempt.PreparedSubmission;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.storage.*;
import java.time.Clock;
import java.util.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;
import static system.assessment.defense.prepared.processing.PreparedProcessingCodec.*;

/** One paid original file submission; durable known-task GET recovery and result checkpoint.
 * No IO holds the assessment/result transaction. Technical failures never rewrite a receipt. */
@Component
public class PreparedAsrWorker {
    public record Input(int schemaVersion,PreparedSubmission.Work original,PreparedPrivateOssStore.StoredAnswer media,PreparedAsrPolicy.Snapshot policy,String inputUrlSha256) {}
    private final PreparedJobLedger jobs;private final PreparedProcessingService results;private final PreparedAsrPolicy policy;
    private final PreparedAsrClient client;private final PreparedPrivateOssStore store;private final PreparedStoragePolicy storage;private final Clock clock;
    @Autowired public PreparedAsrWorker(PreparedJobLedger jobs,PreparedProcessingService results,PreparedAsrPolicy policy,PreparedAsrClient client,PreparedPrivateOssStore store,PreparedStoragePolicy storage){this(jobs,results,policy,client,store,storage,Clock.systemUTC());}
    public PreparedAsrWorker(PreparedJobLedger jobs,PreparedProcessingService results,PreparedAsrPolicy policy,PreparedAsrClient client,PreparedPrivateOssStore store,PreparedStoragePolicy storage,Clock clock){
        this.jobs=jobs;this.results=results;this.policy=policy;this.client=client;this.store=store;this.storage=storage;this.clock=clock;
    }
    @Scheduled(scheduler="preparedAsrScheduler",initialDelay=30000,fixedDelay=3000) public void runOnce(){
        if(!policy.runtimeEnabled())return;
        var next=jobs.claim(Kind.TRANSCRIBE,"file-asr-worker",300);if(next.isEmpty())return;var lease=next.orElseThrow();
        Optional<AsyncTask> existing=Optional.empty();PreparedProcessingService.Plan plan;
        try{existing=jobs.async(lease.job().id());plan=results.plan(lease);}catch(RuntimeException invalid){
            existing.ifPresent(t->jobs.uncertain(t.callId()));jobs.fail(lease,"ASR_ORIGINAL_BINDING_INVALID",false);return;}
        if(existing.isEmpty()){submit(lease,plan);return;}
        var task=existing.orElseThrow();Input input;
        try{
            input=decode(task.inputJson(),Input.class);sha(input.inputUrlSha256());PreparedAsrPolicy.validateEndpoint(input.policy().endpoint());
            if(input.schemaVersion()!=1||!plan.original().binding().input().equals(input.original())||!plan.original().media().equals(input.media())||!policy.canPoll(input.policy()))throw new IllegalArgumentException("原转写身份或输入已变化");
            if(task.taskId()==null)throw new IllegalArgumentException("原供应商请求无已知任务");
        }catch(RuntimeException invalid){review(lease,task,"ASR_IDENTITY_OR_INPUT_CHANGED");return;}
        if(task.resultJson()!=null){publish(lease,task.resultJson(),input);return;}
        if(!clock.instant().isBefore(task.recoveryDeadline())){review(lease,task,"ASR_RESULT_WINDOW_EXPIRED");return;}
        PreparedAsrClient.Poll poll;
        try{poll=client.poll(input.policy(),task.taskId(),policy.apiKey());}
        catch(Exception temporary){jobs.retryAsync(lease,false);return;} // known GET, never paid POST
        if(poll==null||poll.status()==null||!task.taskId().equals(poll.taskId())){review(lease,task,"ASR_TASK_ID_INVALID");return;}
        jobs.pollSucceeded(lease);
        if(Set.of("PENDING","RUNNING").contains(poll.status())){jobs.yieldAsync(lease,30);return;}
        if(!Set.of("SUCCEEDED","FAILED").contains(poll.status())||poll.seconds()==null){review(lease,task,"ASR_TERMINAL_FEE_UNKNOWN");return;}
        try{jobs.settle(task.callId(),input.policy().cost(poll.seconds()),task.taskId());}
        catch(RuntimeException unclear){review(lease,task,"ASR_FEE_CONFLICT");return;}
        if(!"SUCCEEDED".equals(poll.status())||poll.resultUri()==null||poll.seconds()==0){jobs.fail(lease,"ASR_RESULT_INVALID",false);return;}
        PreparedProcessing.Transcript transcript;
        try{transcript=client.download(poll.resultUri(),input.policy(),input.media(),input.inputUrlSha256());}
        catch(Exception unavailable){jobs.retryAsync(lease,true);return;} // re-fetch known result; no re-transcribe
        String content;
        try{content=encode(transcript);transcript(content,input.media().proof().sha256(),input.media().proof().durationMs());
            if(!"dashscope".equals(transcript.provider())||!input.policy().model().equals(transcript.model()))throw new IllegalArgumentException("原模型不匹配");
            if(!jobs.saveAsyncResult(lease,content))return;
        }catch(RuntimeException invalid){jobs.fail(lease,"ASR_RESULT_INVALID",false);return;}
        publish(lease,content,input);
    }
    private void submit(Lease lease,PreparedProcessingService.Plan plan){
        String call=UUID.randomUUID().toString();boolean reserved=false,dispatched=false;
        system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal.Handle io=null;
        try{
            var p=policy.snapshot();if(!storage.availability().enabled())throw new IllegalArgumentException("独立存储不可用");
            io=results.beginTranscriptionAudioIo(lease,plan);
            var media=plan.original().media();var url=store.asrInput(media);PreparedAsrUrls.input(url.url(),media);
            if(java.time.Instant.parse(url.expiresAt()).isBefore(clock.instant().plusSeconds(82800)))throw new IllegalArgumentException("原录音读取期限不足");
            var input=new Input(1,plan.original().binding().input(),media,p,PreparedJobInput.digest(url.url()));
            // A large private read may outlive a role/assignment change. Recheck before any fee fence.
            var current=results.plan(lease);
            if(!current.original().binding().input().equals(input.original())||!current.original().media().equals(media))throw new IllegalArgumentException("原转写授权或内容已变化");
            results.recordAsrInputAccess(lease,media,java.time.Instant.parse(url.expiresAt()),clock.instant());
            jobs.initializeBudget(p.budgetScope(),policy.budgetCap(),policy.pauseAt());
            var decision=jobs.reserve(lease,p.budgetScope(),call,p.maximumCost(media.proof()));
            if(!decision.approved()){jobs.fail(lease,"ASR_BUDGET_NOT_APPROVED",false);return;}reserved=true;
            if(!jobs.dispatchAsync(lease,call,encode(input))){jobs.cancelBeforeDispatch(call);jobs.fail(lease,"ASR_DISPATCH_NOT_APPROVED",false);return;}dispatched=true;
            String task=client.submit(p,url,media,policy.apiKey());jobs.acceptAsync(lease,call,task);jobs.yieldAsync(lease,10);
        }catch(Exception unavailable){
            if(dispatched){
                // A DB response may have been lost AFTER the known ID was committed. Recover it,
                // never turn that harmless scheduling failure into another paid submission.
                var known=jobs.async(lease.job().id());if(known.isPresent()&&known.orElseThrow().taskId()!=null){jobs.yieldAsync(lease,30);return;}
                jobs.uncertain(call);jobs.fail(lease,"ASR_SUBMIT_OUTCOME_UNKNOWN",false);
            }else{if(reserved&&!jobs.cancelBeforeDispatch(call))jobs.uncertain(call);jobs.fail(lease,"ASR_PREPARATION_FAILED",false);}
        }finally{if(io!=null)results.endAudioIo(io);}
    }
    private void publish(Lease lease,String content,Input input){
        try{results.completeTranscript(lease,content,new PreparedProcessingService.Execution("dashscope",input.policy().model(),PreparedJobInput.digest(encode(input.policy()))));}
        catch(org.springframework.dao.TransientDataAccessException temporary){jobs.retryAsync(lease,true);}
        catch(RuntimeException unavailable){jobs.fail(lease,"ASR_PUBLICATION_FAILED",false);}
    }
    private void review(Lease lease,AsyncTask task,String code){jobs.uncertain(task.callId());jobs.fail(lease,code,false);}
}
