package system.assessment.defense.application.dto;

import cn.hutool.core.annotation.Alias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 学生导出专用模型。密码只允许作为导入输入，绝不能出现在导出文件中。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentExportExcelDTO {

    @Alias("姓名")
    private String name;

    @Alias("学号")
    private String account;

    @Alias("班级")
    private String schoolClass;
}
