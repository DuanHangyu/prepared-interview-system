package system.assessment.defense.infrastructure.repository.dao.service;

import com.baomidou.mybatisplus.extension.service.IService;
import system.assessment.defense.domain.entity.StudentSummary;
import system.assessment.defense.domain.entity.TeacherSummary;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;

import java.util.List;
import java.util.Optional;

public interface UserService extends IService<UserPO> {
    /**
     * 根据账号查询用户
     *
     * @param account 账号
     * @return 用户
     */
    Optional<UserPO> findByAccount(String account);

    /**
     * 根据id查询用户
     *
     * @param userId 用户id
     * @return 用户
     */
    Optional<UserPO> findById(Integer userId);

    /** Replace only the credential, and only if the verified value is still current. */
    boolean compareAndSetPassword(Integer userId, String expectedPassword, String replacementPassword);

    /**
     * 根据账号查询用户
     *
     * @param accounts 账号
     * @return 用户
     */
    List<UserPO> findByAccounts(List<String> accounts);

    /**
     * 根据id查询用户
     *
     * @param userId 用户id
     * @return 用户
     */
    Optional<StudentSummary> findSummaryById(Integer userId);

    /**
     * 根据id查询用户
     *
     * @param userId 用户id
     * @return 用户
     */
    Optional<TeacherSummary> findTeacherSummaryById(Integer userId);
}
