package system.assessment.defense.infrastructure.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.DriverManager;
import java.nio.file.Path;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Actual owned native server. No current/old schema, models or cloud resources. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class OwnedMigrationMysqlLiveTest {
    @Test void actualNativeFixtureHasSeparateIdentityAndStopsWithoutTouchingCurrentServer()throws Exception{
        var before=currentDatabaseSnapshot();
        var fixture=OwnedMigrationMysql.open();String url=fixture.serverUrl(),password=fixture.password();
        long pid=fixture.pid();
        try(fixture){
            assertThat(url).startsWith("jdbc:mysql://127.0.0.1:").contains("sslMode=REQUIRED");
            assertThat(url).doesNotContain(":13317/");
            assertThat(fixture.toString()).doesNotContain(password);
            assertThat(password.equals(System.getenv("PREPARED_DB_PASSWORD"))).isFalse();
            try(var connection=DriverManager.getConnection(url,fixture.username(),password);var statement=connection.createStatement()){
                try(var rows=statement.executeQuery("SELECT @@datadir,@@partial_revokes")){
                    assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo(fixture.data()+"/");assertThat(rows.getInt(2)).isEqualTo(1);
                }
                String schema="ai_migration_it_owned_"+UUID.randomUUID().toString().replace("-","");
                statement.execute("CREATE DATABASE `"+schema+"`");
                try{statement.execute("CREATE TABLE `"+schema+"`.proof(id INT PRIMARY KEY)");statement.execute("INSERT INTO `"+schema+"`.proof VALUES(1)");}
                finally{statement.execute("DROP DATABASE `"+schema+"`");}
            }
        }
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        fixture.close();
        assertThat(currentDatabaseSnapshot()).isEqualTo(before);
    }

    private CurrentSnapshot currentDatabaseSnapshot()throws Exception{
        var config=system.assessment.defense.prepared.PreparedLiveDatabase.checked();String url=config.url();
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:").contains("/prepared_interview?","sslMode=REQUIRED");
        try(var connection=DriverManager.getConnection(url,System.getenv("PREPARED_DB_USERNAME"),System.getenv("PREPARED_DB_PASSWORD"));var statement=connection.createStatement()){
            connection.setReadOnly(true);
            String data;
            try(var identity=statement.executeQuery("SELECT DATABASE(),@@datadir")){
                assertThat(identity.next()).isTrue();assertThat(identity.getString(1)).isEqualTo("prepared_interview");
                data=Path.of(identity.getString(2)).toRealPath().toString();
                assertThat(data).isEqualTo(config.expectedData().toRealPath().toString());
            }
            try(var rows=statement.executeQuery("SELECT (SELECT COUNT(*) FROM `user`),(SELECT COUNT(*) FROM flyway_schema_history WHERE success=1),(SELECT COUNT(*) FROM flyway_schema_history WHERE success=0)")){
                assertThat(rows.next()).isTrue();return new CurrentSnapshot(data,rows.getLong(1),rows.getLong(2),rows.getLong(3));
            }
        }
    }
    private record CurrentSnapshot(String data,long users,long successfulMigrations,long failedMigrations){}
}
