package system.assessment.defense.application.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.dto.*;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentInvitePO;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentInviteService;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.List;
import java.util.Objects;

/**
 * @USER taoHouChao
 * @DATE 06:47 2025/8/14
 */
@Service
@RequiredArgsConstructor
public class TeacherAppService {

    private static final int MAX_TEACHER_OPTIONS = 50;

    private final UserService userService;
    private final PasswordSecurityService passwordSecurityService;
    private final AssessmentSettingService assessmentSettingService;
    private final AssessmentInviteService assessmentInviteService;
    public IPage<TeacherDTO> pageList(TeacherPageQuery query) {
        IPage<UserPO> page = userService.page(new Page<>(query.getPage(), query.getSize()),
                Wrappers.lambdaQuery(UserPO.class)
                        .eq(UserPO::getType, 1)
                        .like(Objects.nonNull(query.getName()), UserPO::getName, query.getName())
                        .like(Objects.nonNull(query.getPhone()), UserPO::getPhone, query.getPhone())
                        .like(Objects.nonNull(query.getAccount()), UserPO::getAccount, query.getAccount()));
        return page.convert(studentPO -> BeanUtil.copyProperties(studentPO, TeacherDTO.class));
    }

    public Boolean createStudent(TeacherCreateCmd createCmd) {
        UserPO userPO = BeanUtil.copyProperties(createCmd, UserPO.class);
        userPO.setType(1);
        userPO.setPassword(passwordSecurityService.encode(userPO.getPassword()));
        return userService.save(userPO);
    }

    public Boolean modifyStudent(TeacherModifyCmd modifyCmd) {
        requireTeacher(modifyCmd.getId());
        UserPO userPO = BeanUtil.copyProperties(modifyCmd, UserPO.class);
        userPO.setType(1);
        userPO.setPassword(StringUtils.isBlank(modifyCmd.getPassword())
                ? null
                : passwordSecurityService.encode(modifyCmd.getPassword()));
        return userService.updateById(userPO);
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteStudent(TeacherRemoveCmd removeCmd) {
        Integer teacherId = removeCmd.getId();
        requireTeacher(teacherId);
        long ownedAssessments = assessmentSettingService.count(
                Wrappers.lambdaQuery(AssessmentSettingPO.class)
                        .eq(AssessmentSettingPO::getCreateId, teacherId));
        if (ownedAssessments > 0) {
            throw new BusinessException(ErrorCodeEnums.TEACHER_HAS_ASSESSMENTS);
        }
        assessmentInviteService.remove(Wrappers.lambdaQuery(AssessmentInvitePO.class)
                .and(query -> query.eq(AssessmentInvitePO::getInviteeTeacherId, teacherId)
                        .or()
                        .eq(AssessmentInvitePO::getInviterTeacherId, teacherId)));
        if (!userService.removeById(teacherId)) {
            throw new BusinessException(ErrorCodeEnums.USER_NOT_FOUND);
        }
        return true;
    }

    private UserPO requireTeacher(Integer teacherId) {
        UserPO target = userService.getById(teacherId);
        if (target == null || !Objects.equals(target.getType(), 1)) {
            throw new BusinessException(ErrorCodeEnums.USER_NOT_FOUND);
        }
        return target;
    }

    public List<TeacherOptionDTO> allTeacherOptions() {
        return allTeacherOptions(null);
    }

    public List<TeacherOptionDTO> allTeacherOptions(String keyword) {
        int teacherId = StpUtil.getLoginIdAsInt();
        String normalizedKeyword = StringUtils.trimToNull(keyword);
        return userService.list(Wrappers.lambdaQuery(UserPO.class)
                .select(UserPO::getId, UserPO::getName)
                .eq(UserPO::getType, 1)
                .ne(UserPO::getId, teacherId)
                .like(normalizedKeyword != null, UserPO::getName, normalizedKeyword)
                .orderByAsc(UserPO::getId)
                .last("LIMIT " + MAX_TEACHER_OPTIONS))
                .stream()
                .map(teacherPO -> BeanUtil.copyProperties(teacherPO, TeacherOptionDTO.class))
                .toList();
    }

}
