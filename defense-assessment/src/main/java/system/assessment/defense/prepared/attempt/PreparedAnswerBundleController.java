package system.assessment.defense.prepared.attempt;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.io.IOException;

/** Same-origin authenticated transport, not a presigned URL or whole-answer success endpoint. */
@RestController
@Validated
@RequestMapping("/backend/prepared-interviews")
@SaCheckRole("STUDENT")
public class PreparedAnswerBundleController {
    private final PreparedAnswerBundleService service;
    private final boolean workflowEnabled;
    public PreparedAnswerBundleController(PreparedAnswerBundleService service,@Value("${prepared.interview.workflow-enabled:false}")boolean workflowEnabled){this.service=service;this.workflowEnabled=workflowEnabled;}
    @PostMapping("/{assessmentId}/attempts/{attemptId}/answers/{answerId}/upload-bundles")
    public PreparedAnswerBundle.Summary prepare(@PathVariable @Min(1)int assessmentId,@PathVariable String attemptId,@PathVariable String answerId,@RequestBody PreparedAnswerBundle.Command command) {
        requireEnabled();return service.prepare(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId,command);
    }
    @PutMapping(value="/{assessmentId}/attempts/{attemptId}/answers/{answerId}/upload-bundles/{bundleId}",consumes="application/octet-stream")
    public PreparedAnswerBundle.Summary upload(@PathVariable @Min(1)int assessmentId,@PathVariable String attemptId,@PathVariable String answerId,@PathVariable String bundleId,HttpServletRequest request)throws IOException {
        requireEnabled();return service.upload(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId,bundleId,request.getInputStream());
    }
    @GetMapping("/{assessmentId}/attempts/{attemptId}/answers/{answerId}/upload-bundles/{bundleId}")
    public PreparedAnswerBundle.Summary status(@PathVariable @Min(1)int assessmentId,@PathVariable String attemptId,@PathVariable String answerId,@PathVariable String bundleId) {
        return service.status(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId,bundleId);
    }
    private void requireEnabled(){if(!workflowEnabled)throw new BusinessException(503,"正式作答流程尚未开放，请保留原录音并联系教师");}
}
