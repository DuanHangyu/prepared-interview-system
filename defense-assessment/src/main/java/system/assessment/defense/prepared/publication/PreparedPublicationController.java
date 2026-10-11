package system.assessment.defense.prepared.publication;

import cn.dev33.satoken.annotation.*;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static system.assessment.defense.prepared.publication.PreparedPublication.*;

@RestController
@Validated
@SaCheckRole(value={"TEACHER","ADMIN"},mode=SaMode.OR)
@RequestMapping("/backend/prepared-papers/{assessmentId}/publications")
public class PreparedPublicationController {
    private final PreparedPublicationService service;
    public PreparedPublicationController(PreparedPublicationService service){this.service=service;}
    @GetMapping public Status status(@PathVariable @Min(1) int assessmentId) {return service.status(StpUtil.getLoginIdAsInt(),assessmentId);}
    @GetMapping("/{versionId}") public View get(@PathVariable @Min(1) int assessmentId,@PathVariable String versionId) {return service.get(StpUtil.getLoginIdAsInt(),assessmentId,versionId);}
    @PostMapping public View publish(@PathVariable @Min(1) int assessmentId,@Valid @RequestBody Command command) {return service.publish(StpUtil.getLoginIdAsInt(),assessmentId,command);}
}
