package system.assessment.defense.prepared.retention;

import cn.dev33.satoken.annotation.*;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.infrastructure.exception.BusinessException;

/** Existing bounded teacher metadata, same-origin, cookie and no-store boundaries. */
@RestController
@Validated
@SaCheckRole(value={"TEACHER","ADMIN"},mode=SaMode.OR)
@RequestMapping("/backend/prepared-reviews/{assessmentId}/students/{studentId}/attempts/{attemptId}/retention-protection")
public class PreparedRetentionProtectionController {
    private final PreparedRetentionProtectionService service;
    private final boolean enabled;
    public PreparedRetentionProtectionController(PreparedRetentionProtectionService service,
            @Value("${prepared.interview.workflow-enabled:false}")boolean enabled){this.service=service;this.enabled=enabled;}
    @GetMapping public PreparedRetentionProtection.View status(@PathVariable @Min(1) int assessmentId,@PathVariable @Min(1) int studentId,@PathVariable String attemptId){
        return service.status(StpUtil.getLoginIdAsInt(),studentId,assessmentId,attemptId);
    }
    @PostMapping(consumes="application/json") public PreparedRetentionProtection.Receipt execute(@PathVariable @Min(1) int assessmentId,@PathVariable @Min(1) int studentId,@PathVariable String attemptId,@RequestBody byte[] input){
        if(!enabled)throw new BusinessException(503,"正式作答流程尚未开放，请保留原录音并联系教师");
        return service.execute(StpUtil.getLoginIdAsInt(),studentId,assessmentId,attemptId,PreparedRetentionProtection.decode(input));
    }
}
