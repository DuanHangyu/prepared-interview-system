package system.assessment.defense.prepared.paper;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.prepared.paper.PreparedPaper.*;

@RestController
@Validated
@RequestMapping("/backend/prepared-papers")
@SaCheckRole(value = {"TEACHER", "ADMIN"}, mode = SaMode.OR)
public class PreparedPaperController {
    private final PreparedPaperService service;

    public PreparedPaperController(PreparedPaperService service) { this.service = service; }

    @GetMapping("/{assessmentId}")
    public Draft get(@PathVariable @Min(1) int assessmentId) {
        return service.getDraft(StpUtil.getLoginIdAsInt(), assessmentId);
    }

    @PutMapping("/{assessmentId}")
    public Draft save(@PathVariable @Min(1) int assessmentId, @Valid @RequestBody SaveCommand command) {
        return service.save(StpUtil.getLoginIdAsInt(), assessmentId, command.expectedRevision(), command.questions());
    }

    @PostMapping("/{assessmentId}/approve")
    public Draft approve(@PathVariable @Min(1) int assessmentId, @Valid @RequestBody ApproveCommand command) {
        return service.approve(StpUtil.getLoginIdAsInt(), assessmentId, command.expectedRevision(), command.questionIds());
    }
}
