package system.assessment.defense.prepared.attempt;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.infrastructure.exception.BusinessException;

/** Durable HTTP commands. An end declaration never represents verified cloud receipt. */
@RestController
@Validated
@RequestMapping("/backend/prepared-interviews")
@SaCheckRole("STUDENT")
public class PreparedStudentAttemptController {
    private final PreparedAttemptStartService attempts;
    private final PreparedQuestionGrantService questions;
    private final PreparedAnswerEndService endings;
    private final boolean workflowEnabled;

    public PreparedStudentAttemptController(PreparedAttemptStartService attempts, PreparedQuestionGrantService questions,
            PreparedAnswerEndService endings, @Value("${prepared.interview.workflow-enabled:false}") boolean workflowEnabled) {
        this.attempts = attempts;
        this.questions = questions;
        this.endings = endings;
        this.workflowEnabled = workflowEnabled;
    }

    @PostMapping("/{assessmentId}/attempts")
    public PreparedAttemptStart.Summary start(@PathVariable @Min(1) int assessmentId,
            @RequestBody PreparedAttemptStart.Command command) {
        requireWorkflow();
        return attempts.start(StpUtil.getLoginIdAsInt(), assessmentId, command);
    }

    @GetMapping("/{assessmentId}/attempts/current")
    public PreparedAttemptStart.Summary current(@PathVariable @Min(1) int assessmentId) {
        return attempts.current(StpUtil.getLoginIdAsInt(), assessmentId).orElse(null);
    }

    @PostMapping("/{assessmentId}/attempts/{attemptId}/questions/start")
    public PreparedQuestionGrant.Grant startQuestion(@PathVariable @Min(1) int assessmentId,
            @PathVariable String attemptId, @RequestBody PreparedQuestionGrant.Command command) {
        requireWorkflow();
        return questions.start(StpUtil.getLoginIdAsInt(), assessmentId, attemptId, command);
    }

    @GetMapping("/{assessmentId}/attempts/{attemptId}/questions/current")
    public PreparedQuestionGrant.Grant currentQuestion(@PathVariable @Min(1) int assessmentId,
            @PathVariable String attemptId, @RequestParam String captureClientId) {
        return questions.current(StpUtil.getLoginIdAsInt(), assessmentId, attemptId, captureClientId).orElse(null);
    }

    @PostMapping("/{assessmentId}/attempts/{attemptId}/answers/{answerId}/end")
    public PreparedAnswerEnd.Summary end(@PathVariable @Min(1) int assessmentId, @PathVariable String attemptId,
            @PathVariable String answerId, @RequestBody PreparedAnswerEnd.Command command) {
        requireWorkflow();
        return endings.end(StpUtil.getLoginIdAsInt(), assessmentId, attemptId, answerId, command);
    }

    private void requireWorkflow() {
        if (!workflowEnabled) throw new BusinessException(503, "正式作答流程尚未开放，请保留原回答并联系教师");
    }
}
