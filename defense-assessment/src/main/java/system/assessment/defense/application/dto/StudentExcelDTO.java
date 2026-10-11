package system.assessment.defense.application.dto;

import cn.hutool.core.annotation.Alias;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @USER taoHouChao
 * @DATE 11:14 2025/8/8
 */
@Data
@Schema(description = "学生信息")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StudentExcelDTO {

    @Alias("姓名")
    private String name;

    @Alias("学号")
    private String account;

    @Alias("密码")
    private String password;

    @Alias("班级")
    private String schoolClass;

    public static StudentExcelDTO empty(){
        return StudentExcelDTO.builder().build();
    }
}
