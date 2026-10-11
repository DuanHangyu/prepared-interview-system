package system.assessment.defense.infrastructure.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class AdvisoryLockDataSourceConfiguration {

    /**
     * Named locks must keep their JDBC session open while protected work runs.
     * A small, non-pooled data source prevents those sessions from consuming all
     * application Hikari connections while AI scoring is in flight.
     */
    @Bean(name = "advisoryLockDataSource", defaultCandidate = false)
    public DataSource advisoryLockDataSource(DataSourceProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(properties.determineDriverClassName());
        dataSource.setUrl(properties.determineUrl());
        dataSource.setUsername(properties.determineUsername());
        dataSource.setPassword(properties.determinePassword());
        return dataSource;
    }
}
