package system.assessment.defense.infrastructure.deployment;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;

import java.util.Map;

public final class DatabaseMigrationCli {

    private DatabaseMigrationCli() {
    }

    public static void main(String[] args) {
        Settings settings = settings(System.getenv());
        Flyway flyway = Flyway.configure()
                .dataSource(settings.url(), settings.username(), settings.password())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load();

        switch (operation(args)) {
            case MIGRATE -> migrate(flyway);
            case VALIDATE -> validate(flyway);
        }
    }

    private static void migrate(Flyway flyway) {
        MigrateResult result = flyway.migrate();
        System.out.printf("Database migration complete: executed=%d, version=%s%n",
                result.migrationsExecuted, result.targetSchemaVersion);
    }

    private static void validate(Flyway flyway) {
        ValidateResult result = flyway.validateWithResult();
        if (!result.validationSuccessful) {
            throw new IllegalStateException("Database migration validation failed");
        }
        System.out.printf("Database migration validation complete: migrations=%d%n", result.validateCount);
    }

    static Operation operation(String[] args) {
        if (args.length == 0 || "migrate".equalsIgnoreCase(args[0])) {
            return Operation.MIGRATE;
        }
        if ("validate".equalsIgnoreCase(args[0])) {
            return Operation.VALIDATE;
        }
        throw new IllegalArgumentException("Expected database operation: migrate or validate");
    }

    static Settings settings(Map<String, String> environment) {
        String url = required(environment, "DB_JDBC_URL");
        String username = required(environment, "DB_USERNAME");
        String password = environment.getOrDefault("DB_PASSWORD", "");
        return new Settings(url, username, password);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for database migration");
        }
        return value;
    }

    record Settings(String url, String username, String password) {
        @Override
        public String toString() {
            return "Settings[url=" + url + ", username=" + username + ", password=***]";
        }
    }

    enum Operation {
        MIGRATE,
        VALIDATE
    }
}
