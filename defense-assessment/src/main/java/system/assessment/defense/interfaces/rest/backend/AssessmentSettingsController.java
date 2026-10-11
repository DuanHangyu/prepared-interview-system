package system.assessment.defense.interfaces.rest.backend;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.application.dto.*;
import system.assessment.defense.application.dto.backed.AppointmentSettingCreateCmd;
import system.assessment.defense.application.dto.backed.AssessmentAppointSettingSummaryDTO;
import system.assessment.defense.application.dto.backed.CancelStudentAppointmentCmd;
import system.assessment.defense.application.dto.backed.LiftPunishCmd;
import system.assessment.defense.application.service.AssessmentAppService;

/**
 * @USER taoHouChao
 * @DATE 19:48 2025/8/7
 */
@RestController
@RequestMapping("/backend/assessment")
@Tag(name = "考核设置管理", description = "考核设置管理")
@Slf4j
@SaCheckRole(value = {"TEACHER", "ADMIN"}, mode = SaMode.OR)
public class AssessmentSettingsController {

    @Resource
    private AssessmentAppService assessmentAppService;

    @PostMapping("/list")
    @Operation(summary = "获取考核设置列表")
    public IPage<AssessmentSettingsDTO> list(@Valid @RequestBody AssessmentSettingPagingQuery pagingQuery) {
        return assessmentAppService.list(StpUtil.getLoginIdAsInt(), pagingQuery);
    }

    @PostMapping("/create")
    @Operation(summary = "创建考核")
    public Boolean createAssessmentSettings(@Valid @RequestBody AssessmentSettingsCreateCmd createCmd) {
        return assessmentAppService.createAssessmentSettings(createCmd);
    }

    @PostMapping("/modify")
    @Operation(summary = "修改考核")
    public Boolean modifyAssessmentSettings(@Valid @RequestBody AssessmentSettingsModifyCmd modifyCmd) {
        return assessmentAppService.modifyAssessmentSettings(StpUtil.getLoginIdAsInt(), modifyCmd);
    }

    @PostMapping("/remove")
    @Operation(summary = "删除考核")
    public Boolean removeAssessmentSettings(@Valid @RequestBody AssessmentSettingsRemoveCmd deleteCmd) {
        return assessmentAppService.removeAssessmentSettings(StpUtil.getLoginIdAsInt(), deleteCmd);
    }

    @PostMapping("/settingAppointment")
    @Operation(summary = "设置考核预约")
    public Boolean settingAppointment(@Valid @RequestBody AppointmentSettingCreateCmd appointmentCmd) {
        return assessmentAppService.settingAppointment(StpUtil.getLoginIdAsInt(), appointmentCmd);
    }

    @GetMapping("/setting-appointment-summary")
    @Operation(summary = "获取考核预约设置汇总")
    public AssessmentAppointSettingSummaryDTO settingAppointmentSummary(@RequestParam("id") @Schema(description = "考核ID") Integer id) {
        return assessmentAppService.settingAppointmentSummary(StpUtil.getLoginIdAsInt(), id);
    }

    @PostMapping("/lift-punish")
    @Operation(summary = "解除惩罚")
    public Boolean liftPunish(@Valid @RequestBody LiftPunishCmd liftPunishCmd) {
        return assessmentAppService.liftPunish(StpUtil.getLoginIdAsInt(), liftPunishCmd);
    }

    @PostMapping("/cancel-student-appointment")
    @Operation(summary = "取消学生预约")
    public Boolean cancelStudentAppointment(@Valid @RequestBody CancelStudentAppointmentCmd cancelStudentAppointmentCmd) {
        return assessmentAppService.cancelStudentAppointment(StpUtil.getLoginIdAsInt(), cancelStudentAppointmentCmd);
    }
}
