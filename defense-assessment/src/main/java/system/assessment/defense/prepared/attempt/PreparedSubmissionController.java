package system.assessment.defense.prepared.attempt;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.infrastructure.exception.BusinessException;

@RestController
@Validated
@SaCheckRole("STUDENT")
@RequestMapping("/backend/prepared-interviews")
public class PreparedSubmissionController {
    private final PreparedSubmissionService service;private final boolean enabled;
    public PreparedSubmissionController(PreparedSubmissionService service,@Value("${prepared.interview.workflow-enabled:false}") boolean enabled){this.service=service;this.enabled=enabled;}
    @PostMapping("/{assessmentId}/attempts/{attemptId}/submission")
    public PreparedSubmission.Summary submit(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId,@RequestBody PreparedSubmission.Command command){
        if(!enabled)throw new BusinessException(503,"正式提交尚未开放，请保留原回答");return service.submit(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,command);
    }
    @GetMapping("/{assessmentId}/attempts/{attemptId}/submission")
    public PreparedSubmission.Summary status(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId){return service.status(StpUtil.getLoginIdAsInt(),assessmentId,attemptId);}
}
