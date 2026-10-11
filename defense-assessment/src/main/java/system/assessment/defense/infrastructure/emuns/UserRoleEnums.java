package system.assessment.defense.infrastructure.emuns;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@Getter
@AllArgsConstructor
public enum UserRoleEnums {
    STUDENT(0),
    TEACHER(1),
    ADMIN(2);

    private final Integer role;

    private static final Map<Integer, UserRoleEnums> ROLE_MAP = new HashMap<>();

    static {
        for (UserRoleEnums value : UserRoleEnums.values()) {
            ROLE_MAP.put(value.role, value);
        }
    }

    public static UserRoleEnums getByRole(Integer role) {
        return ROLE_MAP.get(role);
    }
}
