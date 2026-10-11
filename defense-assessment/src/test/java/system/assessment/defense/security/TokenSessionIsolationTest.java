package system.assessment.defense.security;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;
import cn.dev33.satoken.json.SaJsonTemplateForJackson;
import system.assessment.defense.application.service.CredentialSessionGuard;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Reproduce a closed Spring context's DAO in the isolated test JVM only.
 * No Redis connection, production change or weakened authentication assertion. */
@Isolated("Temporarily installs a destroyed test DAO; restores both exact original components")
class TokenSessionIsolationTest {
    private static final String DESTROYED = "LettuceConnectionFactory was destroyed and cannot be used anymore";

    private static final class DestroyedDao implements AutoCloseable {
        private final Object originalDao;
        private final Object originalJson;

        private DestroyedDao() {
            var connections = new LettuceConnectionFactory();
            var dao = new SaTokenDaoForRedisTemplate();
            dao.init(connections);
            connections.destroy();
            originalDao = ReflectionTestUtils.getField(SaManager.class, "saTokenDao");
            originalJson = ReflectionTestUtils.getField(SaManager.class, "saJsonTemplate");
            // The public DAO setter destroys the previous component. Preserve it,
            // including a null/uninitialized value, without invoking its lifecycle.
            ReflectionTestUtils.setField(SaManager.class, "saJsonTemplate", new SaJsonTemplateForJackson());
            ReflectionTestUtils.setField(SaManager.class, "saTokenDao", dao);
        }

        @Override public void close() {
            ReflectionTestUtils.setField(SaManager.class, "saTokenDao", originalDao);
            ReflectionTestUtils.setField(SaManager.class, "saJsonTemplate", originalJson);
        }
    }

    @Test
    void aRealSdkSessionReproducesTheFullRunFailureWithoutConnectingToRedis() {
        try (var ignored = new DestroyedDao()) {
            assertThatThrownBy(() -> new SaSession("fixture-original-sdk").set("binding", "value"))
                    .isInstanceOf(IllegalStateException.class).hasMessage(DESTROYED);
        }
    }

    @RepeatedTest(3)
    void credentialGuardUnitFixtureIsIndependentOfThePreviousSpringContext() {
        try (var ignored = new DestroyedDao()) {
            // Execute the unchanged original normal-login and HTTP-guard assertions.
            new CredentialSessionGuardTest().currentCredentialStillPassesTheConfiguredGuard();
        }
    }

    @RepeatedTest(3)
    void hashedLoginUnitFixtureIsIndependentOfThePreviousSpringContext() {
        try (var ignored = new DestroyedDao()) {
            new HashedLoginRevocationRaceTest().anUnchangedCurrentHashedCredentialCanStillLogIn();
        }
    }

    @RepeatedTest(3)
    void legacyUpgradeUnitFixtureIsIndependentOfThePreviousSpringContext() {
        try (var ignored = new DestroyedDao()) {
            var original = new LoginServiceSecurityTest();
            original.setUp();
            original.successfulLegacyLoginMigratesPlaintextPassword();
        }
    }

    @RepeatedTest(3)
    void productionSessionPersistenceFailureStillRejectsLoginAndRevokesOnlyItsToken() {
        var users = mock(UserService.class);
        var verified = UserPO.builder().id(7).password("fixture-verified-stored-value").type(0).build();
        var token = new SaSession("fixture-failing-real-session");
        try (var ignored = new DestroyedDao(); var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getTokenSession).thenReturn(token);
            assertThatThrownBy(() -> CredentialSessionGuard.issueVerifiedSession(verified, users))
                    .isInstanceOf(IllegalStateException.class).hasMessage(DESTROYED);
            stp.verify(() -> StpUtil.login(7));
            stp.verify(StpUtil::logout);
            stp.verify(() -> StpUtil.logout(7), never());
            verifyNoInteractions(users);
        }
    }

    @Test
    void revocationFailureDoesNotTurnAFailedSessionWriteIntoASuccessfulLogin() {
        var users = mock(UserService.class);
        var verified = UserPO.builder().id(7).password("fixture-verified-stored-value").type(0).build();
        var token = new SaSession("fixture-failing-real-session");
        var revocationFailure = new IllegalStateException("fixture-revocation-unavailable");
        try (var ignored = new DestroyedDao(); var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getTokenSession).thenReturn(token);
            stp.when(StpUtil::logout).thenThrow(revocationFailure);
            assertThatThrownBy(() -> CredentialSessionGuard.issueVerifiedSession(verified, users))
                    .isInstanceOf(IllegalStateException.class).hasMessage(DESTROYED)
                    .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(revocationFailure));
            stp.verify(StpUtil::logout);
            stp.verify(() -> StpUtil.logout(7), never());
            verifyNoInteractions(users);
        }
    }
}
