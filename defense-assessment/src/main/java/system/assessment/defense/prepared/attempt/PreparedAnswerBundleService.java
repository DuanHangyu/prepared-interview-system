package system.assessment.defense.prepared.attempt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static system.assessment.defense.prepared.attempt.PreparedAnswerBundle.*;

/** Durable group intent -> independent streamed bytes -> atomic original-chunk receipts. Never answer SAVED. */
@Service
public class PreparedAnswerBundleService {
    private final PreparedAnswerUploadService authorization;
    private final PreparedAnswerBundleRepository bundles;
    private final PreparedAnswerUploadRepository chunks;
    private final PreparedPrivateOssStore store;
    private final TransactionTemplate tx;
    private final Clock clock;
    @Autowired public PreparedAnswerBundleService(PreparedAnswerUploadService authorization,PreparedAnswerBundleRepository bundles,PreparedAnswerUploadRepository chunks,PreparedPrivateOssStore store,PlatformTransactionManager manager) {
        this(authorization,bundles,chunks,store,manager,Clock.systemUTC());
    }
    public PreparedAnswerBundleService(PreparedAnswerUploadService authorization,PreparedAnswerBundleRepository bundles,PreparedAnswerUploadRepository chunks,PreparedPrivateOssStore store,PlatformTransactionManager manager,Clock clock) {
        this.authorization=authorization;this.bundles=bundles;this.chunks=chunks;this.store=store;this.tx=new TransactionTemplate(manager);this.clock=clock;
    }
    public Summary prepare(int student,int assessment,String attempt,String answer,Command command) {
        noOuterTransaction();if(command==null)throw new IllegalArgumentException("原批次命令缺失");command.scope(student,assessment,attempt,answer);
        var result=tx.execute(status->{
            if(!authorization.allowOriginalUpload(student,assessment,attempt,answer,command.originalChunks()))return null;
            boolean existing=bundles.find(student,assessment,attempt,answer,command.scope(student,assessment,attempt,answer).id()).isPresent();
            var row=bundles.reserve(student,assessment,attempt,answer,command,authorization.storageConfiguration().deploymentId(),now());
            // Original header+links committed together. Missing links later are corruption, not permission to rebuild proof.
            if(!existing)reserveChunks(row);return summary(row);
        });return requireWindow(result);
    }
    public Summary status(int student,int assessment,String attempt,String answer,String id) {
        PreparedAnswerBundle.id(id);
        return Objects.requireNonNull(tx.execute(status->{
            // Parent ownership is checked first. A missing/foreign batch within this authorized answer is
            // indistinguishable 404, allowing the caller to recover its exact original prepare, not new keys.
            authorization.authorizeRead(student,assessment,attempt,answer);
            var row=bundles.find(student,assessment,attempt,answer,id).orElseThrow(()->new BusinessException(404,"本回答尚无此原批次登记，请恢复原上传请求"));
            return summary(row);
        }));
    }
    public Summary upload(int student,int assessment,String attempt,String answer,String id,InputStream input) {
        noOuterTransaction();PreparedAnswerBundle.id(id);if(input==null)throw new IllegalArgumentException("原批次内容缺失");
        var reserved=tx.execute(status->{
            authorization.authorizeRead(student,assessment,attempt,answer);var row=find(student,assessment,attempt,answer,id);
            if(!authorization.allowOriginalUpload(student,assessment,attempt,answer,row.command().originalChunks()))return null;
            deployment(row);summary(row);return row;
        });requireWindow(reserved);
        Path source=null;
        try {
            source=Files.createTempFile("prepared-answer-bundle-",".bin",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            spool(input,source,reserved.scope().byteSize());
            try(var original=Files.newInputStream(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                try{PreparedPrivateOssStore.verifyAnswerBundleBytes(original,reserved.scope());}catch(IOException wrongBytes){throw new BusinessException(400,"原批次实际字节或分片摘要不一致，请保留原内容");}
            }
            if(reserved.confirmed()==null) {
                var stored=store.putAnswerBundle(reserved.scope(),source);
                if(stored==null||!reserved.scope().equals(stored.scope())||!reserved.key().equals(stored.objectKey()))throw new IOException("原批次实际绑定无法核验");
            }
            var confirmed=tx.execute(status->{
                if(!authorization.allowOriginalUpload(student,assessment,attempt,answer,reserved.command().originalChunks()))return null;
                var row=find(student,assessment,attempt,answer,id);deployment(row);summary(row);
                Instant confirmedAt=row.confirmed()==null?now():row.confirmed();
                chunks.confirmBundle(row.scope(),confirmedAt);
                bundles.confirm(row,confirmedAt);return summary(find(student,assessment,attempt,answer,id));
            });return requireWindow(confirmed);
        }catch(IOException unavailable){throw new BusinessException(503,"原录音批次尚未确认保存，请保留本机副本并重试原批次");}
        finally{if(source!=null)try{Files.deleteIfExists(source);}catch(IOException ignored){/* Only this request's private scratch file, never original evidence. */}}
    }
    private PreparedAnswerBundleRepository.Row find(int student,int assessment,String attempt,String answer,String id) {
        return bundles.find(student,assessment,attempt,answer,id).orElseThrow(()->new BusinessException(403,"无权限或原录音批次不存在"));
    }
    private void deployment(PreparedAnswerBundleRepository.Row row) {
        if(!row.deployment().equals(authorization.storageConfiguration().deploymentId()))throw new BusinessException(409,"独立部署已变化，请保留原批次并联系教师");
    }
    private void reserveChunks(PreparedAnswerBundleRepository.Row row) {
        chunks.reserveBundle(row.scope(),row.command().segmentOrdinal(),row.deployment(),row.reserved());
    }
    private Summary summary(PreparedAnswerBundleRepository.Row row) {
        var first=row.scope().chunks().getFirst();var originals=chunks.bundleRows(first.studentId(),first.assessmentId(),first.attemptId(),first.answerId(),row.scope().id());
        if(originals.size()!=row.scope().chunks().size())throw new IllegalStateException("原批次分片绑定缺失");
        for(int i=0;i<originals.size();i++) {
            var chunk=originals.get(i);
            if(!chunk.scope().equals(row.scope().chunks().get(i))||chunk.ordinal()!=row.command().segmentOrdinal()||!chunk.deployment().equals(row.deployment())
                    ||!Objects.equals(chunk.confirmed(),row.confirmed()))throw new IllegalStateException("原批次分片收件无法核验");
        }
        return new Summary(row.scope().id(),first.answerId(),row.commandSha(),row.scope().byteSize(),row.scope().sha256(),row.confirmed()!=null,
            originals.stream().filter(c->c.confirmed()!=null).map(PreparedAnswerUploadRepository.Row::receipt).toList());
    }
    private static void spool(InputStream input,Path file,int expected)throws IOException {
        byte[] buffer=new byte[8192];int count=0,read;
        try(var output=Files.newOutputStream(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            while((read=input.read(buffer))!=-1){count+=read;if(count>expected)throw wrongSize();output.write(buffer,0,read);}
        }
        if(count!=expected)throw wrongSize();
    }
    private static BusinessException wrongSize(){return new BusinessException(400,"原批次实际大小不一致，请保留原内容");}
    private static <T>T requireWindow(T result){if(result==null)throw new PreparedUploadWindow.Expired();return result;}
    private static void noOuterTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("批次上传不能运行在外层事务中");}
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
}
