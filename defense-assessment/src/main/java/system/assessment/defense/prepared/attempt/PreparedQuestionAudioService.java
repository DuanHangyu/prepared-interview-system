package system.assessment.defense.prepared.attempt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.audio.*;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.publication.PreparedPublicationRepository;
import system.assessment.defense.prepared.storage.*;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/** Published question read only. Short consistent DB reads bracket bounded private IO;
 * there is no draft exposure, question start, recorder command or model call. */
@Service
public class PreparedQuestionAudioService {
    public record Audio(int studentId,int assessmentId,String attemptId,String answerId,String captureClientId,
                        String paperVersionId,String questionId,String assetId,String url,String expiresAt,String serverTime,
                        String sha256,long byteSize,long durationMs) {
        @Override public String toString(){return "QuestionAudio[assetId="+assetId+", URL redacted]";}
    }
    private record Binding(PreparedQuestionGrantService.AudioGrant grant,PreparedPrivateOssStore.Stored stored,PreparedStoragePolicy.Snapshot configuration) {}
    private final PreparedQuestionGrantService grants;private final PreparedAudioRepository assets;private final PreparedJobLedger jobs;
    private final PreparedPublicationRepository publications;private final PreparedStoragePolicy policy;private final PreparedPrivateOssStore store;
    private final TransactionTemplate tx;private final Clock clock;private final Semaphore reads=new Semaphore(4);
    @Autowired public PreparedQuestionAudioService(PreparedQuestionGrantService grants,PreparedAudioRepository assets,PreparedJobLedger jobs,
            PreparedPublicationRepository publications,PreparedStoragePolicy policy,PreparedPrivateOssStore store,PlatformTransactionManager manager){
        this(grants,assets,jobs,publications,policy,store,manager,Clock.systemUTC());
    }
    public PreparedQuestionAudioService(PreparedQuestionGrantService grants,PreparedAudioRepository assets,PreparedJobLedger jobs,
            PreparedPublicationRepository publications,PreparedStoragePolicy policy,PreparedPrivateOssStore store,PlatformTransactionManager manager,Clock clock){
        this.grants=grants;this.assets=assets;this.jobs=jobs;this.publications=publications;this.policy=policy;this.store=store;this.clock=clock;
        tx=new TransactionTemplate(manager);tx.setReadOnly(true);
    }
    public Audio preview(int student,int assessment,String attempt,String answer,String client){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("播报读取不能运行在外层事务中");
        var original=binding(student,assessment,attempt,answer,client);
        if(!reads.tryAcquire())throw unavailable();
        try {
            PreparedPrivateOssStore.Preview preview;Instant expiry;
            try {
                preview=store.preview(original.stored());expiry=Instant.parse(preview.expiresAt());Instant observed=clock.instant();
                if(!expiry.isAfter(observed)||expiry.isAfter(observed.plusSeconds(300)))throw new IOException();
                URI url=URI.create(preview.url());var configuration=original.configuration();
                if(!"https".equals(url.getScheme())||!(configuration.bucket()+".oss-cn-beijing.aliyuncs.com").equals(url.getHost())
                        ||url.getPort()!=-1||url.getUserInfo()!=null||url.getFragment()!=null||!("/"+original.stored().objectKey()).equals(url.getRawPath())
                        ||url.getRawQuery()==null||url.getRawQuery().isBlank()||preview.url().length()>4096)throw new IOException();
            }catch(IOException|RuntimeException failure){throw unavailable();}
            // A revoked assignment, changed attempt/client, or replaced proof cannot receive an in-flight capability.
            if(!original.equals(binding(student,assessment,attempt,answer,client)))throw unavailable();
            var delivered=clock.instant();if(!expiry.isAfter(delivered))throw unavailable();
            var proof=original.stored().proof();var grant=original.grant();
            return new Audio(student,assessment,attempt,answer,client,grant.paperVersionId(),grant.questionId(),grant.audio().assetId(),
                    preview.url(),expiry.toString(),delivered.toString(),proof.sha256(),proof.byteSize(),proof.durationMs());
        }finally{reads.release();}
    }
    private Binding binding(int student,int assessment,String attempt,String answer,String client){
        return Objects.requireNonNull(tx.execute(status->{
            var grant=grants.audioGrant(student,assessment,attempt,answer,client);
            try {
                var descriptor=grant.audio();var asset=assets.get(assessment,descriptor.assetId()).orElseThrow(PreparedQuestionAudioService::unavailable);
                var job=jobs.get(asset.jobId()).orElseThrow(PreparedQuestionAudioService::unavailable);
                var input=PreparedAudio.whole(job.inputJson());var configuration=policy.snapshot();
                var scope=new PreparedPrivateOssStore.Broadcast(assessment,grant.questionId(),descriptor.assetId());
                var published=publications.verifiedAsset(assessment,grant.questionId(),asset.key()).orElseThrow(PreparedQuestionAudioService::unavailable);
                if(!descriptor.equals(published.descriptor())||published.assessmentId()!=assessment||!published.questionId().equals(grant.questionId())
                        ||asset.assessment()!=assessment||!asset.id().equals(descriptor.assetId())||!asset.question().equals(grant.questionId())
                        ||!"READY".equals(asset.storedState())||!"READY".equals(asset.view().state())||asset.proof()==null
                        ||!asset.bodySha().equals(descriptor.bodySha256())||!asset.configSha().equals(descriptor.configSha256())
                        ||job.assessmentId()!=assessment||job.kind()!=PreparedJobLedger.Kind.ASSEMBLE||job.state()!=PreparedJobLedger.State.SUCCEEDED
                        ||!input.assetId().equals(asset.id())||!input.questionId().equals(grant.questionId())||!input.body().equals(grant.body())
                        ||!input.bodySha256().equals(descriptor.bodySha256())||!input.configSha256().equals(descriptor.configSha256())
                        ||asset.view().partCount()!=input.orderedPartJobs().size()||asset.view().completedParts()!=asset.view().partCount()
                        ||!assets.partJobs(assessment,asset.id()).equals(input.orderedPartJobs())||!scope.key(configuration.deploymentId()).equals(asset.key())
                        ||!asset.proof().sha256().equals(descriptor.contentSha256())||asset.proof().byteSize()!=descriptor.byteSize()
                        ||asset.proof().durationMs()!=descriptor.durationMs())throw unavailable();
                return new Binding(grant,new PreparedPrivateOssStore.Stored(scope,asset.key(),asset.proof()),configuration);
            }catch(RuntimeException failure){throw unavailable();}
        }));
    }
    private static BusinessException unavailable(){return new BusinessException(503,"题目播报暂不可用，请稍后重试；题目文字保留，不阻止录音，不重置原截止时间");}
}
