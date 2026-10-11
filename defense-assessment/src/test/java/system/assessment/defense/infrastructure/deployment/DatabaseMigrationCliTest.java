package system.assessment.defense.infrastructure.deployment;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMigrationCliTest {

    @Test
    void requiresExplicitDatabaseConnectionSettings() {
        assertThatThrownBy(() -> DatabaseMigrationCli.settings(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_JDBC_URL");
    }

    @Test
    void readsMigrationConnectionWithoutExposingThePassword() {
        DatabaseMigrationCli.Settings settings = DatabaseMigrationCli.settings(Map.of(
                "DB_JDBC_URL", "jdbc:mysql://mysql:3306/defense",
                "DB_USERNAME", "app",
                "DB_PASSWORD", "secret"));

        assertThat(settings.url()).isEqualTo("jdbc:mysql://mysql:3306/defense");
        assertThat(settings.username()).isEqualTo("app");
        assertThat(settings.password()).isEqualTo("secret");
        assertThat(settings.toString()).doesNotContain("secret");
    }

    @Test
    void supportsExplicitMigrateAndValidateOperations() {
        assertThat(DatabaseMigrationCli.operation(new String[]{"migrate"}))
                .isEqualTo(DatabaseMigrationCli.Operation.MIGRATE);
        assertThat(DatabaseMigrationCli.operation(new String[]{"validate"}))
                .isEqualTo(DatabaseMigrationCli.Operation.VALIDATE);
        assertThat(DatabaseMigrationCli.operation(new String[]{}))
                .isEqualTo(DatabaseMigrationCli.Operation.MIGRATE);
    }

    @Test
    void rejectsUnknownOperations() {
        assertThatThrownBy(() -> DatabaseMigrationCli.operation(new String[]{"repair"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("migrate or validate");
    }
}
