package system.assessment.defense.application.service;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import system.assessment.defense.application.dto.ChangePasswordDTO;
import system.assessment.defense.application.dto.LoginDTO;
import system.assessment.defense.application.dto.UserDetailDTO;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.emuns.UserRoleEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.Optional;

/**
 * @USER taoHouChao
 * @DATE 17:21 2025/8/12
 */
@Service
@RequiredArgsConstructor
public class LoginService {

    private final UserService userService;
    private final PasswordSecurityService passwordSecurityService;
    private final SecurityRateLimitService securityRateLimitService;

    public void login(LoginDTO loginDTO) {
        Optional<UserPO> userOp = userService.findByAccount(loginDTO.getAccount());
        if (userOp.isEmpty()) {
            securityRateLimitService.checkLoginAccount(loginDTO.getAccount());
            passwordSecurityService.verifyAgainstDummyHash(loginDTO.getPassword());
            throw invalidCredentials();
        }
        UserPO userPO = userOp.get();
        // Use authoritative identity: database collations can treat different spellings as one account.
        securityRateLimitService.checkLoginIdentity(userPO.getId());
        String storedPassword = userPO.getPassword();
        PasswordSecurityService.PasswordVerification verification =
                passwordSecurityService.verify(loginDTO.getPassword(), storedPassword);
        if (!verification.matched()) {
            throw invalidCredentials();
        }
        requireRole(userPO);
        if (verification.upgradedHash() != null) {
            if (!userService.compareAndSetPassword(userPO.getId(), storedPassword, verification.upgradedHash())) {
                throw new IllegalStateException("密码升级失败，请稍后重试");
            }
            userPO.setPassword(verification.upgradedHash());
        }
        CredentialSessionGuard.issueVerifiedSession(userPO, userService);
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(401, "账号或密码错误");
    }

    public UserDetailDTO userDetail(int userId) {
        Optional<UserPO> userOp = userService.findById(userId);
        UserPO userPO = userOp.orElseThrow(() -> new BusinessException(ErrorCodeEnums.USER_NOT_FOUND));
        UserRoleEnums role = requireRole(userPO);
        return UserDetailDTO.builder()
                .id(userPO.getId())
                .name(userPO.getName())
                .account(userPO.getAccount())
                .role(role.name())
                .build();
    }

    private static UserRoleEnums requireRole(UserPO user) {
        UserRoleEnums role = UserRoleEnums.getByRole(user.getType());
        if (role == null) {
            throw new BusinessException(ErrorCodeEnums.NO_PERMISSION);
        }
        return role;
    }

    public Boolean changePassword(int userId, ChangePasswordDTO changePassword) {
        if (changePassword == null
                || changePassword.getCurrentPassword() == null
                || changePassword.getCurrentPassword().isBlank()) {
            throw new BusinessException(ErrorCodeEnums.PASSWORD_ERROR);
        }
        if (changePassword.getNewPassword() == null || changePassword.getNewPassword().isBlank()) {
            throw new BusinessException(400, "新密码不能为空");
        }
        Optional<UserPO> userOp = userService.findById(userId);
        UserPO userPo = userOp.orElseThrow(() -> new BusinessException(ErrorCodeEnums.USER_NOT_FOUND));
        String storedPassword = userPo.getPassword();
        if (!passwordSecurityService.verify(
                changePassword.getCurrentPassword(), storedPassword).matched()) {
            throw new BusinessException(ErrorCodeEnums.PASSWORD_ERROR);
        }
        if (passwordSecurityService.verify(
                changePassword.getNewPassword(), storedPassword).matched()) {
            throw new BusinessException(ErrorCodeEnums.PASSWORD_SAME);
        }
        String replacementPassword = passwordSecurityService.encode(changePassword.getNewPassword());
        boolean updated = userService.compareAndSetPassword(userId, storedPassword, replacementPassword);
        if (updated) {
            userPo.setPassword(replacementPassword);
            // Passing the login id revokes every Sa-Token session for the account,
            // including sessions on other devices, after the password write succeeds.
            StpUtil.logout(userId);
        }
        return updated;
    }

    public void logout() {
        StpUtil.logout();
    }
}
