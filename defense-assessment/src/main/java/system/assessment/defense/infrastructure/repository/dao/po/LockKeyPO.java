package system.assessment.defense.infrastructure.repository.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
* @USER taoHouChao
* @DATE 00:48 2025/10/22
*/
@Data
@Schema(description = "锁表")
@TableName("lock_key")
@NoArgsConstructor
public class LockKeyPO {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String lockKeyName;

    public LockKeyPO(String lockKeyName){
        this.lockKeyName = lockKeyName;
    }
}
