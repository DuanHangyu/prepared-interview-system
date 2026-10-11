package system.assessment.defense.interfaces.rest.front;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import system.assessment.defense.application.dto.AssessmentSimpleDTO;
import system.assessment.defense.application.service.AssessmentAppService;

/**
 * @USER taoHouChao
 * @DATE 19:48 2025/8/7
 */
@RestController
@RequestMapping("/assessment")
@Tag(name = "考核信息管理", description = "考核信息管理")
@Slf4j
@SaCheckRole("STUDENT")
public class AssessmentController {

    @Resource
    private AssessmentAppService assessmentAppService;

    @GetMapping("/detail")
    @Operation(summary = "获取考核信息")
    public AssessmentSimpleDTO detail(@RequestParam("assessmentId") Integer assessmentId){
        int studentId = StpUtil.getLoginIdAsInt();
        return assessmentAppService.detail(studentId, assessmentId);
    }
}
