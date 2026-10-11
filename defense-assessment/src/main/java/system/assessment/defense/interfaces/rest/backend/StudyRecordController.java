package system.assessment.defense.interfaces.rest.backend;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.application.dto.StudentAssessmentDetailDTO;
import system.assessment.defense.application.dto.StudyRecordDTO;
import system.assessment.defense.application.dto.StudyRecordPagingQuery;
import system.assessment.defense.application.dto.backed.RecordRetakeCmd;
import system.assessment.defense.application.dto.backed.StudentRecordChangeScoreCmd;
import system.assessment.defense.application.dto.backed.StudentRecordExcelQuery;
import system.assessment.defense.application.service.StudentAssessmentService;
import system.assessment.defense.application.service.StudyRecordService;

import java.io.IOException;

/**
 * @USER taoHouChao
 * @DATE 13:38 2025/8/17
 */
@RestController
@RequestMapping("/backend/study-record")
@Tag(name = "后台学习记录管理", description = "后台学习记录管理")
@SaCheckRole(value = {"TEACHER", "ADMIN"}, mode = SaMode.OR)
public class StudyRecordController {

    @Resource
    private StudyRecordService studyRecordService;

    @Resource
    private StudentAssessmentService studentAssessmentService;

    @PostMapping("/list")
    @Operation(summary = "获取学习记录列表")
    public IPage<StudyRecordDTO> recordList(@Valid @RequestBody StudyRecordPagingQuery pagingQuery){
        return studyRecordService.recordList(StpUtil.getLoginIdAsInt(), pagingQuery);
    }

    @PostMapping("/change-score")
    @Operation(summary = "修改学生记录得分")
    public Boolean changeScore(@Valid @RequestBody StudentRecordChangeScoreCmd changeScoreCmd){
        return studyRecordService.changeScore(StpUtil.getLoginIdAsInt(), changeScoreCmd);
    }

    @GetMapping("/assessment-detail")
    @Operation(summary = "获取考核信息")
    public StudentAssessmentDetailDTO assessmentDetail(@RequestParam("id") Integer id){
        return studentAssessmentService.assessmentDetailByRecordId(StpUtil.getLoginIdAsInt(), id);
    }

    @PostMapping("/export-record")
    @Operation(summary = "导出学习记录")
    public void exportRecordExcel(@Valid @RequestBody StudentRecordExcelQuery excelQuery) throws IOException {
        studyRecordService.exportRecordExcel(StpUtil.getLoginIdAsInt(), excelQuery);
    }

    @PostMapping("/retake")
    @Operation(summary = "重新考核")
    public Boolean retake(@Valid @RequestBody RecordRetakeCmd retakeCmd){
        return studyRecordService.retake(StpUtil.getLoginIdAsInt(), retakeCmd);
    }

    @PostMapping("/reanalyze")
    @Operation(summary = "重新分析考核（重新生成评分，救评分卡死）")
    public Boolean reanalyze(@Valid @RequestBody RecordRetakeCmd reanalyzeCmd){
        return studentAssessmentService.reanalyze(StpUtil.getLoginIdAsInt(), reanalyzeCmd.getRecordId());
    }
}
