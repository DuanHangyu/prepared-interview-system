package system.assessment.defense.prepared.processing;

import cn.dev33.satoken.annotation.*;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@SaCheckRole(value={"TEACHER","ADMIN"},mode=SaMode.OR)
@RequestMapping("/backend/prepared-reviews")
public class PreparedReviewController {
    private final PreparedReviewService service;private final PreparedReviewAudioService audio;
    private final system.assessment.defense.prepared.attempt.PreparedAnswerEvidenceService evidence;
    public PreparedReviewController(PreparedReviewService service,PreparedReviewAudioService audio,system.assessment.defense.prepared.attempt.PreparedAnswerEvidenceService evidence){this.service=service;this.audio=audio;this.evidence=evidence;}
    @GetMapping("/{assessmentId}") public PreparedReviewService.Roster roster(@PathVariable @Min(1) int assessmentId,@RequestParam(defaultValue="0") @Min(0) int afterRecordId,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size){return service.roster(StpUtil.getLoginIdAsInt(),assessmentId,afterRecordId,size);}
    @GetMapping("/{assessmentId}/students/{studentId}/attempts") public PreparedReviewService.Attempts attempts(@PathVariable @Min(1) int assessmentId,@PathVariable @Min(1) int studentId,@RequestParam(defaultValue="0") @Min(0) int afterAttemptNo,@RequestParam(defaultValue="10") @Min(1) @Max(20) int size){return service.attempts(StpUtil.getLoginIdAsInt(),assessmentId,studentId,afterAttemptNo,size);}
    @GetMapping("/{assessmentId}/attempts/{attemptId}") public PreparedReviewService.Interview interview(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId){return service.interview(StpUtil.getLoginIdAsInt(),assessmentId,attemptId);}
    @GetMapping("/{assessmentId}/attempts/{attemptId}/answers/{answerId}") public PreparedReviewService.Answer answer(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId,@PathVariable String answerId,@RequestParam(required=false) String afterResultId,@RequestParam(defaultValue="10") @Min(1) @Max(20) int size){return service.answer(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId,afterResultId,size);}
    @GetMapping("/{assessmentId}/attempts/{attemptId}/answers/{answerId}/audio") public PreparedReviewAudioService.Audio audio(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId,@PathVariable String answerId){return audio.preview(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId);}
    @GetMapping("/{assessmentId}/attempts/{attemptId}/answers/{answerId}/evidence") public system.assessment.defense.prepared.attempt.PreparedAnswerEvidenceService.Evidence evidence(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId,@PathVariable String answerId,@RequestParam(defaultValue="0") @Min(0) @Max(1000) int afterSegmentOrdinal,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size){return evidence.read(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId,afterSegmentOrdinal,size);}
    @PostMapping(value="/{assessmentId}/attempts/{attemptId}/answers/{answerId}/confirm",consumes="application/json") public PreparedProcessingService.ConfirmReceipt confirm(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId,@PathVariable String answerId,@RequestBody byte[] input){return service.confirm(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId,PreparedReviewCommands.confirm(input));}
    @PostMapping(value="/{assessmentId}/attempts/{attemptId}/answers/{answerId}/regrade",consumes="application/json") public PreparedProcessingService.RegradeReceipt regrade(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId,@PathVariable String answerId,@RequestBody byte[] input){return service.regrade(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId,PreparedReviewCommands.regrade(input));}
    @PostMapping(value="/{assessmentId}/attempts/{attemptId}/answers/{answerId}/adjust",consumes="application/json") public PreparedProcessingService.AdjustReceipt adjust(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId,@PathVariable String answerId,@RequestBody byte[] input){return service.adjust(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId,PreparedReviewCommands.adjust(input));}
    @GetMapping("/{assessmentId}/attempts/{attemptId}/answers/{answerId}/manual-history") public PreparedProcessingService.ManualPage manualHistory(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId,@PathVariable String answerId,@RequestParam(required=false) String afterManualScoreId,@RequestParam(defaultValue="10") @Min(1) @Max(20) int size){return service.manualHistory(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,answerId,afterManualScoreId,size);}
}
