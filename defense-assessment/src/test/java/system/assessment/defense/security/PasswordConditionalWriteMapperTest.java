package system.assessment.defense.security;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.infrastructure.repository.dao.mapper.UserMapper;
import system.assessment.defense.infrastructure.repository.dao.service.impl.UserServiceImpl;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Bound-SQL and service boundary tests; real MySQL behaviour is verified separately. */
class PasswordConditionalWriteMapperTest {
    @Test
    void passwordsRemainBoundParametersAndOnlyThePasswordColumnIsWritten() {
        var configuration = new Configuration();
        configuration.addMapper(UserMapper.class);
        var bound = configuration.getMappedStatement(UserMapper.class.getName() + ".compareAndSetPassword")
                .getBoundSql(Map.of("userId", 7, "expectedPassword", "fixture' OR '1'='1",
                        "replacementPassword", "fixture-new-value"));

        assertThat(bound.getSql()).isEqualTo("UPDATE `user` SET `password` = ? WHERE `id` = ? "
                + "AND CAST(`password` AS BINARY) = CAST(? AS BINARY)");
        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("replacementPassword", "userId", "expectedPassword");
        assertThat(bound.getSql()).doesNotContain("fixture", "name", "type", "account");
    }

    @Test
    void serviceReturnsSuccessOnlyForOneAffectedRowAndDoesNotUseWholeRowUpdate() {
        var mapper = mock(UserMapper.class);
        var service = service(mapper);
        when(mapper.compareAndSetPassword(7, "expected", "replacement")).thenReturn(1, 0, 2);
        assertThat(service.compareAndSetPassword(7, "expected", "replacement")).isTrue();
        assertThat(service.compareAndSetPassword(7, "expected", "replacement")).isFalse();
        assertThat(service.compareAndSetPassword(7, "expected", "replacement")).isFalse();
        verify(mapper, times(3)).compareAndSetPassword(7, "expected", "replacement");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void invalidIdentityAndMissingCredentialNeverReachTheDatabase() {
        var mapper = mock(UserMapper.class);
        var service = service(mapper);
        for (Integer id : new Integer[]{null, 0, -1}) {
            assertThatThrownBy(() -> service.compareAndSetPassword(id, "expected", "replacement"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String missing : new String[]{null, "", " "}) {
            assertThatThrownBy(() -> service.compareAndSetPassword(7, missing, "replacement"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.compareAndSetPassword(7, "expected", missing))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(mapper);
    }

    private UserServiceImpl service(UserMapper mapper) {
        var service = new UserServiceImpl(null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        return service;
    }
}
