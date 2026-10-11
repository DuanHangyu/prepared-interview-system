package system.assessment.defense.prepared.retention;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import static system.assessment.defense.prepared.retention.PreparedRetentionProtection.*;

/** Management of retained ORIGINAL data, including historical attempts. It grants no
 * student write permission, removes no automatic protections and changes no exam times.
 * There is intentionally no deletion worker/API, and no external IO under these locks. */
@Service
public class PreparedRetentionProtectionService {
    private final ResourceAccessControlService access;
    private final PreparedAttemptStartRepository attempts;
    private final PreparedRetentionProtectionStore store;
    private final TransactionTemplate tx;
    private final Clock clock;
    @Autowired public PreparedRetentionProtectionService(ResourceAccessControlService access,PreparedAttemptStartRepository attempts,
            JdbcTemplate jdbc,PlatformTransactionManager manager){this(access,attempts,jdbc,manager,Clock.systemUTC());}
    public PreparedRetentionProtectionService(ResourceAccessControlService access,PreparedAttemptStartRepository attempts,
            JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock){
        this.access=access;this.attempts=attempts;this.store=new PreparedRetentionProtectionStore(jdbc);this.tx=new TransactionTemplate(manager);this.clock=clock;
    }
    public View status(int actor,int student,int assessment,String attempt){return tx.execute(s->{
        authorize(actor,student,assessment,attempt);
        var guard=store.guard(student,assessment,attempt);var latest=guard.map(g->store.latest(student,assessment,attempt,g)).orElse(null);
        return new View(actor,student,assessment,attempt,guard.map(PreparedRetentionProtectionStore.Guard::revision).orElse(0L),guard.map(PreparedRetentionProtectionStore.Guard::held).orElse(false),
            guard.map(PreparedRetentionProtectionStore.Guard::audio).orElse("RETAINED"),guard.map(PreparedRetentionProtectionStore.Guard::results).orElse("RETAINED"),
            guard.map(PreparedRetentionProtectionService::changeable).orElse(true),now().toString(),latest);
    });}
    public Receipt execute(int actor,int student,int assessment,String attempt,Command command){
        if(command==null)throw new IllegalArgumentException("原留存保护命令缺失");
        return tx.execute(s->{
            var original=authorize(actor,student,assessment,attempt);var guard=store.guard(student,assessment,attempt);
            guard.ifPresent(g->store.latest(student,assessment,attempt,g));
            var replay=store.command(actor,assessment,command.idempotencyKey());
            if(replay.isPresent()){
                var stored=replay.orElseThrow();var receipt=store.verify(stored);
                if(receipt.studentId()!=student||!receipt.attemptId().equals(attempt)
                    ||!PreparedRetentionProtectionStore.requestHash(actor,student,assessment,attempt,command).equals(stored.requestSha()))throw conflict("相同保护标识不能更换原范围或内容");
                return receipt;
            }
            long revision=guard.map(PreparedRetentionProtectionStore.Guard::revision).orElse(0L);boolean held=guard.map(PreparedRetentionProtectionStore.Guard::held).orElse(false);
            if(command.expectedRevision()!=revision)throw conflict("留存保护版本已变化，请先查询原状态");
            if(guard.isPresent()&&!changeable(guard.orElseThrow()))throw conflict("原数据已进入清理，不能承诺重新保护或恢复");
            boolean next=command.action().equals("PROTECT");if(next==held)throw conflict("保护状态已相同，请查询原回执，不要另建操作");
            var now=now();if(now.isBefore(Instant.parse(original.startedAt()))||guard.isPresent()&&now.isBefore(Instant.parse(guard.orElseThrow().updated())))throw new BusinessException(503,"服务器时间异常，原数据保持不变");
            var receipt=new Receipt(UUID.randomUUID().toString(),actor,student,assessment,attempt,command.action(),revision,revision+1,next,now.toString(),command.reason(),command.idempotencyKey(),guard.map(PreparedRetentionProtectionStore.Guard::audio).orElse("RETAINED"),"RETAINED");
            store.save(receipt,command,guard.isPresent());return receipt;
        });
    }
    private PreparedAttemptStart.Summary authorize(int actor,int student,int assessment,String attempt){
        if(actor<1||student<1||assessment<1)throw new IllegalArgumentException("留存保护身份或考核无效");PreparedJobInput.uuid(attempt);
        access.requireAssessmentManager(actor,assessment);attempts.lockAssessment(assessment);
        // Fresh locking read after the parent lock, not the earlier authorization snapshot.
        if(!attempts.managerReadAllowed(actor,student,assessment,attempt))throw new BusinessException(403,"无权限或原尝试不存在");
        return attempts.get(student,assessment,attempt);
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private static boolean changeable(PreparedRetentionProtectionStore.Guard guard){return !"RETIRING".equals(guard.audio())&&"RETAINED".equals(guard.results())&&guard.revision()<9007199254740991L;}
    private static BusinessException conflict(String message){return new BusinessException(409,message);}
}
