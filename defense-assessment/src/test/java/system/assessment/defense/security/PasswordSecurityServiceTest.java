package system.assessment.defense.security;

import org.junit.jupiter.api.Test;
import system.assessment.defense.application.service.PasswordSecurityService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordSecurityServiceTest {

    @Test
    void rejectsPasswordsBeyondBcryptInputBoundary() {
        PasswordSecurityService service = new PasswordSecurityService();

        assertThatThrownBy(() -> service.encode("密".repeat(25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("72字节");
    }

    @Test
    void rejectsWeakNewPasswordsAtEveryWritePath() {
        assertThatThrownBy(() -> passwordSecurityService.encode("short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要8个字符");
    }

    @Test
    void oversizedCandidateCannotMatchBcryptCredential() {
        String encoded = passwordSecurityService.encode("valid-password");

        assertThat(passwordSecurityService.verify("密".repeat(25), encoded).matched()).isFalse();
    }

    private final PasswordSecurityService passwordSecurityService = new PasswordSecurityService();

    @Test
    void newlyStoredPasswordsUseBcryptAndCanBeVerified() {
        String encoded = passwordSecurityService.encode("correct horse battery staple");

        assertThat(encoded).startsWith("$2");
        assertThat(encoded).isNotEqualTo("correct horse battery staple");
        assertThat(passwordSecurityService.verify("correct horse battery staple", encoded).matched()).isTrue();
        assertThat(passwordSecurityService.verify("wrong", encoded).matched()).isFalse();
    }

    @Test
    void matchingLegacyPlaintextPasswordIsUpgradedOnce() {
        PasswordSecurityService.PasswordVerification verification =
                passwordSecurityService.verify("legacy-password", "legacy-password");

        assertThat(verification.matched()).isTrue();
        assertThat(verification.upgradedHash()).startsWith("$2");
        assertThat(passwordSecurityService.verify("legacy-password", verification.upgradedHash()).matched()).isTrue();
    }

    @Test
    void wrongLegacyPasswordIsRejectedWithoutUpgrade() {
        PasswordSecurityService.PasswordVerification verification =
                passwordSecurityService.verify("wrong", "legacy-password");

        assertThat(verification.matched()).isFalse();
        assertThat(verification.upgradedHash()).isNull();
    }

    @Test
    void oversizedLegacyPasswordMigratesWithoutUnsafeBcryptTruncation() {
        String legacyPassword = "密".repeat(25);

        PasswordSecurityService.PasswordVerification verification =
                passwordSecurityService.verify(legacyPassword, legacyPassword);

        assertThat(verification.matched()).isTrue();
        assertThat(verification.upgradedHash()).startsWith("{bcrypt-sha256}$2");
        assertThat(passwordSecurityService.verify(legacyPassword, verification.upgradedHash()).matched()).isTrue();
        assertThat(passwordSecurityService.verify(legacyPassword + "x", verification.upgradedHash()).matched()).isFalse();
    }

    @Test
    void shortLegacyPasswordMigratesDespiteCurrentNewPasswordPolicy() {
        PasswordSecurityService.PasswordVerification verification =
                passwordSecurityService.verify("legacy", "legacy");

        assertThat(verification.matched()).isTrue();
        assertThat(verification.upgradedHash()).startsWith("$2");
        assertThat(passwordSecurityService.verify("legacy", verification.upgradedHash()).matched()).isTrue();
    }
}
