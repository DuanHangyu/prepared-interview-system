package system.assessment.defense.security;

import cn.dev33.satoken.session.SaSession;

/** Test-only token attributes for unit tests that already double StpUtil.
 * Keeps the SDK's real map/set/get behavior, without inheriting a Spring
 * context's persistence component or changing any global DAO. Live session
 * tests and persistence-failure tests must continue to use real SaSession. */
final class UnitTokenSession extends SaSession {
    UnitTokenSession(String id) {
        super();
        setId(id);
    }

    @Override
    public void update() {
        // Unit fixture only: external persistence is outside this test boundary.
    }
}
