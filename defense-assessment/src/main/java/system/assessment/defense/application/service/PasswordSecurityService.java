package system.assessment.defense.application.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 密码哈希与旧明文密码的兼容迁移入口。
 */
@Service
public class PasswordSecurityService {

    private static final int BCRYPT_STRENGTH = 12;
    private static final int MIN_PASSWORD_CHARACTERS = 8;
    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;
    private static final String BCRYPT_SHA256_PREFIX = "{bcrypt-sha256}";
    private static final String DUMMY_BCRYPT_HASH =
            "$2y$12$ZQQPHxGfmAAAXu2Ugr77dOVWwOvkxSVw9KiWWuXGS2b0CWXx5xwba";
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    public String encode(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (rawPassword.length() < MIN_PASSWORD_CHARACTERS) {
            throw new IllegalArgumentException("密码至少需要8个字符");
        }
        if (!fitsBcryptInput(rawPassword)) {
            throw new IllegalArgumentException("密码长度不能超过72字节");
        }
        return passwordEncoder.encode(rawPassword);
    }

    public PasswordVerification verify(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null || storedPassword.isBlank()) {
            return PasswordVerification.rejected();
        }
        if (isBcryptSha256Hash(storedPassword)) {
            String bcryptHash = storedPassword.substring(BCRYPT_SHA256_PREFIX.length());
            return new PasswordVerification(
                    passwordEncoder.matches(sha256Base64(rawPassword), bcryptHash), null);
        }
        if (isBcryptHash(storedPassword)) {
            if (!fitsBcryptInput(rawPassword)) {
                return PasswordVerification.rejected();
            }
            return new PasswordVerification(passwordEncoder.matches(rawPassword, storedPassword), null);
        }

        boolean matched = MessageDigest.isEqual(
                rawPassword.getBytes(StandardCharsets.UTF_8),
                storedPassword.getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            return PasswordVerification.rejected();
        }
        return new PasswordVerification(true, encodeLegacyPassword(rawPassword));
    }

    private String encodeLegacyPassword(String rawPassword) {
        if (fitsBcryptInput(rawPassword)) {
            return passwordEncoder.encode(rawPassword);
        }
        return BCRYPT_SHA256_PREFIX + passwordEncoder.encode(sha256Base64(rawPassword));
    }

    private String sha256Base64(String rawPassword) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawPassword.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Performs the same expensive BCrypt comparison used for a real account so
     * that an unknown account does not have an obvious fast-failure path.
     */
    public void verifyAgainstDummyHash(String rawPassword) {
        verify(rawPassword, DUMMY_BCRYPT_HASH);
    }

    private boolean fitsBcryptInput(String rawPassword) {
        return rawPassword.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_PASSWORD_BYTES;
    }

    private boolean isBcryptHash(String value) {
        return value.matches("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
    }

    private boolean isBcryptSha256Hash(String value) {
        return value.startsWith(BCRYPT_SHA256_PREFIX)
                && isBcryptHash(value.substring(BCRYPT_SHA256_PREFIX.length()));
    }

    public record PasswordVerification(boolean matched, String upgradedHash) {
        private static PasswordVerification rejected() {
            return new PasswordVerification(false, null);
        }
    }
}
