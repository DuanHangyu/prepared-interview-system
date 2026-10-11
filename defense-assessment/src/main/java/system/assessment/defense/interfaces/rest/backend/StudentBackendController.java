package system.assessment.defense.interfaces.rest.backend;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import system.assessment.defense.application.dto.*;
import system.assessment.defense.application.dto.backed.SchoolClassStudentDTO;
import system.assessment.defense.application.service.StudentAppService;
import system.assessment.defense.application.service.FileSecurityService;
import system.assessment.defense.infrastructure.common.ExcelUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 21:03 2025/8/7
 */
@RestController
@RequestMapping("/backend/student")
@Tag(name = "学生管理", description = "学生管理")
@Validated
@SaCheckRole(value = {"TEACHER", "ADMIN"}, mode = SaMode.OR)
public class StudentBackendController {

    @Resource
    private StudentAppService studentAppService;

    @Resource
    private FileSecurityService fileSecurityService;

    @PostMapping("/page-list")
    @Operation(summary = "分页查询学生")
    public IPage<StudentDTO> pageList(@Valid @RequestBody StudentPageQuery query){
        return studentAppService.pageList(query);
    }

    @PostMapping("/create")
    @Operation(summary = "创建学生")
    @SaCheckRole("ADMIN")
    public Boolean createStudent(@Valid @RequestBody StudentCreateCmd createCmd){
        return studentAppService.createStudent(createCmd);
    }

    @PostMapping("/modify")
    @Operation(summary = "修改学生")
    @SaCheckRole("ADMIN")
    public Boolean modifyStudent(@Valid @RequestBody StudentModifyCmd modifyCmd){
        return studentAppService.modifyStudent(modifyCmd);
    }

    @PostMapping("/delete")
    @Operation(summary = "删除学生")
    @SaCheckRole("ADMIN")
    public Boolean deleteStudent(@Valid @RequestBody StudentRemoveCmd removeCmd){
        return studentAppService.deleteStudent(removeCmd);
    }

    @PostMapping("/export-template")
    @Operation(summary = "下载模板")
    @SaCheckRole("ADMIN")
    public void exportTemplate() throws IOException {
        List<StudentExcelDTO> studentExcelList = new ArrayList<>(1);
        studentExcelList.add(StudentExcelDTO.empty());
        ExcelUtils.exportTemplate(studentExcelList, "学生导入模版");
    }

    @PostMapping("/import")
    @Operation(summary = "导入学生")
    @SaCheckRole("ADMIN")
    public Boolean importStudent(MultipartFile file){
        fileSecurityService.requireSafeXlsx(file);
        List<StudentExcelDTO> students = ExcelUtils.readExcel(file, StudentExcelDTO.class);
        if (CollectionUtils.isEmpty(students)) {
            return true;
        }
        return studentAppService.importStudent(students);
    }

    @PostMapping("/export")
    @Operation(summary = "导出学生")
    @SaCheckRole("ADMIN")
    public void exportStudent(@Valid @RequestBody StudentExportCmd exportCmd) throws IOException {
        List<StudentExportExcelDTO> studentExcels = studentAppService.exportStudent(exportCmd);
        ExcelUtils.exportExcel(studentExcels);
    }

    @GetMapping("/school-class-student")
    @Operation(summary = "获取班级选项")
    public List<SchoolClassStudentDTO> schoolClassStudentList() {
        return studentAppService.allSchoolClassOptions().stream()
                .map(schoolClass -> {
                    SchoolClassStudentDTO schoolClassStudentDTO = new SchoolClassStudentDTO();
                    schoolClassStudentDTO.setSchoolClass(schoolClass);
                    schoolClassStudentDTO.setStudents(List.of());
                    return schoolClassStudentDTO;
                }).toList();
    }

    @GetMapping("/school-class-student-options")
    @Operation(summary = "按班级获取学生选项")
    public List<StudentOptionDTO> schoolClassStudentOptions(
            @RequestParam
            @NotBlank(message = "班级名称不能为空")
            @Size(max = 255, message = "班级名称不能超过255个字符") String schoolClass) {
        return studentAppService.studentOptionsBySchoolClass(schoolClass);
    }
}
