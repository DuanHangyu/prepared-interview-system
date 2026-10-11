package system.assessment.defense.prepared.processing;

import cn.dev33.satoken.annotation.*;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@SaCheckRole(value={"TEACHER","ADMIN"},mode=SaMode.OR)
@RequestMapping("/backend/prepared-reviews")
public class PreparedReviewSummaryController {
    private final PreparedReviewSummaryService service;
    private final PreparedStudentResultReportExporter exporter;
    @org.springframework.beans.factory.annotation.Autowired
    public PreparedReviewSummaryController(PreparedReviewSummaryService service,PreparedStudentResultReportExporter exporter){this.service=service;this.exporter=exporter;}
    public PreparedReviewSummaryController(PreparedReviewSummaryService service){this(service,new PreparedStudentResultReportExporter());}
    @GetMapping("/{assessmentId}/attempts/{attemptId}/summary")
    public PreparedReviewSummaryService.Summary summary(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId){
        return service.summary(StpUtil.getLoginIdAsInt(),assessmentId,attemptId);
    }
    @GetMapping("/{assessmentId}/attempts/{attemptId}/report")
    public PreparedReviewSummaryService.Report report(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId,@RequestParam String resultVersion){
        return service.report(StpUtil.getLoginIdAsInt(),assessmentId,attemptId,resultVersion);
    }
    @system.assessment.defense.infrastructure.config.BinaryResponse
    @GetMapping("/{assessmentId}/attempts/{attemptId}/export.xlsx")
    public void export(@PathVariable @Min(1) int assessmentId,@PathVariable String attemptId,@RequestParam String resultVersion,jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException{
        int actor=StpUtil.getLoginIdAsInt();exporter.writeReview(()->service.exportReport(actor,assessmentId,attemptId,resultVersion),response);
    }
}
