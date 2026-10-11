package system.assessment.defense.interfaces.rest.backend;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.application.dto.*;
import system.assessment.defense.application.service.TeacherAppService;

import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 06:47 2025/8/14
 */
@RestController
@RequestMapping("/backend/teacher")
@Tag(name = "教师管理", description = "教师管理")
@SaCheckRole(value = {"TEACHER", "ADMIN"}, mode = SaMode.OR)
@Validated
public class TeacherBackendController {

    @Resource
    private TeacherAppService teacherAppService;

    @PostMapping("/page-list")
    @Operation(summary = "分页查询教师")
    @SaCheckRole("ADMIN")
    public IPage<TeacherDTO> pageList(@Valid @RequestBody TeacherPageQuery query){
        return teacherAppService.pageList(query);
    }

    @PostMapping("/create")
    @Operation(summary = "创建教师")
    @SaCheckRole("ADMIN")
    public Boolean createStudent(@Valid @RequestBody TeacherCreateCmd createCmd){
        return teacherAppService.createStudent(createCmd);
    }

    @PostMapping("/modify")
    @Operation(summary = "修改教师")
    @SaCheckRole("ADMIN")
    public Boolean modifyStudent(@Valid @RequestBody TeacherModifyCmd modifyCmd){
        return teacherAppService.modifyStudent(modifyCmd);
    }

    @PostMapping("/delete")
    @Operation(summary = "删除教师")
    @SaCheckRole("ADMIN")
    public Boolean deleteStudent(@Valid @RequestBody TeacherRemoveCmd removeCmd){
        return teacherAppService.deleteStudent(removeCmd);
    }

    @GetMapping("/all-teacher")
    @Operation(summary = "搜索教师选项")
    public List<TeacherOptionDTO> allTeacher(
            @RequestParam(required = false)
            @Size(max = 100, message = "教师搜索关键词不能超过100个字符") String keyword){
        return teacherAppService.allTeacherOptions(keyword);
    }

}
