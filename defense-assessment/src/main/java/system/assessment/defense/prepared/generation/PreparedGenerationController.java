package system.assessment.defense.prepared.generation;

import cn.dev33.satoken.annotation.*;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.application.service.SecurityRateLimitService;
import static system.assessment.defense.prepared.generation.PreparedGeneration.*;

@RestController
@Validated
@SaCheckRole(value={"TEACHER","ADMIN"},mode=SaMode.OR)
@RequestMapping("/backend/prepared-papers/{assessmentId}/generations")
public class PreparedGenerationController {
    private final PreparedGenerationService service;
    private final ResourceAccessControlService access;
    private final SecurityRateLimitService limits;
    public PreparedGenerationController(PreparedGenerationService service,ResourceAccessControlService access,SecurityRateLimitService limits) {
        this.service=service;this.access=access;this.limits=limits;
    }
    @GetMapping public Listing list(@PathVariable @Min(1) int assessmentId) {return service.list(StpUtil.getLoginIdAsInt(),assessmentId);}
    @GetMapping("/{jobId}") public View get(@PathVariable @Min(1) int assessmentId,@PathVariable String jobId) {
        return service.get(StpUtil.getLoginIdAsInt(),assessmentId,jobId);
    }
    @PostMapping public Summary generate(@PathVariable @Min(1) int assessmentId,@Valid @RequestBody Command command,HttpServletRequest request) {
        int actor=StpUtil.getLoginIdAsInt();access.requireAssessmentManager(actor,assessmentId);
        limits.checkAiHttp(request.getRemoteAddr(),actor,5);return service.generate(actor,assessmentId,command);
    }
    @PostMapping("/{jobId}/adopt") public Adoption adopt(@PathVariable @Min(1) int assessmentId,@PathVariable String jobId,@Valid @RequestBody AdoptCommand command) {
        return service.adopt(StpUtil.getLoginIdAsInt(),assessmentId,jobId,command);
    }
}
