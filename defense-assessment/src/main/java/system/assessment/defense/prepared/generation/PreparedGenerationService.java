package system.assessment.defense.prepared.generation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.paper.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static system.assessment.defense.prepared.generation.PreparedGeneration.*;

@Service
public class PreparedGenerationService {
    private final ResourceAccessControlService access;
    private final PreparedPaperRepository drafts;
    private final PreparedPaperService papers;
    private final PreparedGenerationRepository repository;
    private final PreparedJobLedger jobs;
    private final PreparedGenerationPolicy policy;
    private final PreparedGenerationCodec codec;
    public PreparedGenerationService(ResourceAccessControlService access, PreparedPaperRepository drafts, PreparedPaperService papers,
                                     PreparedGenerationRepository repository, PreparedJobLedger jobs, PreparedGenerationPolicy policy, PreparedGenerationCodec codec) {
        this.access=access;this.drafts=drafts;this.papers=papers;this.repository=repository;this.jobs=jobs;this.policy=policy;this.codec=codec;
    }
    @Transactional(readOnly=true)
    public Listing list(int actor,int assessment) {
        access.requireAssessmentManager(actor,assessment);
        return new Listing(policy.availability(),repository.list(assessment));
    }
    @Transactional(readOnly=true)
    public View get(int actor,int assessment,String id) {
        access.requireAssessmentManager(actor,assessment);
        var job=authorizedJob(assessment,id);var summary=repository.summary(id);
        var suggestions=job.state()==PreparedJobLedger.State.SUCCEEDED? suggestions(job):List.<PreparedPaper.QuestionInput>of();
        return new View(id,summary.state(),summary.inputVersion(),summary.errorCode(),summary.createdAt(),suggestions,repository.adopted(id));
    }
    @Transactional
    public Summary generate(int actor,int assessment,Command command) {
        access.requireAssessmentManager(actor,assessment);command.validate();
        var draft=drafts.loadForUpdate(assessment);
        var previous=repository.previousJob(actor,assessment,command.idempotencyKey());
        if(previous.isPresent()) {
            if(!input(authorizedJob(assessment,previous.get()).inputJson()).command().equals(command)) throw conflict();
            return repository.summary(previous.get());
        }
        if(draft.revision()!=command.expectedRevision()) throw conflict();
        if(!policy.availability().enabled()) throw new BusinessException(503,"AI 备题配置尚未核验，可继续手工备题");
        if(repository.active(assessment)) throw new BusinessException(409,"本考核已有出题任务，请等待或查看原任务");
        var job=jobs.enqueue(actor,assessment,PreparedJobLedger.Kind.QUESTIONS,command.expectedRevision(),command.idempotencyKey(),
                encode(new Input(command,policy.snapshot())));
        repository.audit(actor,assessment,"AI_GENERATION_REQUESTED",draft.revision());
        return repository.summary(job.id());
    }
    @Transactional
    public Adoption adopt(int actor,int assessment,String id,AdoptCommand command) {
        access.requireAssessmentManager(actor,assessment); command.validate();PreparedJobInput.uuid(id);
        var draft=drafts.loadForUpdate(assessment);var job=authorizedJob(assessment,id);
        String hash=PreparedJobInput.digest(encode(List.of(id,command.expectedRevision(),command.questionIds())));
        var prior=repository.receipt(assessment,actor,command.idempotencyKey());
        if(prior.isPresent()) {
            if(!prior.get().requestHash().equals(hash)) throw conflict();
            return new Adoption(draft,prior.get().revision());
        }
        if(job.state()!=PreparedJobLedger.State.SUCCEEDED) throw new BusinessException(409,"建议尚未生成成功，不能采用");
        if(draft.revision()!=command.expectedRevision()) throw conflict();
        Set<String> adopted=new HashSet<>(repository.adopted(id));
        if(command.questionIds().stream().anyMatch(adopted::contains)) throw new BusinessException(409,"所选建议已采用，不允许重复添加");
        var options=suggestions(job).stream().collect(Collectors.toMap(PreparedPaper.QuestionInput::questionId,Function.identity()));
        if(!options.keySet().containsAll(command.questionIds())) throw new IllegalArgumentException("所选题目不属于本次建议");
        var next=papers.appendAi(actor,assessment,command.expectedRevision(),command.questionIds().stream().map(options::get).toList());
        repository.record(actor,assessment,id,command,hash,next.revision());
        return new Adoption(next,next.revision());
    }
    private PreparedJobLedger.Job authorizedJob(int assessment,String id) {
        var job=jobs.get(id).orElseThrow(()->new BusinessException(403,"建议不存在或无权限"));
        if(job.assessmentId()!=assessment || job.kind()!=PreparedJobLedger.Kind.QUESTIONS) throw new BusinessException(403,"建议不存在或无权限");
        return job;
    }
    private List<PreparedPaper.QuestionInput> suggestions(PreparedJobLedger.Job job) {
        var command=input(job.inputJson()).command();return codec.decode(job.id(),job.outputJson(),command.count(),command.maxScore(),command.answerSeconds());
    }
    private static BusinessException conflict(){return new BusinessException(409,"草稿版本或幂等命令冲突，请保留编辑内容并读取最新版本");}
}
