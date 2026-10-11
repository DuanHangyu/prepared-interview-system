package system.assessment.defense.infrastructure.repository.dao.service;

import com.baomidou.mybatisplus.extension.service.IService;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StudentAssessmentRecordService extends IService<StudentAssessmentRecordPO> {
    /**
     * 根据学生id查询考核记录
     *
     * @param studentId 学生id
     * @return 考核记录
     */
    List<StudentAssessmentRecordPO> findByStudentId(Integer studentId);

    /**
     * 根据学生id和考核id查询考核记录
     *
     * @param studentId     学生id
     * @param assessmentId 考核id
     * @return 考核记录
     */
    Optional<StudentAssessmentRecordPO> findByStudentIdAndAssessmentId(Integer studentId, Integer assessmentId);

    /**
     * 根据学生id和考核id列表查询考核记录
     *
     * @param studentId     学生id
     * @param assessmentIds 考核id列表
     * @return 考核记录
     */
    List<StudentAssessmentRecordPO> findByStudentIdAndAssessmentIds(Integer studentId, List<Integer> assessmentIds);

    /**
     * 根据学生id列表查询考核记录
     *
     * @param studentIds 学生id列表
     * @return 考核记录
     */
    List<StudentAssessmentRecordPO> findbyStudentIds(List<Integer> studentIds);

    boolean startInterview(Integer studentId, Integer assessmentId, LocalDateTime startedAt);

    default boolean startQuestionPhase(Integer studentId, Integer assessmentId, LocalDateTime startedAt) {
        return startQuestionPhase(studentId, assessmentId, startedAt, false);
    }

    boolean startQuestionPhase(Integer studentId, Integer assessmentId, LocalDateTime startedAt,
                               boolean requireCompletedDefense);

    boolean releaseQuestionPhase(Integer studentId, Integer assessmentId,
                                 LocalDateTime startedAt, LocalDateTime failedAt);

    boolean claimInitialAnalysis(Integer recordId, LocalDateTime claimedAt);

    boolean claimManualAnalysis(Integer recordId, LocalDateTime claimedAt);

    boolean claimRetryAnalysis(Integer recordId, LocalDateTime staleBefore, LocalDateTime claimedAt);

    boolean completeAnalysis(Integer recordId, String result, LocalDateTime finishedAt);

    boolean releaseAnalysisClaim(Integer recordId, LocalDateTime finishedAt);
}
