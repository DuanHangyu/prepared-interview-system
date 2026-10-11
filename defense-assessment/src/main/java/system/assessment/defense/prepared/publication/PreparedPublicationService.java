package system.assessment.defense.prepared.publication;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.paper.PreparedPaperRepository;
import static system.assessment.defense.prepared.publication.PreparedPublication.*;

@Service
public class PreparedPublicationService {
    private final ResourceAccessControlService access;
    private final PreparedPaperRepository drafts;
    private final PreparedPublicationRepository repository;
    private final String deployment;
    public PreparedPublicationService(ResourceAccessControlService access, PreparedPaperRepository drafts,
                                      PreparedPublicationRepository repository, @Value("${prepared.deployment-id:prepared-local}") String deployment) {
        if (deployment==null || !deployment.matches("prepared-[a-z0-9][a-z0-9-]{0,70}")) throw new IllegalArgumentException("部署标识无效");
        this.access=access;this.drafts=drafts;this.repository=repository;this.deployment=deployment;
    }
    @Transactional(readOnly=true)
    public Status status(int actor, int assessment) {
        access.requireAssessmentManager(actor,assessment);
        var draft=drafts.load(assessment);var rules=repository.rules(assessment);var gate=repository.gate(assessment);
        var checked=PreparedPublicationChecks.inspect(draft,rules,gate.frozen(),repository::verifiedAsset,deployment);
        var active=gate.activeVersionId()==null?null:repository.summary(assessment,gate.activeVersionId()).orElseThrow(()->new IllegalStateException("发布指针无效"));
        String contentHash=checked.snapshot()==null?null:PreparedJobInput.digest(encode(checked.snapshot()));
        return new Status(assessment,draft.revision(),rules,checked.issues().isEmpty(),gate.frozen(),contentHash,active,checked.issues(),repository.versions(assessment));
    }
    @Transactional(readOnly=true)
    public View get(int actor, int assessment, String id) {
        access.requireAssessmentManager(actor,assessment);PreparedJobInput.uuid(id);
        return view(assessment,id);
    }
    @Transactional
    public View publish(int actor, int assessment, Command command) {
        access.requireAssessmentManager(actor,assessment);command.validate();
        var draft=drafts.loadForUpdate(assessment);
        String requestHash=PreparedJobInput.digest("prepared-publish-v1:"+command.expectedRevision()+":"+command.expectedContentSha256());
        var receipt=repository.receipt(assessment,actor,command.idempotencyKey());
        if (receipt.isPresent()) {
            if (!requestHash.equals(receipt.get().requestHash())) throw new BusinessException(409,"发布幂等键对应的命令不同");
            return view(assessment,receipt.get().versionId());
        }
        if (draft.revision()!=command.expectedRevision()) throw new BusinessException(409,"草稿版本已变化，请保留编辑并重新检查");
        var gate=repository.gate(assessment);
        var checked=PreparedPublicationChecks.inspect(draft,repository.rules(assessment),gate.frozen(),repository::verifiedAsset,deployment);
        if (!checked.issues().isEmpty()) throw new BusinessException(409,"试卷尚不能发布，请查看发布检查中的待处理事项");
        String content=encode(checked.snapshot()), hash=PreparedJobInput.digest(content);
        if (!hash.equals(command.expectedContentSha256()))
            throw new BusinessException(409,"考核设置或播报资产已变化，请重新检查后确认发布");
        String id=gate.activeVersionId();
        var active=id==null?null:repository.summary(assessment,id).orElseThrow(()->new IllegalStateException("发布指针无效"));
        if (active==null || !hash.equals(active.contentSha256()))
            id=repository.publish(actor,draft.revision(),checked.snapshot(),content,hash);
        repository.recordReceipt(actor,assessment,command,requestHash,id);
        return view(assessment,id);
    }
    private View view(int assessment, String id) {
        var summary=repository.summary(assessment,id).orElseThrow(()->new BusinessException(403,"试卷版本不存在或无权限"));
        var snapshot=decode(repository.snapshot(assessment,id));
        if (snapshot.schemaVersion()!=1 || snapshot.assessmentId()!=assessment
                || !PreparedJobInput.digest(encode(snapshot)).equals(summary.contentSha256()))
            throw new IllegalStateException("试卷快照完整性验证失败");
        return new View(summary,snapshot);
    }
}
