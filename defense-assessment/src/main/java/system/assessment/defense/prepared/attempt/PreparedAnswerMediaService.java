package system.assessment.defense.prepared.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.media.*;
import system.assessment.defense.prepared.storage.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;

/** Whole media receipt is separate from the immutable end declaration and byte acknowledgements.
 * Short current authorization transactions bracket all private IO and real decoding. */
@Service
public class PreparedAnswerMediaService {
    public record Command(String captureClientId,String manifestSha256) {public Command{PreparedJobInput.uuid(captureClientId);sha(manifestSha256);}}
    public record Input(int student,int assessment,String attempt,String answer,String client,String manifestSha256,long declarationRevision,String deployment) {}
    public record Summary(String answerId,String manifestSha256,String jobId,String phase,boolean serverSaved,long revision,Long durationMs,String verifiedAt) {}
    public record Plan(Input input,PreparedAnswerEnd.Manifest manifest,int answerSeconds,List<PreparedAnswerUploadRepository.Location> locations,List<PreparedAnswerBundleRepository.Row> bundles) {
        public Plan{locations=List.copyOf(locations);bundles=List.copyOf(bundles);}
    }
    private record Header(String hash,long declarationRevision,String deployment,String job,String proof,Timestamp verified) {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private final PreparedAnswerUploadService authorization;private final PreparedAnswerUploadRepository chunks;private final PreparedAnswerBundleRepository bundles;
    private final PreparedJobLedger jobs;private final PreparedAnswerMediaRuntime runtime;private final PreparedStoragePolicy policy;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public PreparedAnswerMediaService(PreparedAnswerUploadService authorization,PreparedAnswerUploadRepository chunks,PreparedAnswerBundleRepository bundles,
            PreparedJobLedger jobs,PreparedAnswerMediaRuntime runtime,PreparedStoragePolicy policy,JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.authorization=authorization;this.chunks=chunks;this.bundles=bundles;this.jobs=jobs;this.runtime=runtime;this.policy=policy;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);
    }
    /** Deterministic original-answer command: it cannot select a new manifest, recording or cloud path. */
    public Summary request(int student,int assessment,String attempt,String answer,Command command) {
        if(command==null)throw new IllegalArgumentException("原回答核验命令缺失");
        var result=tx.execute(status->{
            var bound=authorization.authorize(student,assessment,attempt,answer);var head=bound.end().orElseThrow(()->conflict("请先可靠登记原结束清单"));
            if(!bound.answer().client().equals(command.captureClientId())||!head.hash().equals(command.manifestSha256()))throw conflict("原回答、客户端或清单不一致");
            var previous=header(student,assessment,attempt,answer);
            if(previous.isPresent()){
                var original=previous.orElseThrow();var current=summary(bound,original);
                if(!"WAITING_APPROVAL".equals(current.phase()))return current;
                if(!authorization.uploadWindow(student,assessment,attempt,bound))return null;
                var window=authorization.window(student,assessment,attempt,answer,command.captureClientId());
                if(!window.mayUploadOriginal()||!"APPROVED".equals(window.phase()))throw conflict("超期原核验任务需要有效教师审批，不能重置自动窗口");
                if(!runtime.available())throw new BusinessException(503,"独立录音媒体核验尚未启用，请保留本机录音");
                var job=jobs.getForShare(original.job()).orElseThrow();var input=input(job);verifyHeader(input,original,job.id());plan(input,bound);
                if(!jobs.resumeAnswerMediaAfterApproval(job))throw conflict("原核验任务绑定需要核验，不能自动恢复");
                jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,original_student_id,original_attempt_id,resource_id) VALUES (?,?,'ANSWER_MEDIA_RESUMED',?,?,?,?)",assessment,student,head.revision(),student,attempt,answer);
                return summary(bound,original);
            }
            if(!head.reasons().isEmpty()||!"UPLOAD_PENDING".equals(bound.answer().state()))throw conflict("原回答需要人工核验，不能伪报完整");
            if(!authorization.uploadWindow(student,assessment,attempt,bound))return null;
            if(!runtime.available())throw new BusinessException(503,"独立录音媒体核验尚未启用，请保留本机录音");
            String deployment=authorization.storageConfiguration().deploymentId();
            var input=new Input(student,assessment,attempt,answer,command.captureClientId(),head.hash(),head.revision(),deployment);plan(input,bound);
            String key=UUID.nameUUIDFromBytes(("prepared-answer-media-v1\n"+assessment+"\n"+student+"\n"+attempt+"\n"+answer+"\n"+head.hash()).getBytes(StandardCharsets.UTF_8)).toString();
            var job=jobs.enqueue(student,assessment,Kind.ANSWER_MEDIA,head.revision(),key,encode(input));
            jdbc.update("INSERT INTO prepared_answer_media(answer_id,assessment_id,student_id,attempt_id,manifest_sha256,declaration_revision,deployment_id,job_id) VALUES (?,?,?,?,?,?,?,?)",answer,assessment,student,attempt,head.hash(),head.revision(),deployment,job.id());
            return summary(bound,header(student,assessment,attempt,answer).orElseThrow());
        });return requireWindow(result);
    }
    public Summary status(int student,int assessment,String attempt,String answer) {
        return tx.execute(status->{var bound=authorization.authorize(student,assessment,attempt,answer);var header=header(student,assessment,attempt,answer);
            if(header.isEmpty())return new Summary(answer,bound.end().map(PreparedAnswerEndRepository.Head::hash).orElse(null),null,"NOT_QUEUED",false,revision(bound.answer()),null,null);
            return summary(bound,header.orElseThrow());});
    }
    /** Only the authorized teacher reader reuses structural proof after an anchor changes.
     * This cannot request, assemble, upload, complete, heartbeat, or revive an old task. */
    Summary reviewStatus(int actor,int student,int assessment,String attempt,String answer){
        var bound=authorization.authorizeReview(actor,student,assessment,attempt,answer);
        var original=header(student,assessment,attempt,answer).orElseThrow(()->new IllegalStateException("历史原录音媒体回执缺失"));
        return summary(bound,original);
    }
    /** Package-private retention proof reuse, not a media read capability or upload permission. */
    Summary retentionStatus(int student,int assessment,String attempt,String answer){
        var bound=authorization.retentionBound(student,assessment,attempt,answer);
        return summary(bound,header(student,assessment,attempt,answer).orElseThrow(()->new IllegalStateException("原留存媒体回执缺失")));
    }
    public Plan plan(Lease lease) {
        Input input=input(lease);return requireWindow(tx.execute(status->planWithin(lease,input)));
    }
    private Plan planWithin(Lease lease,Input input){
            var bound=authorization.authorize(input.student(),input.assessment(),input.attempt(),input.answer());verifyHeader(input,header(input.student(),input.assessment(),input.attempt(),input.answer()).orElseThrow(),lease.job().id());
            if(!authorization.uploadWindow(input.student(),input.assessment(),input.attempt(),bound))return null;
            if(!jobs.heartbeat(lease,300))throw conflict("原核验任务已失去认领，不能覆盖");return plan(input,bound);
    }
    public system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal.Handle beginMediaIo(Lease lease,Plan expected){
        if(expected==null)throw new IllegalArgumentException("原整题核验来源缺失");var input=input(lease);
        var handle=tx.execute(status->{var current=planWithin(lease,input);if(current==null)return null;
            if(!current.equals(expected))throw conflict("原整题核验来源已变化");
            var scope=new system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal.Scope(input.student(),input.student(),input.assessment(),input.attempt(),input.answer(),input.declarationRevision(),input.manifestSha256());
            return new system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal(jdbc).open(scope,system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal.Kind.ASSEMBLY);});
        return requireWindow(handle);
    }
    public void endMediaIo(system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal.Handle handle){
        tx.executeWithoutResult(s->new system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal(jdbc).close(handle));
    }
    private Plan plan(Input input,PreparedAnswerUploadService.Bound bound) {
        var head=bound.end().orElseThrow(()->conflict("原结束清单缺失"));
        if(!head.hash().equals(input.manifestSha256())||head.revision()!=input.declarationRevision()||!head.reasons().isEmpty()
                ||!bound.answer().client().equals(input.client())||!"UPLOAD_PENDING".equals(bound.answer().state())||revision(bound.answer())!=head.revision())throw conflict("原回答状态或清单需要核验");
        if(!input.deployment().equals(authorization.storageConfiguration().deploymentId()))throw conflict("原录音部署已变化");
        var manifest=PreparedAnswerEnd.decode(head.manifest());var locations=chunks.locations(input.student(),input.assessment(),input.attempt(),input.answer());
        int expected=manifest.segments().stream().mapToInt(PreparedAnswerEnd.Segment::chunkCount).sum();
        if(expected!=locations.size())throw conflict("原录音仍有缺片，请继续补传原内容");
        int index=0;var groups=new LinkedHashMap<String,PreparedAnswerBundleRepository.Row>();
        for(var segment:manifest.segments())for(var chunk:segment.chunks()) {
            var location=locations.get(index++);var row=location.row();var scope=row.scope();
            if(!scope.segmentId().equals(segment.id())||row.ordinal()!=segment.ordinal()||!scope.mimeType().equals(segment.mimeType())||scope.index()!=chunk.index()
                    ||scope.byteSize()!=chunk.byteSize()||!scope.sha256().equals(chunk.sha256())||!row.deployment().equals(input.deployment())
                    ||!row.objectKey().equals(scope.key(input.deployment()))||row.confirmed()==null||row.confirmed().isBefore(row.reserved()))throw conflict("原录音字节尚未完整确认或绑定异常");
            if(location.bundleId()==null){if(location.offset()!=null)throw new IllegalStateException("原单片位置损坏");}
            else {
                var group=groups.computeIfAbsent(location.bundleId(),id->bundles.find(input.student(),input.assessment(),input.attempt(),input.answer(),id).orElseThrow(()->new IllegalStateException("原批次证明缺失")));
                if(!group.deployment().equals(input.deployment())||!group.command().captureClientId().equals(input.client())||group.confirmed()==null
                        ||!group.confirmed().equals(row.confirmed()))throw new IllegalStateException("原批次确认绑定损坏");
                int offset=0;boolean found=false;for(var selected:group.scope().chunks()){if(selected.equals(scope)){found=Objects.equals(offset,location.offset());break;}offset+=selected.byteSize();}
                if(!found)throw new IllegalStateException("原批次位置无法核验");
            }
        }
        for(var group:groups.values()) {
            var full=chunks.bundleRows(input.student(),input.assessment(),input.attempt(),input.answer(),group.scope().id());
            if(full.size()!=group.scope().chunks().size())throw new IllegalStateException("原批次完整绑定缺失");
            for(int n=0;n<full.size();n++)if(!full.get(n).scope().equals(group.scope().chunks().get(n))||!Objects.equals(full.get(n).confirmed(),group.confirmed()))throw new IllegalStateException("原批次完整绑定损坏");
        }
        return new Plan(input,manifest,bound.answer().seconds(),locations,new ArrayList<>(groups.values()));
    }
    /** IO proof, fresh original scope/manifest/window, lease result and SAVED commit are one short transaction. */
    public void complete(Lease lease,PreparedPrivateOssStore.StoredAnswer saved,PreparedAnswerMedia.Proof proof) {
        Input input=input(lease);var result=tx.execute(status->{
            var bound=authorization.authorize(input.student(),input.assessment(),input.attempt(),input.answer());
            var header=header(input.student(),input.assessment(),input.attempt(),input.answer()).orElseThrow();verifyHeader(input,header,lease.job().id());
            if(!authorization.uploadWindow(input.student(),input.assessment(),input.attempt(),bound))return null;
            var plan=plan(input,bound);var scope=new PreparedPrivateOssStore.AnswerFile(input.assessment(),input.student(),input.attempt(),input.answer(),input.manifestSha256());
            verifyProof(proof,plan.manifest(),plan.answerSeconds());
            if(saved==null||!scope.equals(saved.scope())||!scope.key(input.deployment()).equals(saved.objectKey())||!proof.audio().equals(saved.proof()))throw new IllegalStateException("完整媒体证明无法核验");
            String json=encode(proof);
            if(!jobs.succeed(lease,json))throw conflict("原核验任务认领已失效");
            int changed=jdbc.update("UPDATE prepared_answer SET state='SAVED',revision=revision+1 WHERE id=? AND assessment_id=? AND student_id=? AND attempt_id=? AND state='UPLOAD_PENDING' AND revision=?",input.answer(),input.assessment(),input.student(),input.attempt(),input.declarationRevision());
            if(changed!=1)throw conflict("原回答状态已变化");
            if(jdbc.update("UPDATE prepared_answer_media SET proof_json=?,verified_at=CURRENT_TIMESTAMP(3) WHERE answer_id=? AND job_id=? AND proof_json IS NULL",json,input.answer(),lease.job().id())!=1)throw new IllegalStateException("整题媒体回执无法提交");
            jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,original_student_id,original_attempt_id,resource_id) VALUES (?,?,'ANSWER_MEDIA_SAVED',?,?,?,?)",input.assessment(),input.student(),input.declarationRevision()+1,input.student(),input.attempt(),input.answer());return true;
        });requireWindow(result);
    }
    private Optional<Header> header(int student,int assessment,String attempt,String answer){return jdbc.query("SELECT * FROM prepared_answer_media WHERE student_id=? AND assessment_id=? AND attempt_id=? AND answer_id=? FOR UPDATE",(rs,n)->new Header(rs.getString("manifest_sha256"),rs.getLong("declaration_revision"),rs.getString("deployment_id"),rs.getString("job_id"),rs.getString("proof_json"),rs.getTimestamp("verified_at")),student,assessment,attempt,answer).stream().findFirst();}
    private Summary summary(PreparedAnswerUploadService.Bound bound,Header header) {
        var head=bound.end().orElseThrow(()->new IllegalStateException("整题回执缺少原声明"));var job=jobs.getForShare(header.job()).orElseThrow();var input=input(job);
        verifyHeader(input,header,job.id());
        if(!input.attempt().equals(bound.attempt().id())||!input.answer().equals(bound.answer().id())||!input.client().equals(bound.answer().client())
                ||!head.hash().equals(header.hash())||head.revision()!=header.declarationRevision()||!head.reasons().isEmpty())throw new IllegalStateException("整题回执原声明不一致");
        boolean saved=header.proof()!=null;Long duration=null;
        if(saved){try{var proof=JSON.readValue(header.proof(),PreparedAnswerMedia.Proof.class);
                if(header.verified()==null||job.state()!=State.SUCCEEDED||!header.proof().equals(PreparedJobInput.json(job.outputJson(),262144))||!"SAVED".equals(bound.answer().state())||revision(bound.answer())!=header.declarationRevision()+1
                        )throw new IllegalStateException();
                verifyProof(proof,PreparedAnswerEnd.decode(head.manifest()),bound.answer().seconds());duration=proof.audio().durationMs();
            }catch(Exception failure){throw new IllegalStateException("整题媒体回执损坏");}}
        else if(header.verified()!=null||job.state()==State.SUCCEEDED||"SAVED".equals(bound.answer().state()))throw new IllegalStateException("整题媒体状态不一致");
        return new Summary(bound.answer().id(),header.hash(),header.job(),saved?"SAVED":jobs.answerMediaWaitingForApproval(job.id())?"WAITING_APPROVAL":job.state().name(),saved,revision(bound.answer()),duration,header.verified()==null?null:header.verified().toInstant().toString());
    }
    /** Matching JSON/state flags alone are not a media receipt. Validate the canonical PCM shape
     * and every original recorder binding on BOTH commit and read. Actual remote bytes are still
     * verified by the private adapter; this structural check does not replace that IO proof. */
    private static void verifyProof(PreparedAnswerMedia.Proof proof,PreparedAnswerEnd.Manifest manifest,int seconds) {
        if(proof==null||proof.audio()==null)throw new IllegalStateException("完整媒体证明缺失");
        var audio=proof.audio();
        if(audio.sha256()==null||!audio.sha256().matches("[a-f0-9]{64}")||audio.byteSize()<46||audio.byteSize()>67108864
                ||audio.sampleRate()!=16000||audio.channels()!=1||audio.dataOffset()!=44||audio.dataSize()<2
                ||audio.dataSize()!=audio.byteSize()-44||audio.dataSize()%2!=0||audio.durationMs()<1
                ||audio.durationMs()>(long)seconds*1000||audio.durationMs()!=(audio.dataSize()/2*1000+15999)/16000
                ||proof.segments().size()!=manifest.segments().size())throw new IllegalStateException("完整媒体证明无法核验");
        long total=0;
        for(int n=0;n<proof.segments().size();n++) {
            var original=manifest.segments().get(n);var decoded=proof.segments().get(n);
            if(!original.id().equals(decoded.id())||original.ordinal()!=decoded.ordinal()||decoded.sourceSha256()==null
                    ||!decoded.sourceSha256().matches("[a-f0-9]{64}")||decoded.durationMs()<1||decoded.durationMs()>(long)seconds*1000
                    ||Math.abs(decoded.durationMs()-(original.endedAt()-original.startedAt()))>1000)throw new IllegalStateException("原录音分段证明无法核验");
            total+=decoded.durationMs();
        }
        // Per-segment ceil-to-millisecond rounding can exceed the whole PCM duration by at most
        // one millisecond per extra segment. Never permit silent padding, truncation or overflow.
        if(total>(long)seconds*1000||total<audio.durationMs()||total-audio.durationMs()>=proof.segments().size())throw new IllegalStateException("整题与分段媒体时长不一致");
    }
    private long revision(PreparedQuestionGrantRepository.Answer answer){return Objects.requireNonNull(jdbc.queryForObject("SELECT revision FROM prepared_answer WHERE id=? FOR UPDATE",Long.class,answer.id()));}
    private static void verifyHeader(Input input,Header header,String job){if(!input.manifestSha256().equals(header.hash())||input.declarationRevision()!=header.declarationRevision()||!input.deployment().equals(header.deployment())||!job.equals(header.job()))throw new IllegalStateException("原核验任务绑定损坏");}
    private static Input input(Lease lease){if(lease==null)throw new IllegalArgumentException("原任务认领缺失");return input(lease.job());}
    private static Input input(Job job){try{if(job==null||job.kind()!=Kind.ANSWER_MEDIA)throw new IllegalArgumentException();var input=JSON.readValue(PreparedJobInput.json(job.inputJson(),4096),Input.class);
        PreparedJobInput.uuid(input.attempt());PreparedJobInput.uuid(input.answer());PreparedJobInput.uuid(input.client());sha(input.manifestSha256());
        if(input.student()!=job.actorId()||input.assessment()!=job.assessmentId()||input.declarationRevision()!=job.inputVersion()||input.declarationRevision()<1||!encode(input).equals(PreparedJobInput.json(job.inputJson(),4096)))throw new IllegalArgumentException();return input;
        }catch(Exception error){throw new IllegalStateException("原录音核验任务无法核验");}}
    private static String encode(Object value){try{return PreparedJobInput.json(JSON.writeValueAsString(value),262144);}catch(java.io.IOException error){throw new IllegalStateException("录音核验记录无法保存");}}
    private static void sha(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("原清单摘要无效");}
    private static BusinessException conflict(String message){return new BusinessException(409,message);}
    private static <T>T requireWindow(T result){if(result==null)throw new PreparedUploadWindow.Expired();return result;}
}
