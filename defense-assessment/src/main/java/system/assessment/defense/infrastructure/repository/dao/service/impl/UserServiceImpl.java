package system.assessment.defense.infrastructure.repository.dao.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Repository;
import system.assessment.defense.domain.entity.StudentSummary;
import system.assessment.defense.domain.entity.TeacherSummary;
import system.assessment.defense.infrastructure.repository.dao.mapper.*;
import system.assessment.defense.infrastructure.repository.dao.po.*;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @USER taoHouChao
 * @DATE 17:24 2025/8/12
 */
@Repository
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, UserPO> implements UserService {

    private final StudentAssessmentRecordMapper recordMapper;

    private final AssessmentStudentRelationMapper relationMapper;

    private final StudentAssessmentAppointmentMapper appointmentMapper;

    private final StudentAssessmentAppointmentMapper assessmentAppointmentMapper;

    private final AssessmentSettingMapper settingMapper;

    private final AssessmentInviteMapper inviteMapper;

    @Override
    public Optional<UserPO> findByAccount(String account) {
        return Optional.ofNullable(this.getOne(Wrappers.lambdaQuery(UserPO.class)
                .eq(UserPO::getAccount, account)));
    }

    @Override
    public Optional<UserPO> findById(Integer userId) {
        return Optional.ofNullable(this.getById(userId));
    }

    @Override
    public boolean compareAndSetPassword(Integer userId, String expectedPassword, String replacementPassword) {
        if (userId == null || userId <= 0 || expectedPassword == null || expectedPassword.isBlank()
                || replacementPassword == null || replacementPassword.isBlank()) {
            throw new IllegalArgumentException("密码条件更新参数无效");
        }
        return baseMapper.compareAndSetPassword(userId, expectedPassword, replacementPassword) == 1;
    }

    @Override
    public List<UserPO> findByAccounts(List<String> accounts) {
        return this.list(Wrappers.lambdaQuery(UserPO.class)
                .in(UserPO::getAccount, accounts));
    }

    @Override
    public Optional<StudentSummary> findSummaryById(Integer userId) {
        return findById(userId).map(userPO -> {
            StudentSummary summary = new StudentSummary();
            summary.setId(userPO.getId());
            summary.setRelations(relationMapper.selectList(Wrappers.lambdaQuery(AssessmentStudentRelationPO.class)
                    .eq(AssessmentStudentRelationPO::getStudentId, userId)));
            List<StudentAssessmentAppointmentPO> studentAssessmentAppointmentPOS = appointmentMapper.selectWithLocationByStudentId(userId);
            // 如果考核了，不通过，可以重新预约，此时预约的状态为0，那已完成的考核就不要查出来了
            List<Integer> appointingAssessmentIds = studentAssessmentAppointmentPOS.stream()
                    .filter(item -> item.getState() == null || Objects.equals(item.getState(), 0) || Objects.equals(item.getState(), 1))
                    .map(StudentAssessmentAppointmentPO::getAssessmentId)
                    .distinct().toList();
            summary.setRecords(recordMapper.selectList(Wrappers.lambdaQuery(StudentAssessmentRecordPO.class)
                    .eq(StudentAssessmentRecordPO::getStudentId, userId)
                    .notIn(CollectionUtils.isNotEmpty(appointingAssessmentIds), StudentAssessmentRecordPO::getAssessmentId, appointingAssessmentIds)));
            summary.setAppointments(studentAssessmentAppointmentPOS);
            return summary;
        });
    }

    @Override
    public Optional<TeacherSummary> findTeacherSummaryById(Integer userId) {
        return findById(userId).map(teacher -> {
            TeacherSummary summary = new TeacherSummary();
            summary.setId(teacher.getId());
            summary.setType(teacher.getType());
            if (Objects.equals(teacher.getType(), 2)) {
                List<Integer> allStudentIds = this.list(Wrappers.lambdaQuery(UserPO.class)
                                .select(UserPO::getId)
                                .eq(UserPO::getType, 0))
                        .stream()
                        .map(UserPO::getId)
                        .toList();
                summary.setStudentIds(allStudentIds);
                summary.setManagedAssessmentIds(settingMapper.selectList(
                                Wrappers.lambdaQuery(AssessmentSettingPO.class)
                                        .select(AssessmentSettingPO::getId))
                        .stream()
                        .map(AssessmentSettingPO::getId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
                return summary;
            }

            List<Integer> mySettingIds = settingMapper.selectList(Wrappers.lambdaQuery(AssessmentSettingPO.class)
                    .select(AssessmentSettingPO::getId)
                    .eq(AssessmentSettingPO::getCreateId, userId))
                    .stream()
                    .map(AssessmentSettingPO::getId)
                    .toList();
            List<Integer> allMySettingIds = new ArrayList<>(mySettingIds);

            List<Integer> inviteSettingIds = inviteMapper.selectList(Wrappers.lambdaQuery(AssessmentInvitePO.class)
                            .select(AssessmentInvitePO::getAssessmentId)
                            .eq(AssessmentInvitePO::getInviteeTeacherId, userId))
                    .stream()
                    .map(AssessmentInvitePO::getAssessmentId)
                    .toList();
            allMySettingIds.addAll(inviteSettingIds);
            allMySettingIds = allMySettingIds.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            summary.setManagedAssessmentIds(allMySettingIds);
            if (CollectionUtils.isEmpty(allMySettingIds)) {
                summary.setStudentIds(List.of());
                return summary;
            }

            List<Integer> myStudentIds = relationMapper.selectList(Wrappers.lambdaQuery(AssessmentStudentRelationPO.class)
                            .select(AssessmentStudentRelationPO::getStudentId)
                            .in(AssessmentStudentRelationPO::getAssessmentId, allMySettingIds))
                    .stream()
                    .map(AssessmentStudentRelationPO::getStudentId)
                    .distinct()
                    .toList();
            summary.setStudentIds(myStudentIds);
            return summary;
        });
    }
}
