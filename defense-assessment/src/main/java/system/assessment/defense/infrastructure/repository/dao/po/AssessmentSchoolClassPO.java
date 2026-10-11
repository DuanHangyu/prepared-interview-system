package system.assessment.defense.infrastructure.repository.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@TableName("assessment_school_class")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AssessmentSchoolClassPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private Integer assessmentId;

    private String schoolClass;

    private LocalDateTime createTime;
}
