package system.assessment.defense.application.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import system.assessment.defense.infrastructure.emuns.UserRoleEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Server-only credential binding; never trusts a client marker or upgrades an old token. */
public final class CredentialSessionGuard {
    private static final String BINDING_KEY = "prepared.verifiedCredential.v1";
    private static final String BINDING_DOMAIN = "prepared-credential-session-v1\0";

    private CredentialSessionGuard() {}

    public static void issueVerifiedSession(UserPO verified, UserService users) {
        // Capture BEFORE issuing the token. A later database read must never
        // bless an old verification by binding it to a replacement credential.
        String verifiedBinding = binding(verified);
        StpUtil.login(verified.getId());
        try {
            StpUtil.getTokenSession().set(BINDING_KEY, verifiedBinding);
            requireCurrentCredential(users);
        } catch (RuntimeException failure) {
            revokeCurrentSession(failure);
            throw failure;
        }
    }

    /** Called after Sa-Token checkLogin, before every protected HTTP handler. */
    public static void requireCurrentCredential(UserService users) {
        int userId = StpUtil.getLoginIdAsInt();
        // No credential cache: a committed password change/deletion must be
        // visible even if session revocation fails or Redis restores old data.
        UserPO current = users.findById(userId).orElse(null);
        SaSession session = StpUtil.getTokenSession();
        Object value = session == null ? null : session.get(BINDING_KEY);
        if (current == null || current.getId() == null || current.getId() != userId
                || current.getPassword() == null || current.getPassword().isBlank()
                || UserRoleEnums.getByRole(current.getType()) == null
                || !(value instanceof String marker) || marker.length() != 64
                || !MessageDigest.isEqual(marker.getBytes(StandardCharsets.US_ASCII),
                    binding(current).getBytes(StandardCharsets.US_ASCII))) {
            BusinessException failure = new BusinessException(401, "登录状态已失效，请重新登录");
            revokeCurrentSession(failure);
            throw failure;
        }
    }

    private static String binding(UserPO user) {
        if (user == null || user.getId() == null || user.getPassword() == null
                || user.getPassword().isBlank()) {
            throw new BusinessException(401, "登录状态已失效，请重新登录");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(BINDING_DOMAIN.getBytes(StandardCharsets.UTF_8));
            digest.update(Integer.toString(user.getId()).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(user.getPassword().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void revokeCurrentSession(RuntimeException failure) {
        try {
            // Never logout(id) here: an unsuccessful stale request must not
            // revoke a different current token as part of its compensation.
            StpUtil.logout();
        } catch (RuntimeException revocationFailure) {
            failure.addSuppressed(revocationFailure);
        }
    }
}
