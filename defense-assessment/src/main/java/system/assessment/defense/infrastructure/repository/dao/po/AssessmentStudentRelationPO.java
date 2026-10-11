package system.assessment.defense.infrastructure.repository.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @USER taoHouChao
 * @DATE 08:23 2025/8/13
 */
@Data
@TableName("assessment_student_relation")
@Schema(description = "考核学生关系")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AssessmentStudentRelationPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private Integer assessmentId;

    private Integer studentId;

    /**
     * 关联类型，0直接关联学生，1通过班级关联写生
     */
    private Integer relationType;

    private LocalDateTime createTime;
}
