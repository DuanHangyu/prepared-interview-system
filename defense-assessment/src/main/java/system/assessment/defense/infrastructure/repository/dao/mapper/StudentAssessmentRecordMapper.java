package system.assessment.defense.infrastructure.repository.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;

import java.time.LocalDateTime;

public interface StudentAssessmentRecordMapper extends BaseMapper<StudentAssessmentRecordPO> {

    @Update("""
            UPDATE student_assessment_record
            SET start_defense_time = #{startedAt},
                end_defense_time = NULL,
                question_started_at = NULL,
                defense_voice = '',
                defense_content = NULL,
                defense_result = NULL,
                score = -1,
                check_score = NULL,
                state = 0,
                re_analysis = 0,
                update_time = #{startedAt}
            WHERE student_id = #{studentId}
              AND assessment_id = #{assessmentId}
              AND (start_defense_time IS NULL OR state = 3)
              AND question_started_at IS NULL
            """)
    int resetForInterviewStart(@Param("studentId") Integer studentId,
                               @Param("assessmentId") Integer assessmentId,
                               @Param("startedAt") LocalDateTime startedAt);

    @Insert("""
            INSERT IGNORE INTO student_assessment_record
              (assessment_id, student_id, defense_voice, score, check_score,
               start_defense_time, state, re_analysis, create_time, update_time)
            VALUES
              (#{record.assessmentId}, #{record.studentId}, '', -1, NULL,
               #{record.startDefenseTime}, 0, 0, #{record.createTime}, #{record.updateTime})
            """)
    int insertInterviewStartIfAbsent(@Param("record") StudentAssessmentRecordPO record);

    @Update("""
            UPDATE student_assessment_record
            SET question_started_at = #{startedAt},
                defense_result = NULL,
                score = -1,
                check_score = NULL,
                state = 0,
                re_analysis = 0,
                update_time = #{startedAt}
            WHERE student_id = #{studentId}
              AND assessment_id = #{assessmentId}
              AND question_started_at IS NULL
              AND (state IS NULL OR state IN (0, 3))
              AND (#{requireCompletedDefense} = FALSE OR end_defense_time IS NOT NULL)
            """)
    int markQuestionPhaseStarted(@Param("studentId") Integer studentId,
                                 @Param("assessmentId") Integer assessmentId,
                                 @Param("startedAt") LocalDateTime startedAt,
                                 @Param("requireCompletedDefense") boolean requireCompletedDefense);

    @Update("""
            UPDATE student_assessment_record
            SET question_started_at = NULL,
                update_time = #{failedAt}
            WHERE student_id = #{studentId}
              AND assessment_id = #{assessmentId}
              AND question_started_at = #{startedAt}
              AND state = 0
              AND defense_result IS NULL
            """)
    int releaseQuestionPhase(@Param("studentId") Integer studentId,
                             @Param("assessmentId") Integer assessmentId,
                             @Param("startedAt") LocalDateTime startedAt,
                             @Param("failedAt") LocalDateTime failedAt);

    @Insert("""
            INSERT IGNORE INTO student_assessment_record
              (assessment_id, student_id, defense_voice, score, check_score,
               question_started_at, state, re_analysis, create_time, update_time)
            VALUES
              (#{record.assessmentId}, #{record.studentId}, '', -1, NULL,
               #{record.questionStartedAt}, 0, 0, #{record.createTime}, #{record.updateTime})
            """)
    int insertQuestionPhaseIfAbsent(@Param("record") StudentAssessmentRecordPO record);

    @Update("""
            UPDATE student_assessment_record
            SET state = 2, re_analysis = 1, update_time = #{claimedAt}
            WHERE id = #{recordId}
              AND state = 0
              AND (re_analysis = 0 OR re_analysis IS NULL)
            """)
    int claimInitialAnalysis(@Param("recordId") Integer recordId,
                             @Param("claimedAt") LocalDateTime claimedAt);

    @Update("""
            UPDATE student_assessment_record
            SET state = 2, re_analysis = 1, update_time = #{claimedAt}
            WHERE id = #{recordId}
              AND state IN (1, 2)
              AND (re_analysis = 0 OR re_analysis IS NULL)
            """)
    int claimManualAnalysis(@Param("recordId") Integer recordId,
                            @Param("claimedAt") LocalDateTime claimedAt);

    @Update("""
            UPDATE student_assessment_record
            SET re_analysis = 1, update_time = #{claimedAt}
            WHERE id = #{recordId}
              AND state = 2
              AND ((re_analysis = 0 OR re_analysis IS NULL) OR update_time < #{staleBefore})
            """)
    int claimRetryAnalysis(@Param("recordId") Integer recordId,
                           @Param("staleBefore") LocalDateTime staleBefore,
                           @Param("claimedAt") LocalDateTime claimedAt);

    @Update("""
            UPDATE student_assessment_record
            SET defense_result = #{result}, state = 1, re_analysis = 0, update_time = #{finishedAt}
            WHERE id = #{recordId} AND state = 2 AND re_analysis = 1
            """)
    int completeAnalysis(@Param("recordId") Integer recordId,
                         @Param("result") String result,
                         @Param("finishedAt") LocalDateTime finishedAt);

    @Update("""
            UPDATE student_assessment_record
            SET re_analysis = 0, update_time = #{finishedAt}
            WHERE id = #{recordId} AND state = 2 AND re_analysis = 1
            """)
    int releaseAnalysisClaim(@Param("recordId") Integer recordId,
                             @Param("finishedAt") LocalDateTime finishedAt);
}
