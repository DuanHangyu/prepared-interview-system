package system.assessment.defense.prepared.attempt;

import cn.dev33.satoken.annotation.*;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.infrastructure.exception.BusinessException;

/** Uses existing same-origin, cookie, bounded metadata and teacher rate-limit boundaries. */
@RestController
@Validated
@SaCheckRole(value={"TEACHER","ADMIN"},mode=SaMode.OR)
@RequestMapping("/backend/prepared-reviews/{assessmentId}/students/{studentId}/attempts/{attemptId}/answers/{answerId}/supplement")
public class PreparedSupplementController {
    private final PreparedSupplementService service;private final boolean enabled;
    public PreparedSupplementController(PreparedSupplementService service,@Value("${prepared.interview.workflow-enabled:false}")boolean enabled){this.service=service;this.enabled=enabled;}
    @GetMapping public PreparedSupplement.View status(@PathVariable @Min(1) int assessmentId,@PathVariable @Min(1) int studentId,@PathVariable String attemptId,@PathVariable String answerId){return service.status(StpUtil.getLoginIdAsInt(),studentId,assessmentId,attemptId,answerId);}
    @PostMapping(consumes="application/json") public PreparedSupplement.Receipt approve(@PathVariable @Min(1) int assessmentId,@PathVariable @Min(1) int studentId,@PathVariable String attemptId,@PathVariable String answerId,@RequestBody byte[] input){
        if(!enabled)throw new BusinessException(503,"正式作答流程尚未开放，请保留原录音并联系教师");
        return service.approve(StpUtil.getLoginIdAsInt(),studentId,assessmentId,attemptId,answerId,PreparedSupplement.decode(input));
    }
}
