package system.assessment.defense.prepared.config;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import system.assessment.defense.infrastructure.exception.BusinessException;

/** Legacy adapters have no durable budget reservation or prepared-task ownership. Never bill through them. */
@Aspect
@Component
@Order(-100)
public class PreparedLegacyModelFence {
    @Before("execution(public * system.assessment.defense.application.manage.OpenAiManager.*(..))"
            + " && !execution(* system.assessment.defense.application.manage.OpenAiManager.get*(..))")
    public void rejectUnmeteredLegacyCalls() {
        throw new BusinessException(409, "预置版本已禁用旧实时模型链路，请使用考前准备或后台任务");
    }

    // Fence at the entry, not after legacy code has created an anchor or claimed an analysis.
    @Before("execution(public * system.assessment.defense.application.service.StudentInterviewSessionService.handleWebsocketMessage(..))"
            + " || execution(public * system.assessment.defense.application.service.StudentInterviewSessionService.appendVoice(..))"
            + " || execution(public * system.assessment.defense.application.service.StudentAssessmentAnalysisService.reanalyze(..))"
            + " || execution(public * system.assessment.defense.application.service.StudentAssessmentAnalysisService.submitClaimedAnalysis(..))")
    public void rejectLegacyInterviewWrites() {
        throw new BusinessException(409, "预置版本不支持旧实时答辩或旧评分写入，请使用新版流程");
    }
}
