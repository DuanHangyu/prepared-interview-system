package system.assessment.defense.prepared.attempt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.publication.PreparedPublication;
import system.assessment.defense.prepared.storage.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static system.assessment.defense.prepared.attempt.PreparedAnswerUpload.*;
import static system.assessment.defense.prepared.attempt.PreparedAnswerUploadRepository.*;

/** Short reserve and confirm transactions bracket real streaming IO. A chunk receipt is NOT answer SAVED. */
@Service
public class PreparedAnswerUploadService {
    private final ResourceAccessControlService access;
    private final PreparedStudentEligibilityRepository eligibility;
    private final PreparedAttemptStartRepository attempts;
    private final PreparedQuestionGrantRepository questions;
    private final PreparedAnswerEndRepository endings;
    private final PreparedAnswerUploadRepository repository;
    private final PreparedStoragePolicy policy;
    private final PreparedPrivateOssStore store;
    private final TransactionTemplate tx;
    private final Clock clock;
    @Autowired public PreparedAnswerUploadService(ResourceAccessControlService access,PreparedStudentEligibilityRepository eligibility,
            PreparedAttemptStartRepository attempts,PreparedQuestionGrantRepository questions,PreparedAnswerEndRepository endings,
            PreparedAnswerUploadRepository repository,PreparedStoragePolicy policy,PreparedPrivateOssStore store,PlatformTransactionManager manager) {
        this(access,eligibility,attempts,questions,endings,repository,policy,store,manager,Clock.systemUTC());
    }
    public PreparedAnswerUploadService(ResourceAccessControlService access,PreparedStudentEligibilityRepository eligibility,
            PreparedAttemptStartRepository attempts,PreparedQuestionGrantRepository questions,PreparedAnswerEndRepository endings,
            PreparedAnswerUploadRepository repository,PreparedStoragePolicy policy,PreparedPrivateOssStore store,PlatformTransactionManager manager,Clock clock) {
        this.access=access;this.eligibility=eligibility;this.attempts=attempts;this.questions=questions;this.endings=endings;
        this.repository=repository;this.policy=policy;this.store=store;this.clock=clock;this.tx=new TransactionTemplate(manager);
    }
    public Receipt upload(int student,int assessment,String attempt,String answer,Command command,InputStream body) {
        // Refuse accidental wrapping that would retain an outer parent lock throughout cloud IO.
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("录音上传不能运行在外层事务中");
        if(command==null||body==null)throw new IllegalArgumentException("原分片命令或内容缺失");
        var scope=command.scope(student,assessment,attempt,answer);
        Row reserved=tx.execute(status->{
            var bound=authorize(student,assessment,attempt,answer);requireClient(bound,command);
            if(!uploadWindow(student,assessment,attempt,bound))return null;
            matchOriginal(bound,command);
            String deployment=storageConfiguration().deploymentId();
            return repository.reserve(scope,command.segmentOrdinal(),deployment,now());
        });
        if(reserved==null)throw late();
        Path source=null;
        try {
            source=Files.createTempFile("prepared-answer-chunk-",".bin",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            spool(body,source,scope);
            if(reserved.confirmed()==null) {
                var stored=store.putAnswerChunk(scope,source);
                if(stored==null||!scope.equals(stored.scope())||!reserved.objectKey().equals(stored.objectKey()))throw new IOException("绑定无法核验");
            }
            Row confirmed=tx.execute(status->{
                var bound=authorize(student,assessment,attempt,answer);requireClient(bound,command);
                if(!uploadWindow(student,assessment,attempt,bound))return null;
                matchOriginal(bound,command);
                if(!reserved.deployment().equals(storageConfiguration().deploymentId()))throw new BusinessException(409,"独立部署已变化，请保留原录音并联系教师");
                var original=repository.reserve(scope,command.segmentOrdinal(),reserved.deployment(),now());
                return repository.confirm(original,now());
            });
            if(confirmed==null)throw late();return confirmed.receipt();
        }catch(IOException failure){throw new BusinessException(503,"原录音分片尚未确认保存，请保留本机副本并重试原分片");}
        finally {if(source!=null)try{Files.deleteIfExists(source);}catch(IOException ignored){/* Only this request's private scratch file. Never delete original/local/remote audio. */}}
    }
    /** Scoped receipt set contains only confirmed bytes; reserved/object paths and other users never escape. */
    public List<Receipt> receipts(int student,int assessment,String attempt,String answer) {
        validateScope(student,assessment,attempt,answer);
        return Objects.requireNonNull(tx.execute(status->{authorize(student,assessment,attempt,answer);
            return repository.rows(student,assessment,attempt,answer).stream().filter(row->row.confirmed()!=null).map(Row::receipt).toList();}));
    }
    /** Shared current authorization/manifest/window rules; bundle caller owns a short transaction. */
    boolean allowOriginalUpload(int student,int assessment,String attempt,String answer,List<Command> commands) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("录音范围核验需要短事务");
        var bound=authorize(student,assessment,attempt,answer);
        for(var command:commands){requireClient(bound,command);matchOriginal(bound,command);}
        return uploadWindow(student,assessment,attempt,bound);
    }
    void authorizeRead(int student,int assessment,String attempt,String answer){authorize(student,assessment,attempt,answer);}
    record Bound(PreparedAttemptStart.Summary attempt,PreparedQuestionGrantRepository.Answer answer,Optional<PreparedAnswerEndRepository.Head> end) {}
    Bound authorize(int student,int assessment,String attemptId,String answerId) {
        validateScope(student,assessment,attemptId,answerId);
        access.requireAssignedStudent(student,assessment);attempts.lockAssessment(assessment);eligibility.contextForUpdate(student,assessment);
        var attempt=attempts.currentForUpdate(student,assessment).filter(a->attemptId.equals(a.id())).orElseThrow(()->new BusinessException(403,"无权限或尝试不存在"));
        return boundForAttempt(student,assessment,attemptId,answerId,attempt);
    }
    /** Package-private read-only proof reuse. Upload and Worker callers still use authorize. */
    Bound authorizeReview(int actor,int student,int assessment,String attemptId,String answerId){
        validateScope(student,assessment,attemptId,answerId);
        var attempt=PreparedManagerReadScope.attempt(access,attempts,actor,student,assessment,attemptId);
        return boundForAttempt(student,assessment,attemptId,answerId,attempt);
    }
    /** Internal structural reader only, under the original retention parent's lock.
     * Never called by upload, worker, student or manager HTTP authorization paths. */
    Bound retentionBound(int student,int assessment,String attemptId,String answerId){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("原留存证明需要短事务");
        validateScope(student,assessment,attemptId,answerId);
        return boundForAttempt(student,assessment,attemptId,answerId,attempts.get(student,assessment,attemptId));
    }
    private Bound boundForAttempt(int student,int assessment,String attemptId,String answerId,PreparedAttemptStart.Summary attempt){
        var answer=questions.answers(student,assessment,attemptId,true).stream().filter(a->answerId.equals(a.id())).findFirst().orElseThrow(()->new BusinessException(403,"无权限或回答不存在"));
        var paper=attempts.boundPaperForUpdate(assessment,attempt.paperVersionId());PreparedStudentPaperVerifier.verify(assessment,paper.content(),paper.sha256());
        var snapshot=PreparedPublication.decode(paper.content());
        if(answer.ordinal()<1||answer.ordinal()>snapshot.questions().size())throw new IllegalStateException("原回答题序无效");
        var question=snapshot.questions().get(answer.ordinal()-1);questions.verifyQuestion(assessment,attempt.paperVersionId(),question,true);
        if(!answer.version().equals(attempt.paperVersionId())||!answer.question().equals(question.questionId())||answer.seconds()!=question.answerSeconds()
                ||answer.started().isBefore(Instant.parse(attempt.startedAt()))||!answer.deadline().equals(answer.started().plusSeconds(answer.seconds())))
            throw new IllegalStateException("原回答授权绑定无法核验");
        var end=endings.head(student,assessment,attemptId,answerId);
        end.ifPresent(head->PreparedAnswerEndService.verifyHead(head,answer));return new Bound(attempt,answer,end);
    }
    /** Current original client only. Queries do not expire attempts or start/extend any deadline. */
    public PreparedUploadWindow.View window(int student,int assessment,String attemptId,String answerId,String captureClientId){
        PreparedJobInput.uuid(captureClientId);
        return Objects.requireNonNull(tx.execute(status->{
            var bound=authorize(student,assessment,attemptId,answerId);
            if(!bound.answer().client().equals(captureClientId))throw new BusinessException(409,"请从原授权客户端恢复原内容，不能自动抢占");
            Instant observed=now();var decision=windowDecision(student,assessment,attemptId,bound,observed);var grant=decision.approval();var attempt=bound.attempt();
            return new PreparedUploadWindow.View(student,assessment,attemptId,answerId,attempt.paperVersionId(),
                bound.end().map(PreparedAnswerEndRepository.Head::hash).orElse(null),bound.answer().deadline().toString(),
                attempt.answeringEndedAt(),attempt.supplementalUploadDeadline(),observed.toString(),decision.phase(),decision.allowed(),
                grant==null?0:grant.approvalRevision(),grant==null?null:grant.approvedAt(),grant==null?attempt.supplementalUploadDeadline():grant.uploadDeadline());
        }));
    }
    private record WindowDecision(String phase,boolean allowed,PreparedSupplement.Receipt approval) {}
    private WindowDecision windowDecision(int student,int assessment,String attemptId,Bound bound,Instant observed){
        var attempt=bound.attempt();
        boolean active=Set.of("IN_PROGRESS","UPLOAD_PENDING","REQUIRES_REVIEW").contains(attempt.state());
        if(attempt.answeringEndedAt()==null) {
            if(attempt.supplementalUploadDeadline()!=null)throw new IllegalStateException("原补传起算信息无效");
            return new WindowDecision(active?"ANSWERING":"NOT_ELIGIBLE",active,null);
        }
        Instant ended=Instant.parse(attempt.answeringEndedAt()),deadline=Instant.parse(attempt.supplementalUploadDeadline());
        if(!deadline.equals(ended.plusSeconds(86400)))throw new IllegalStateException("原补传截止无法核验");
        var grants=endings.supplements();var approval=grants.latest(student,assessment,attemptId,bound.answer().id()).map(row->grants.verify(row,bound)).orElse(null);
        if(!active)return new WindowDecision("NOT_ELIGIBLE",false,approval);
        if(observed.isBefore(deadline))return new WindowDecision("AUTOMATIC_WINDOW",true,approval);
        if(approval!=null){
            if(!endings.supplementReviewOnly(student,assessment,attemptId)||observed.isBefore(Instant.parse(approval.approvedAt())))return new WindowDecision("NOT_ELIGIBLE",false,approval);
            if(observed.isBefore(Instant.parse(approval.uploadDeadline())))return new WindowDecision("APPROVED",true,approval);
        }
        return new WindowDecision(approval==null?"AWAITING_APPROVAL":"EXPIRED",false,approval);
    }
    boolean uploadWindow(int student,int assessment,String attemptId,Bound bound) {
        Instant observed=now();var decision=windowDecision(student,assessment,attemptId,bound,observed);
        if("NOT_ELIGIBLE".equals(decision.phase()))throw new BusinessException(409,"原尝试当前不接受新的录音上传");
        if(!decision.allowed())endings.expirePending(student,assessment,attemptId,observed);
        return decision.allowed(); // Commit expiry transition before returning the typed business error.
    }
    private void requireClient(Bound bound,Command command) {
        if(!bound.answer().client().equals(command.captureClientId()))throw new BusinessException(409,"请从原授权客户端恢复原分片，不能自动抢占");
        if(!Set.of("RECORDING","UPLOAD_PENDING","REQUIRES_REVIEW","SAVED").contains(bound.answer().state()))throw new BusinessException(409,"原回答状态不允许上传");
        if(bound.end().isEmpty()&&!"RECORDING".equals(bound.answer().state()))throw new IllegalStateException("原回答缺少可靠结束声明");
    }
    private static void matchOriginal(Bound bound,Command command) {
        if(bound.end().isEmpty())return;
        var manifest=PreparedAnswerEnd.decode(bound.end().orElseThrow().manifest());
        matchManifest(manifest,command);
    }
    static void verifyReservedManifest(PreparedAnswerEnd.Manifest manifest,List<Row> rows) {
        for(var row:rows){var scope=row.scope();matchManifest(manifest,new Command(null,scope.segmentId(),row.ordinal(),scope.mimeType(),scope.index(),scope.byteSize(),scope.sha256()));}
    }
    private static void matchManifest(PreparedAnswerEnd.Manifest manifest,Command command) {
        var segment=manifest.segments().stream().filter(s->s.id().equals(command.segmentId())).findFirst().orElseThrow(()->new BusinessException(409,"分片不属于原结束清单"));
        if(segment.ordinal()!=command.segmentOrdinal()||!segment.mimeType().equals(command.mimeType())||command.index()>=segment.chunks().size())throw new BusinessException(409,"分片不属于原结束清单");
        var chunk=segment.chunks().get(command.index());
        if(chunk.index()!=command.index()||chunk.byteSize()!=command.byteSize()||!chunk.sha256().equals(command.sha256()))throw new BusinessException(409,"原结束清单内容不一致，不能覆盖");
    }
    PreparedStoragePolicy.Snapshot storageConfiguration() {
        try{return policy.snapshot();}catch(IllegalArgumentException unavailable){throw new BusinessException(503,"独立私有录音存储尚未准备好，请保留本机副本");}
    }
    private static void spool(InputStream input,Path target,PreparedPrivateOssStore.AnswerChunk scope)throws IOException {
        MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("摘要算法不可用");}
        long count=0;int read;byte[] buffer=new byte[8192];
        try(var output=Files.newOutputStream(target,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            while((read=input.read(buffer))!=-1){count+=read;if(count>scope.byteSize())throw invalidBytes();output.write(buffer,0,read);digest.update(buffer,0,read);}
        }
        if(count!=scope.byteSize()||!HexFormat.of().formatHex(digest.digest()).equals(scope.sha256()))throw invalidBytes();
    }
    private static BusinessException invalidBytes(){return new BusinessException(400,"原分片实际字节或摘要不一致，请保留原内容");}
    private static BusinessException late(){return new PreparedUploadWindow.Expired();}
    private static void validateScope(int student,int assessment,String attempt,String answer){if(student<1||assessment<1)throw new IllegalArgumentException("录音归属无效");PreparedJobInput.uuid(attempt);PreparedJobInput.uuid(answer);}
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
}
