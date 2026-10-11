package system.assessment.defense.prepared.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import system.assessment.defense.application.dto.StudentDefenseFileUploadCmd;
import system.assessment.defense.application.dto.backed.RecordRetakeCmd;
import system.assessment.defense.application.dto.backed.StudentRecordChangeScoreCmd;

/** Keep compatible history writes, but never let them overwrite a prepared attempt. */
@Aspect
@Component
@Order(-110)
public class PreparedLegacyMutationFence {
    private final PreparedLegacyRecordGuard guard;
    public PreparedLegacyMutationFence(PreparedLegacyRecordGuard guard) { this.guard = guard; }

    @Around("execution(public * system.assessment.defense.application.service.StudyRecordService.changeScore(..))"
            + " || execution(public * system.assessment.defense.application.service.StudyRecordService.retake(..))")
    public Object recordWrite(ProceedingJoinPoint invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        int actor = id(args[0]);
        int record;
        if (args[1] instanceof StudentRecordChangeScoreCmd score) record = id(score.getId());
        else if (args[1] instanceof RecordRetakeCmd retake) record = id(retake.getRecordId());
        else throw new IllegalArgumentException("旧记录命令无效");
        try { return guard.record(actor, record, () -> proceed(invocation)); }
        catch (DelegateFailure failure) { throw failure.original; }
    }

    @Around("execution(public * system.assessment.defense.application.service.StudentInterviewSessionService.uploadDefenseFile(..))")
    public Object materialWrite(ProceedingJoinPoint invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        if (!(args[0] instanceof StudentDefenseFileUploadCmd command)) throw new IllegalArgumentException("旧材料命令无效");
        int student = id(args[1]);
        int assessment = id(command.getAssessmentId());
        try { return guard.material(student, assessment, () -> proceed(invocation)); }
        catch (DelegateFailure failure) { throw failure.original; }
    }

    private static int id(Object value) {
        if (!(value instanceof Integer id) || id < 1) throw new IllegalArgumentException("身份或资源标识无效");
        return id;
    }
    private static Object proceed(ProceedingJoinPoint invocation) {
        try { return invocation.proceed(); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new DelegateFailure(failure); }
    }
    private static final class DelegateFailure extends RuntimeException {
        private final Throwable original;
        private DelegateFailure(Throwable original) { super("原操作失败"); this.original = original; }
    }
}
