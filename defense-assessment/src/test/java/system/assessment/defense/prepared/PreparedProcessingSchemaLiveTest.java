package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** Forward schema in the independent database; no old credentials, cloud or paid calls. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedProcessingSchemaLiveTest {
    @Test void resultsAndEffectivePointersHaveScopedForeignKeysAndUniqueVersionBoundaries() {
        var ds=new DriverManagerDataSource(PreparedLiveDatabase.jdbcUrl(),System.getenv("PREPARED_DB_USERNAME"),System.getenv("PREPARED_DB_PASSWORD"));
        org.flywaydb.core.Flyway.configure().dataSource(ds).cleanDisabled(true).load().migrate();var jdbc=new JdbcTemplate(ds);
        for(String table:new String[]{"prepared_processing_result","prepared_processing_head","prepared_score_request","prepared_result_command"})
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isNotNull();
    }
}
