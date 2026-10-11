package system.assessment.defense.infrastructure.repository.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;

public interface UserMapper extends BaseMapper<UserPO> {
    // Credentials are byte-exact even if the user table uses a case-insensitive collation.
    @Update("UPDATE `user` SET `password` = #{replacementPassword} WHERE `id` = #{userId} "
            + "AND CAST(`password` AS BINARY) = CAST(#{expectedPassword} AS BINARY)")
    int compareAndSetPassword(@Param("userId") Integer userId,
                             @Param("expectedPassword") String expectedPassword,
                             @Param("replacementPassword") String replacementPassword);
}
