package system.assessment.defense.prepared.attempt;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.constraints.Min;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@SaCheckRole("STUDENT")
@RequestMapping("/backend/prepared-interviews")
public class PreparedQuestionAudioController {
    private final PreparedQuestionAudioService audio;
    public PreparedQuestionAudioController(PreparedQuestionAudioService audio){this.audio=audio;}
    @GetMapping("/{assessmentId}/attempts/{attemptId}/answers/{answerId}/question-audio")
    public ResponseEntity<PreparedQuestionAudioService.Audio> preview(@PathVariable @Min(1) int assessmentId,
            @PathVariable String attemptId,@PathVariable String answerId,@RequestParam String captureClientId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(audio.preview(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId,captureClientId));
    }
}
