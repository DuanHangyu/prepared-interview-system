package system.assessment.defense.interfaces.rest.front;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.application.dto.*;
import system.assessment.defense.application.dto.front.StudentAppointmentCmd;
import system.assessment.defense.application.dto.front.StudentCancelAppointmentCmd;
import system.assessment.defense.application.service.StudentAssessmentService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @USER taoHouChao
 * @DATE 09:42 2025/8/13
 */
@RestController
@RequestMapping("/front/student-assessment")
@Tag(name = "学生考核管理", description = "学生考核管理")
@SaCheckRole("STUDENT")
public class StudentAssessmentController {

    @Resource
    private StudentAssessmentService studentAssessmentService;

    @GetMapping("/get-machine-time")
    @Operation(summary = "获取当前时间")
    public String getLocalDateTime(){
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @GetMapping("/statistic")
    @Operation(summary = "获取学生考核统计信息")
    public StudentDefenseStatisticDTO studentDefenseStatistic(){
        int studentId = StpUtil.getLoginIdAsInt();
        return studentAssessmentService.studentDefenseStatistic(studentId);
    }

    @PostMapping("/to-appointment-list")
    @Operation(summary = "获取待预约列表")
    public IPage<StudentTodoDefenseDTO> toAppointmentList(@Valid @RequestBody StudentDefensePagingQuery pagingQuery){
        int studentId = StpUtil.getLoginIdAsInt();
        return studentAssessmentService.toAppointmentList(pagingQuery, studentId);
    }

    @PostMapping("/todo-list")
    @Operation(summary = "获取待考核列表")
    public IPage<StudentTodoDefenseDTO> todoDefenseList(@Valid @RequestBody StudentDefensePagingQuery pagingQuery){
        int studentId = StpUtil.getLoginIdAsInt();
        return studentAssessmentService.todoDefenseList(pagingQuery, studentId);
    }

    @PostMapping("/analysis-list")
    @Operation(summary = "获取分析中列表")
    public IPage<StudentTodoDefenseDTO> analysisDefenseList(@Valid @RequestBody StudentDefensePagingQuery pagingQuery){
        int studentId = StpUtil.getLoginIdAsInt();
        return studentAssessmentService.analysisDefenseList(pagingQuery, studentId);
    }

    @PostMapping("/done-list")
    @Operation(summary = "获取已完成列表")
    public IPage<StudentDoneDefenseDTO> doneDefenseList(@Valid @RequestBody StudentDefensePagingQuery pagingQuery){
        int studentId = StpUtil.getLoginIdAsInt();
        return studentAssessmentService.doneDefenseList(pagingQuery, studentId);
    }

    @GetMapping("/assessment-detail")
    @Operation(summary = "获取考核信息")
    public StudentAssessmentDetailDTO assessmentDetail(@RequestParam("id") Integer id){
        return studentAssessmentService.assessmentDetail(StpUtil.getLoginIdAsInt(), id);
    }

    @PostMapping("/upload-file")
    @Operation(summary = "上传答辩文件")
    public Boolean uploadDefenseFile(@Valid @RequestBody StudentDefenseFileUploadCmd uploadCmd){
        int studentId = StpUtil.getLoginIdAsInt();
        return studentAssessmentService.uploadDefenseFile(uploadCmd, studentId);
    }

    @PostMapping("/appointment")
    @Operation(summary = "预约考核")
    public Boolean studentAppointment(@Valid @RequestBody StudentAppointmentCmd appointmentCmd){
        int studentId = StpUtil.getLoginIdAsInt();
        return studentAssessmentService.studentAppointment(studentId, appointmentCmd);
    }

    @PostMapping("/cancel-appointment")
    @Operation(summary = "取消预约")
    public Boolean cancelAppointment(@Valid @RequestBody StudentCancelAppointmentCmd cancelAppointmentCmd){
        int studentId = StpUtil.getLoginIdAsInt();
        return studentAssessmentService.cancelAppointment(studentId, cancelAppointmentCmd.getAssessmentId());
    }
}
