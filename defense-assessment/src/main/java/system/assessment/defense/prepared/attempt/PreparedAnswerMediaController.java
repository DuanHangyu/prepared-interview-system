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
@RequestMapping("/backend/prepared-interviews")
@SaCheckRole("STUDENT")
public class PreparedAnswerMediaController {
    private final PreparedAnswerMediaService service;private final boolean enabled;
    public PreparedAnswerMediaController(PreparedAnswerMediaService service,@Value("${prepared.interview.workflow-enabled:false}")boolean enabled){this.service=service;this.enabled=enabled;}
    @PostMapping("/{assessmentId}/attempts/{attemptId}/answers/{answerId}/media")
    public PreparedAnswerMediaService.Summary request(@PathVariable @Min(1)int assessmentId,@PathVariable String attemptId,@PathVariable String answerId,@RequestBody PreparedAnswerMediaService.Command command){
        if(!enabled)throw new BusinessException(503,"正式作答流程尚未开放，请保留原录音并联系教师");
        return service.request(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId,command);
    }
    @GetMapping("/{assessmentId}/attempts/{attemptId}/answers/{answerId}/media")
    public PreparedAnswerMediaService.Summary status(@PathVariable @Min(1)int assessmentId,@PathVariable String attemptId,@PathVariable String answerId){return service.status(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId);}
}
