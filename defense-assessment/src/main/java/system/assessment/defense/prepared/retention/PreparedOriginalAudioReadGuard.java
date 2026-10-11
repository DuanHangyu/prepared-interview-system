package system.assessment.defense.prepared.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.util.Objects;

/** Internal audio-only read fence, after canonical original proof and fresh caller
 * authorization. This is neither authorization by itself nor a cleanup executor.
 * It joins the caller's short transaction and locks the same assessment parent;
 * storage/model IO must stay outside that transaction. Result/report proof reads
 * intentionally do not call this fence: retained metadata is not an audio file.
 * Future retirement must additionally fence in-flight IO and all issued capabilities. */
public final class PreparedOriginalAudioReadGuard {
    private final JdbcTemplate jdbc;
    private final PreparedRetentionProtectionStore store;
    public PreparedOriginalAudioReadGuard(JdbcTemplate jdbc){
        this.jdbc=Objects.requireNonNull(jdbc);this.store=new PreparedRetentionProtectionStore(jdbc);
    }
    public void requireRetained(int student,int assessment,String attempt){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("原录音读取状态须在原事务中核验");
        if(student<1||assessment<1)throw new IllegalArgumentException("原录音读取范围无效");PreparedJobInput.uuid(attempt);
        // Reentrant when the canonical source already holds the parent. Never
        // turn this into a snapshot read detached from a concurrent transition.
        if(jdbc.queryForList("SELECT id FROM assessment WHERE id=? FOR UPDATE",Integer.class,assessment).size()!=1
            ||jdbc.queryForList("SELECT id FROM prepared_attempt WHERE assessment_id=? AND student_id=? AND id=? FOR UPDATE",String.class,assessment,student,attempt).size()!=1)
            throw new IllegalStateException("原录音读取范围无法核验");
        var guard=store.guard(student,assessment,attempt);
        if(guard.isEmpty())return; // store proved there is no detached original command history
        var original=guard.orElseThrow();store.latest(student,assessment,attempt,original);
        if(!"RETAINED".equals(original.results()))throw new BusinessException(409,"原结果正在执行留存清理，当前不能获取录音授权");
        switch(original.audio()){
            case "RETAINED"->{ }
            case "RETIRING"->throw new BusinessException(409,"原录音正在执行留存清理，当前不能获取播放或转写授权");
            case "RETIRED"->throw new BusinessException(410,"原录音已按留存规则退出，不能再获取播放或转写授权");
            default->throw new IllegalStateException("原录音留存状态无法核验");
        }
    }
}
