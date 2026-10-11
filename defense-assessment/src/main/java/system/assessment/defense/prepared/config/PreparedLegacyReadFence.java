package system.assessment.defense.prepared.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Do not parse prepared originals as legacy reports or issue incompatible legacy URLs. */
@Aspect @Component @Order(-110)
public class PreparedLegacyReadFence {
    private final PreparedLegacyRecordGuard guard;
    public PreparedLegacyReadFence(PreparedLegacyRecordGuard guard){this.guard=guard;}
    @Around("execution(public * system.assessment.defense.application.service.StudentAssessmentQueryService.assessmentDetailByRecordId(..))")
    public Object teacherDetail(ProceedingJoinPoint call)throws Throwable {
        var args=call.getArgs();try{return guard.record(id(args[0]),id(args[1]),()->proceed(call));}catch(DelegateFailure e){throw e.original;}
    }
    @Around("execution(public * system.assessment.defense.application.service.StudentAssessmentQueryService.assessmentDetail(..))")
    public Object studentDetail(ProceedingJoinPoint call)throws Throwable {
        var args=call.getArgs();try{return guard.studentDetail(id(args[0]),id(args[1]),()->proceed(call));}catch(DelegateFailure e){throw e.original;}
    }
    private static int id(Object value){if(!(value instanceof Integer id)||id<1)throw new IllegalArgumentException("身份或资源标识无效");return id;}
    private static Object proceed(ProceedingJoinPoint call){try{return call.proceed();}catch(RuntimeException|Error e){throw e;}catch(Throwable e){throw new DelegateFailure(e);}}
    private static final class DelegateFailure extends RuntimeException {final Throwable original;DelegateFailure(Throwable original){super("原读取失败");this.original=original;}}
}
