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
import java.util.List;

/** Authenticated bounded streaming, no signed-object URLs or long-term credentials reach the browser. */
@RestController
@Validated
@RequestMapping("/backend/prepared-interviews")
@SaCheckRole("STUDENT")
public class PreparedAnswerUploadController {
    private final PreparedAnswerUploadService service;
    private final boolean workflowEnabled;
    public PreparedAnswerUploadController(PreparedAnswerUploadService service,@Value("${prepared.interview.workflow-enabled:false}") boolean workflowEnabled) {
        this.service=service;this.workflowEnabled=workflowEnabled;
    }
    @PutMapping(value="/{assessmentId}/attempts/{attemptId}/answers/{answerId}/chunks/{segmentId}/{index}",consumes="application/octet-stream")
    public PreparedAnswerUpload.Receipt upload(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId,@PathVariable String answerId,
            @PathVariable String segmentId,@PathVariable int index,@RequestParam String captureClientId,@RequestParam int segmentOrdinal,
            @RequestParam String mimeType,@RequestParam int byteSize,@RequestParam String sha256,HttpServletRequest request)throws IOException {
        if(!workflowEnabled)throw new BusinessException(503,"正式作答流程尚未开放，请保留原录音并联系教师");
        return service.upload(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId,
                new PreparedAnswerUpload.Command(captureClientId,segmentId,segmentOrdinal,mimeType,index,byteSize,sha256),request.getInputStream());
    }
    @GetMapping("/{assessmentId}/attempts/{attemptId}/answers/{answerId}/chunks")
    public List<PreparedAnswerUpload.Receipt> receipts(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId,@PathVariable String answerId) {
        return service.receipts(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId);
    }
    @GetMapping("/{assessmentId}/attempts/{attemptId}/answers/{answerId}/upload-window")
    public PreparedUploadWindow.View window(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId,@PathVariable String answerId,@RequestParam String captureClientId){
        return service.window(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId,captureClientId);
    }
}
