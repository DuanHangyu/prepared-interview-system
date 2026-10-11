package system.assessment.defense.infrastructure.migration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

/** Real disposable native servers, not mock storage or model integration. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class OwnedPreparedValidationMysqlLiveTest {
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void freshPreparedServerUsesSeparateLeastPrivilegeIdentityAndStopsOnlyItsOwnProcess(int round)throws Exception {
        var fixture=OwnedMigrationMysql.openForPreparedValidation();long pid=fixture.pid();
        try(fixture) {
            assertThat(fixture.applicationUrl()).contains("/prepared_interview?sslMode=REQUIRED").doesNotContain(":13317/");
            assertThat(fixture.applicationPassword()).isNotEqualTo(fixture.password()).isNotEqualTo(System.getenv("PREPARED_DB_PASSWORD"));
            assertThat(fixture.applicationUsername()).isEqualTo("prepared_migrator");
            assertThat(fixture.toString()).doesNotContain(fixture.applicationPassword());
            fixture.checkApplicationIdentity();
            try(var connection=DriverManager.getConnection(fixture.applicationUrl(),fixture.applicationUsername(),fixture.applicationPassword());var statement=connection.createStatement()) {
                try(var rows=statement.executeQuery("SELECT DATABASE(),@@datadir")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("prepared_interview");
                    assertThat(rows.getString(2)).isEqualTo(fixture.data()+"/");
                }
                statement.execute("CREATE TABLE ownership_probe(id INT PRIMARY KEY)");
                statement.execute("INSERT INTO ownership_probe VALUES(1)");
                assertThatThrownBy(()->statement.execute("CREATE DATABASE forbidden_probe")).isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(()->statement.executeQuery("SELECT * FROM mysql.user")).isInstanceOf(java.sql.SQLException.class);
                statement.execute("DROP TABLE ownership_probe");
            }
        }
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(Files.isDirectory(fixture.data())).isTrue(); // retained for diagnosis, never recursive removal
        fixture.close();
    }
}
