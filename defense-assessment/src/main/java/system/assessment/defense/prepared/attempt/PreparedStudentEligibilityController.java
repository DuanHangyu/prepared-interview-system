package system.assessment.defense.prepared.attempt;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/backend/prepared-interviews")
@SaCheckRole("STUDENT")
public class PreparedStudentEligibilityController {
    private final PreparedStudentEligibilityService service;
    private final String deployment;
    private final boolean workflowEnabled;
    public PreparedStudentEligibilityController(PreparedStudentEligibilityService service,@Value("${prepared.deployment-id:}") String deployment,
            @Value("${prepared.interview.workflow-enabled:false}") boolean workflowEnabled) {
        this.service=service;this.deployment=deployment;this.workflowEnabled=workflowEnabled;
    }
    @GetMapping("/{assessmentId}/eligibility")
    public PreparedStudentEligibility.Status eligibility(@PathVariable @Min(1) int assessmentId) {
        return service.status(StpUtil.getLoginIdAsInt(),assessmentId);
    }
    @GetMapping("/{assessmentId}/preflight-context")
    public PreparedStudentEligibility.PreflightContext preflightContext(@PathVariable @Min(1) int assessmentId) {
        int student=StpUtil.getLoginIdAsInt();
        var eligibility=service.status(student,assessmentId);
        return new PreparedStudentEligibility.PreflightContext(student,deployment,eligibility,workflowEnabled);
    }
}
