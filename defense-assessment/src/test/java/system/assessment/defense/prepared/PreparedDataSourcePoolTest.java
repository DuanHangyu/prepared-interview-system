package system.assessment.defense.prepared;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import system.assessment.defense.infrastructure.config.AdvisoryLockDataSourceConfiguration;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class PreparedDataSourcePoolTest {
    @Test void auxiliaryLockConnectionsDoNotDisableOrReplaceTheApplicationPool() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withUserConfiguration(AdvisoryLockDataSourceConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://127.0.0.1:13317/prepared_interview",
                        "spring.datasource.username=unused-unit-test",
                        "spring.datasource.password=unused-unit-test")
                .run(context -> {
                    assertThat(context).hasSingleBean(HikariDataSource.class);
                    assertThat(context.getBean(DataSource.class)).isInstanceOf(HikariDataSource.class);
                    assertThat(context.getBean("advisoryLockDataSource")).isInstanceOf(DriverManagerDataSource.class);
                });
    }
}
