package system.assessment.defense.infrastructure.config;

import cn.dev33.satoken.stp.StpInterface;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import system.assessment.defense.infrastructure.emuns.UserRoleEnums;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.util.Collections;
import java.util.List;

/**
 * 从服务端用户记录解析 Sa-Token 角色，不信任任何前端传入的角色字段。
 */
@Component
@RequiredArgsConstructor
public class SaTokenRoleProvider implements StpInterface {

    private final UserService userService;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Integer userId;
        try {
            userId = Integer.valueOf(String.valueOf(loginId));
        } catch (NumberFormatException exception) {
            return Collections.emptyList();
        }

        return userService.findById(userId)
                .map(user -> UserRoleEnums.getByRole(user.getType()))
                .map(role -> List.of(role.name()))
                .orElseGet(Collections::emptyList);
    }
}
