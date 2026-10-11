package system.assessment.defense.prepared.audio;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.media.PreparedSpeechText;
import system.assessment.defense.prepared.paper.*;
import system.assessment.defense.prepared.publication.PreparedPublicationRepository;
import system.assessment.defense.prepared.speech.*;
import system.assessment.defense.prepared.storage.*;
import java.util.*;
import static system.assessment.defense.prepared.audio.PreparedAudio.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind;

@Service
public class PreparedAudioService {
    private final ResourceAccessControlService access;private final PreparedPaperRepository papers;
    private final PreparedPublicationRepository publications;private final PreparedAudioRepository audio;
    private final PreparedJobLedger jobs;private final PreparedTtsPolicy policy;private final PreparedStoragePolicy storage;private final PreparedSpeechSpool spool;
    public PreparedAudioService(ResourceAccessControlService access,PreparedPaperRepository papers,PreparedPublicationRepository publications,
                                PreparedAudioRepository audio,PreparedJobLedger jobs,PreparedTtsPolicy policy,PreparedStoragePolicy storage,PreparedSpeechSpool spool){
        this.access=access;this.papers=papers;this.publications=publications;this.audio=audio;this.jobs=jobs;this.policy=policy;this.storage=storage;this.spool=spool;
    }
    @Transactional(readOnly=true) public Listing list(int actor,int assessment){
        access.requireAssessmentManager(actor,assessment);var draft=papers.load(assessment);
        var current=draft.questions().stream().map(q->audio.latest(assessment,q.questionId(),PreparedJobInput.digest(q.body())))
                .flatMap(Optional::stream).map(PreparedAudioRepository.Asset::view).toList();
        return new Listing(assessment,draft.revision(),publications.gate(assessment).frozen(),availability(),current,audio.history(assessment).stream().map(PreparedAudioRepository.Asset::view).toList());
    }
    public View get(int actor,int assessment,String id){access.requireAssessmentManager(actor,assessment);return asset(assessment,id).view();}
    public PreparedAudioRepository.Asset asset(int assessment,String id){
        return audio.get(assessment,PreparedJobInput.uuid(id)).orElseThrow(()->new BusinessException(403,"播报不存在或无权限"));
    }
    public Availability availability(){
        try{var p=policy.snapshot();var s=storage.snapshot();if(!spool.configurationAvailable() || !spool.assemblyEnabled())throw new IllegalArgumentException();
            var budget=jobs.initializedBudget(p.budgetScope());if(budget.isPresent() && (budget.get().paused()
                    || budget.get().spent().add(budget.get().reserved()).compareTo(budget.get().pauseAt())>=0))
                return new Availability(false,"共享测试预算已暂停，需先核账",null,null,null,null);
            return new Availability(true,"播报计入 100 元共享测试预算，80 元前暂停；已成功分段可复用",PreparedJobInput.digest(PreparedSpeech.encode(List.of(p,s))),p.model(),p.voice(),p.maximumCost());
        }catch(RuntimeException error){return new Availability(false,"播报尚未启用：独立计费、当前价格、私有存储或暂存目录未核验",null,null,null,null);}
    }
    @Transactional public View prepare(int actor,int assessment,Command command){
        access.requireAssessmentManager(actor,assessment);validate(command);var draft=papers.loadForUpdate(assessment);
        String hash=PreparedJobInput.digest(PreparedSpeech.encode(List.of("prepare",command.expectedRevision(),command.questionId(),command.expectedConfigurationSha256())));
        var old=audio.receipt(actor,assessment,command.idempotencyKey());
        if(old.isPresent()){if(!old.get().hash().equals(hash))throw conflict("相同命令不能用于不同播报请求");return asset(assessment,old.get().assetId()).view();}
        if(draft.revision()!=command.expectedRevision())throw conflict("草稿已变化，请重新读取后确认播报");
        if(publications.gate(assessment).frozen())throw conflict("已有学生开考，不能准备新播报");
        var question=draft.questions().stream().filter(q->q.questionId().equals(command.questionId())).findFirst().orElseThrow(()->new BusinessException(403,"题目不存在或无权限"));
        if(!question.approved())throw conflict("请先审核当前题目");
        var available=availability();if(!available.enabled())throw new BusinessException(503,available.reason());
        if(!command.expectedConfigurationSha256().equals(available.confirmationSha256()))throw conflict("费用或播报配置已变化，请重新确认");
        var snapshot=policy.snapshot();String body=PreparedJobInput.digest(question.body()),config=configuration(snapshot);
        var active=audio.active(assessment,question.questionId(),body,config);
        if(active.isPresent()){
            if(active.get().view().state().equals("READY")){attach(draft,question.questionId(),body,active.get().key(),actor);audio.receipt(actor,assessment,command.idempotencyKey(),hash,active.get().id());return active.get().view();}
            if(!active.get().view().state().equals("FAILED"))throw conflict("该题已有播报准备记录，请查看原任务");
            supersede(active.get());
        }
        List<String> parts=PreparedSpeechText.split(question.body());List<String> children=new ArrayList<>(),dependencies=new ArrayList<>();String asset=UUID.randomUUID().toString();int newCount=0;
        for(int index=0;index<parts.size();index++){
            String cached=cached(assessment,question.questionId(),body,config,index,parts);if(cached!=null){children.add(cached);if(jobs.get(cached).orElseThrow().actorId()==actor)dependencies.add(cached);continue;}
            var input=new PreparedSpeech.PartInput(1,question.questionId(),UUID.randomUUID().toString(),body,index,parts.size(),parts.get(index),snapshot);
            var job=jobs.enqueue(actor,assessment,Kind.TTS,draft.revision(),UUID.randomUUID().toString(),PreparedSpeech.encode(input));
            audio.part(job.id(),assessment,question.questionId(),body,config,index,parts.size());children.add(job.id());dependencies.add(job.id());newCount++;
        }
        var input=new WholeInput(1,question.questionId(),asset,question.body(),body,config,children);input.validate();
        var parent=jobs.enqueueAfter(actor,assessment,Kind.ASSEMBLE,draft.revision(),UUID.randomUUID().toString(),PreparedSpeech.encode(input),dependencies);
        audio.insert(asset,assessment,question.questionId(),body,config,parent.id(),parts.size(),newCount,snapshot.maximumCost().multiply(java.math.BigDecimal.valueOf(newCount)));
        for(int i=0;i<children.size();i++)audio.reference(assessment,asset,i,children.get(i));
        audio.receipt(actor,assessment,command.idempotencyKey(),hash,asset);return asset(assessment,asset).view();
    }
    @Transactional public View recover(int actor,int assessment,String id,Recovery command){
        access.requireAssessmentManager(actor,assessment);PreparedJobInput.uuid(id);
        if(command==null || command.expectedRevision()==null || command.expectedRevision()<0)throw new IllegalArgumentException("恢复命令无效");PreparedJobInput.uuid(command.idempotencyKey());
        var draft=papers.loadForUpdate(assessment);String hash=PreparedJobInput.digest(PreparedSpeech.encode(List.of("recover",id,command.expectedRevision())));
        var receipt=audio.receipt(actor,assessment,command.idempotencyKey());if(receipt.isPresent()){
            if(!receipt.get().hash().equals(hash))throw conflict("相同命令不能用于不同恢复请求");return asset(assessment,receipt.get().assetId()).view();}
        if(draft.revision()!=command.expectedRevision() || publications.gate(assessment).frozen())throw conflict("草稿已变化或考核已开考");
        var original=asset(assessment,id);if(!original.view().assemblyRecoverable())throw conflict("分段尚未全部可靠保存，不能仅重试合成");
        var oldInput=whole(jobs.get(original.jobId()).orElseThrow().inputJson());
        var question=draft.questions().stream().filter(q->q.questionId().equals(original.question())).findFirst().orElseThrow(()->new BusinessException(403,"题目不存在或无权限"));
        if(!question.approved() || !PreparedJobInput.digest(question.body()).equals(original.bodySha()))throw conflict("请先审核当前题目，已修改的题目不能复用旧播报");
        var active=audio.active(assessment,question.questionId(),original.bodySha(),original.configSha());
        if(active.isPresent() && !active.get().id().equals(id))throw conflict("已有新的播报任务，请查看该任务");
        try{storage.snapshot();if(!spool.configurationAvailable() || !spool.assemblyEnabled())throw new IllegalArgumentException();}
        catch(RuntimeException unavailable){throw new BusinessException(503,"独立存储、播报合成或暂存目录未就绪，已保存分段保留");}
        supersede(original);String next=UUID.randomUUID().toString();var children=audio.partJobs(assessment,id);
        if(!children.equals(oldInput.orderedPartJobs()))throw conflict("播报分段绑定不完整");
        var dependencies=children.stream().filter(child->jobs.get(child).orElseThrow().actorId()==actor).toList();
        var input=new WholeInput(1,question.questionId(),next,oldInput.body(),original.bodySha(),original.configSha(),children);
        var parent=jobs.enqueueAfter(actor,assessment,Kind.ASSEMBLE,draft.revision(),UUID.randomUUID().toString(),PreparedSpeech.encode(input),dependencies);
        audio.insert(next,assessment,question.questionId(),original.bodySha(),original.configSha(),parent.id(),children.size(),0,java.math.BigDecimal.ZERO);
        for(int i=0;i<children.size();i++)audio.reference(assessment,next,i,children.get(i));audio.receipt(actor,assessment,command.idempotencyKey(),hash,next);
        return asset(assessment,next).view();
    }
    private void supersede(PreparedAudioRepository.Asset old){
        var children=audio.partJobs(old.assessment(),old.id());
        for(String child:children){var job=jobs.get(child).orElseThrow();if(job.state()==PreparedJobLedger.State.RUNNING)throw conflict("旧播报分段仍在处理，请稍后再恢复");
            if((job.state()==PreparedJobLedger.State.QUEUED || job.state()==PreparedJobLedger.State.RETRY_WAIT) && !jobs.cancelQueuedUnsent(child))throw conflict("旧播报费用尚待核账");}
        audio.failed(old.assessment(),old.id());
    }
    private String cached(int assessment,String question,String body,String config,int index,List<String> parts){
        for(String id:audio.cached(assessment,question,body,config,index))try{
            var job=jobs.get(id).orElseThrow();var in=PreparedSpeech.part(job.inputJson());var out=PreparedSpeech.output(job.outputJson());
            if(in.questionId().equals(question) && in.bodySha256().equals(body) && in.partIndex()==index && in.partCount()==parts.size()
                    && in.text().equals(parts.get(index)) && configuration(in.policy()).equals(config) && out.questionId().equals(question) && out.assetId().equals(in.assetId())
                    && out.objectKey().equals(new PreparedPrivateOssStore.Broadcast(assessment,question,in.assetId()).key(storage.snapshot().deploymentId())))return id;
        }catch(RuntimeException ignored){ /* Corrupt history is never adopted as proof. */ }
        return null;
    }
    /** Internal worker result boundary. One transaction fences the lease, proof and draft revision. */
    @Transactional public void complete(PreparedJobLedger.Lease lease,PreparedPrivateOssStore.Stored saved){
        int assessment=lease.job().assessmentId(),actor=lease.job().actorId();var draft=papers.loadForUpdate(assessment);access.requireAssessmentManager(actor,assessment);
        var input=whole(lease.job().inputJson());var original=asset(assessment,input.assetId());var expected=new PreparedPrivateOssStore.Broadcast(assessment,input.questionId(),input.assetId());
        if(lease.job().kind()!=Kind.ASSEMBLE || original.assessment()!=assessment || !original.question().equals(input.questionId())
                || !original.jobId().equals(lease.job().id()) || !original.bodySha().equals(input.bodySha256()) || !original.configSha().equals(input.configSha256())
                || saved==null || !expected.equals(saved.scope()) || !expected.key(storage.snapshot().deploymentId()).equals(saved.objectKey()) || saved.proof()==null
                || !audio.partJobs(assessment,input.assetId()).equals(input.orderedPartJobs()) || original.view().completedParts()!=original.view().partCount()
                || !jobs.heartbeat(lease,300))throw conflict("播报任务结果已过期或绑定不一致");
        // Re-validate the persisted proof schema before storing a public READY descriptor.
        PreparedSpeech.output(PreparedSpeech.encode(new PreparedSpeech.PartOutput(1,input.questionId(),input.assetId(),saved.objectKey(),saved.proof())));
        audio.ready(assessment,input.assetId(),saved.objectKey(),saved.proof());
        attach(draft,input.questionId(),input.bodySha256(),saved.objectKey(),actor);
        if(!jobs.succeed(lease,PreparedSpeech.encode(saved)))throw conflict("播报任务租约已失效");
    }
    private void attach(PreparedPaper.Draft draft,String question,String body,String key,int actor){
        var current=draft.questions().stream().filter(q->q.questionId().equals(question) && PreparedJobInput.digest(q.body()).equals(body)).findFirst();
        if(current.isEmpty() || key.equals(current.get().audioObjectKey()))return;
        var next=draft.questions().stream().map(q->!q.questionId().equals(question)?q:new PreparedPaper.Question(q.questionId(),q.source(),q.body(),q.ordinal(),q.maxScore(),q.answerSeconds(),q.rubric(),q.approved(),"READY",key)).toList();
        if(!papers.replaceDraft(new PreparedPaper.Draft(draft.assessmentId(),draft.revision()+1,next),draft.revision(),actor))throw conflict("草稿版本冲突");
    }
    /** Completed cache references may belong to another authorized manager of this same assessment.
     * They are read-only, not a relaxation of the job ledger's same-creator dependency edges. */
    public List<PreparedPrivateOssStore.Stored> assemblyParts(PreparedJobLedger.Lease lease){
        access.requireAssessmentManager(lease.job().actorId(),lease.job().assessmentId());var input=whole(lease.job().inputJson());
        int assessment=lease.job().assessmentId();var parent=asset(assessment,input.assetId());var texts=PreparedSpeechText.split(input.body());
        if(lease.job().kind()!=Kind.ASSEMBLE || parent.assessment()!=assessment || !parent.question().equals(input.questionId())
                || !parent.jobId().equals(lease.job().id()) || !parent.storedState().equals("BUILDING")
                || !parent.bodySha().equals(input.bodySha256()) || !parent.configSha().equals(input.configSha256()) || parent.view().partCount()!=texts.size()
                || !audio.partJobs(assessment,input.assetId()).equals(input.orderedPartJobs()) || texts.size()!=input.orderedPartJobs().size())throw new IllegalArgumentException("整题播报绑定无效");
        var storageConfig=storage.snapshot();List<PreparedPrivateOssStore.Stored> result=new ArrayList<>();
        for(int i=0;i<texts.size();i++){
            var job=jobs.get(input.orderedPartJobs().get(i)).orElseThrow();var part=PreparedSpeech.part(job.inputJson());var output=PreparedSpeech.output(job.outputJson());
            var scope=new PreparedPrivateOssStore.Broadcast(assessment,input.questionId(),part.assetId());
            if(job.assessmentId()!=assessment || job.kind()!=Kind.TTS || job.state()!=PreparedJobLedger.State.SUCCEEDED || !part.questionId().equals(input.questionId())
                    || !part.bodySha256().equals(input.bodySha256()) || !configuration(part.policy()).equals(input.configSha256()) || part.partIndex()!=i || part.partCount()!=texts.size()
                    || !part.text().equals(texts.get(i)) || !output.questionId().equals(input.questionId()) || !output.assetId().equals(part.assetId())
                    || !scope.key(storageConfig.deploymentId()).equals(output.objectKey()))throw new IllegalArgumentException("播报分段绑定无效");
            result.add(new PreparedPrivateOssStore.Stored(scope,output.objectKey(),output.proof()));
        }
        return List.copyOf(result);
    }
    public PreparedPrivateOssStore.Stored previewAsset(int actor,int assessment,String id){
        access.requireAssessmentManager(actor,assessment);var asset=asset(assessment,id);var job=jobs.get(asset.jobId()).orElseThrow();
        if(!asset.view().state().equals("READY") || asset.proof()==null || job.kind()!=Kind.ASSEMBLE)throw conflict("整题播报尚未可靠保存，不能试听");
        var input=whole(job.inputJson());var scope=new PreparedPrivateOssStore.Broadcast(assessment,asset.question(),asset.id());
        if(!input.assetId().equals(id) || !input.questionId().equals(asset.question()) || !input.bodySha256().equals(asset.bodySha())
                || !input.configSha256().equals(asset.configSha()) || !scope.key(storage.snapshot().deploymentId()).equals(asset.key()))throw conflict("整题播报记录不完整");
        return new PreparedPrivateOssStore.Stored(scope,asset.key(),asset.proof());
    }
    private static void validate(Command c){if(c==null || c.expectedRevision()==null || c.expectedRevision()<0)throw new IllegalArgumentException("播报命令无效");
        PreparedJobInput.uuid(c.idempotencyKey());PreparedJobInput.uuid(c.questionId());sha(c.expectedConfigurationSha256());}
    private static BusinessException conflict(String message){return new BusinessException(409,message);}
}
