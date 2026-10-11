package system.assessment.defense.prepared.audio;

import cn.dev33.satoken.annotation.*;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.application.service.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import static system.assessment.defense.prepared.audio.PreparedAudio.*;

@RestController @Validated
@SaCheckRole(value={"TEACHER","ADMIN"},mode=SaMode.OR)
@RequestMapping("/backend/prepared-papers/{assessmentId}/audio")
public class PreparedAudioController {
    private final PreparedAudioService service;private final ResourceAccessControlService access;
    private final SecurityRateLimitService limits;private final PreparedPrivateOssStore store;
    public PreparedAudioController(PreparedAudioService service,ResourceAccessControlService access,SecurityRateLimitService limits,PreparedPrivateOssStore store){
        this.service=service;this.access=access;this.limits=limits;this.store=store;
    }
    @GetMapping public Listing list(@PathVariable @Min(1) int assessmentId){return service.list(StpUtil.getLoginIdAsInt(),assessmentId);}
    @GetMapping("/{assetId}") public View get(@PathVariable @Min(1) int assessmentId,@PathVariable String assetId){return service.get(StpUtil.getLoginIdAsInt(),assessmentId,assetId);}
    @PostMapping public View prepare(@PathVariable @Min(1) int assessmentId,@Valid @RequestBody Command command,HttpServletRequest request){
        int actor=StpUtil.getLoginIdAsInt();access.requireAssessmentManager(actor,assessmentId);limits.checkAiHttp(request.getRemoteAddr(),actor,5);
        return service.prepare(actor,assessmentId,command);
    }
    @PostMapping("/{assetId}/recover") public View recover(@PathVariable @Min(1) int assessmentId,@PathVariable String assetId,@Valid @RequestBody Recovery command){
        return service.recover(StpUtil.getLoginIdAsInt(),assessmentId,assetId,command);
    }
    @GetMapping("/{assetId}/preview") public PreparedPrivateOssStore.Preview preview(@PathVariable @Min(1) int assessmentId,@PathVariable String assetId,HttpServletResponse response){
        var saved=service.previewAsset(StpUtil.getLoginIdAsInt(),assessmentId,assetId);
        response.setHeader("Cache-Control","private, no-store");response.setHeader("Pragma","no-cache");
        try{return store.preview(saved);}catch(java.io.IOException error){throw new BusinessException(503,"私有音频试听暂不可用，已保存内容未删除，请稍后重试");}
    }
}
