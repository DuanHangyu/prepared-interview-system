package system.assessment.defense.infrastructure.repository.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;

import java.time.LocalDateTime;

public interface AssessmentSettingMapper extends BaseMapper<AssessmentSettingPO> {

    @Update("""
            UPDATE assessment
            SET deleted = 1, deleted_at = #{deletedAt}, update_time = #{deletedAt}
            WHERE id = #{assessmentId} AND deleted = 0
            """)
    int softDeleteWithTimestamp(@Param("assessmentId") Integer assessmentId,
                                @Param("deletedAt") LocalDateTime deletedAt);
}
