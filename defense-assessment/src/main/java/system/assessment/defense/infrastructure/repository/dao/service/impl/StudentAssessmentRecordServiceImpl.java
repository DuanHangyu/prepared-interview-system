package system.assessment.defense.infrastructure.repository.dao.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.infrastructure.repository.dao.mapper.StudentAssessmentRecordMapper;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @USER taoHouChao
 * @DATE 20:39 2025/8/14
 */
@Repository
@RequiredArgsConstructor
public class StudentAssessmentRecordServiceImpl extends ServiceImpl<StudentAssessmentRecordMapper, StudentAssessmentRecordPO>
        implements StudentAssessmentRecordService {

    private final StudentAssessmentRecordMapper recordMapper;

    @Override
    public List<StudentAssessmentRecordPO> findByStudentId(Integer studentId) {
        return this.list(Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                .eq(StudentAssessmentRecordPO::getStudentId, studentId));
    }

    @Override
    public Optional<StudentAssessmentRecordPO> findByStudentIdAndAssessmentId(Integer studentId, Integer assessmentId) {
        return Optional.ofNullable(this.getOne(Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                .eq(StudentAssessmentRecordPO::getStudentId, studentId)
                .eq(StudentAssessmentRecordPO::getAssessmentId, assessmentId)));
    }

    @Override
    public List<StudentAssessmentRecordPO> findByStudentIdAndAssessmentIds(Integer studentId, List<Integer> assessmentIds) {
        return this.list(Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                .eq(StudentAssessmentRecordPO::getStudentId, studentId)
                .in(StudentAssessmentRecordPO::getAssessmentId, assessmentIds));
    }

    @Override
    public List<StudentAssessmentRecordPO> findbyStudentIds(List<Integer> studentIds) {
        return this.list(Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                .in(StudentAssessmentRecordPO::getStudentId, studentIds));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean startInterview(Integer studentId, Integer assessmentId, LocalDateTime startedAt) {
        if (recordMapper.resetForInterviewStart(studentId, assessmentId, startedAt) > 0) {
            return true;
        }
        StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                .studentId(studentId)
                .assessmentId(assessmentId)
                .startDefenseTime(startedAt)
                .createTime(startedAt)
                .updateTime(startedAt)
                .build();
        return recordMapper.insertInterviewStartIfAbsent(record) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean startQuestionPhase(Integer studentId, Integer assessmentId, LocalDateTime startedAt,
                                      boolean requireCompletedDefense) {
        if (recordMapper.markQuestionPhaseStarted(
                studentId, assessmentId, startedAt, requireCompletedDefense) > 0) {
            return true;
        }
        if (requireCompletedDefense) {
            return false;
        }
        StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                .studentId(studentId)
                .assessmentId(assessmentId)
                .questionStartedAt(startedAt)
                .createTime(startedAt)
                .updateTime(startedAt)
                .build();
        return recordMapper.insertQuestionPhaseIfAbsent(record) > 0;
    }

    @Override
    public boolean releaseQuestionPhase(Integer studentId, Integer assessmentId,
                                        LocalDateTime startedAt, LocalDateTime failedAt) {
        return recordMapper.releaseQuestionPhase(studentId, assessmentId, startedAt, failedAt) > 0;
    }

    @Override
    public boolean claimInitialAnalysis(Integer recordId, LocalDateTime claimedAt) {
        return recordMapper.claimInitialAnalysis(recordId, claimedAt) > 0;
    }

    @Override
    public boolean claimManualAnalysis(Integer recordId, LocalDateTime claimedAt) {
        return recordMapper.claimManualAnalysis(recordId, claimedAt) > 0;
    }

    @Override
    public boolean claimRetryAnalysis(Integer recordId, LocalDateTime staleBefore, LocalDateTime claimedAt) {
        return recordMapper.claimRetryAnalysis(recordId, staleBefore, claimedAt) > 0;
    }

    @Override
    public boolean completeAnalysis(Integer recordId, String result, LocalDateTime finishedAt) {
        return recordMapper.completeAnalysis(recordId, result, finishedAt) > 0;
    }

    @Override
    public boolean releaseAnalysisClaim(Integer recordId, LocalDateTime finishedAt) {
        return recordMapper.releaseAnalysisClaim(recordId, finishedAt) > 0;
    }
}
