package system.assessment.defense.infrastructure.repository.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @USER taoHouChao
 * @DATE 17:22 2025/8/12
 */
@Data
@TableName("user")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserPO {

    public static final UserPO EMPTY_USER = UserPO.builder()
            .name("未知")
            .account("未知")
            .schoolClass("未知")
            .build();

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String name;

    private String account;

    // A null profile patch means "do not change the credential", even under global ALWAYS updates.
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private String password;

    private String phone;

    private Integer type;

    private String schoolClass;

    private String abilityAdvantageAnalysis;

    private String developmentPotentialAssessment;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
