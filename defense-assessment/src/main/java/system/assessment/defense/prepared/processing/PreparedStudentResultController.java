package system.assessment.defense.prepared.processing;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@SaCheckRole("STUDENT")
@RequestMapping("/backend/prepared-results")
public class PreparedStudentResultController {
    private final PreparedStudentResultService service;
    private final PreparedStudentResultReportExporter exporter;
    @org.springframework.beans.factory.annotation.Autowired
    public PreparedStudentResultController(PreparedStudentResultService service,PreparedStudentResultReportExporter exporter){this.service=service;this.exporter=exporter;}
    public PreparedStudentResultController(PreparedStudentResultService service){this(service,new PreparedStudentResultReportExporter());}
    @GetMapping("/{assessmentId}") public PreparedStudentResultService.Summary summary(@PathVariable @Min(1) int assessmentId){return service.summary(StpUtil.getLoginIdAsInt(),assessmentId);}
    @GetMapping("/{assessmentId}/answers/{answerId}") public PreparedStudentResultService.Answer answer(@PathVariable @Min(1) int assessmentId,@PathVariable String answerId,@RequestParam String resultVersion){return service.answer(StpUtil.getLoginIdAsInt(),assessmentId,answerId,resultVersion);}
    @GetMapping("/{assessmentId}/report") public PreparedStudentResultService.Report report(@PathVariable @Min(1) int assessmentId,@RequestParam String resultVersion){return service.report(StpUtil.getLoginIdAsInt(),assessmentId,resultVersion);}
    @system.assessment.defense.infrastructure.config.BinaryResponse
    @GetMapping("/{assessmentId}/export.xlsx")
    public void export(@PathVariable @Min(1) int assessmentId,@RequestParam String resultVersion,jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException{
        int student=StpUtil.getLoginIdAsInt();exporter.write(()->service.exportReport(student,assessmentId,resultVersion),response);
    }
}
