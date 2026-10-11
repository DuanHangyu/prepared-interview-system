package system.assessment.defense.application.dto.backed;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import system.assessment.defense.application.dto.StudentOptionDTO;

import java.util.List;

/**
 * @USER taoHouChao
 * @DATE 17:16 2025/9/20
 */
@Data
@Schema(description = "班级学生信息")
public class SchoolClassStudentDTO {

    @Schema(description = "班级名称")
    private String schoolClass;

    @Schema(description = "班级学生信息")
    private List<StudentOptionDTO> students;
}
