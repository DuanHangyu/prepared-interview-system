package system.assessment.defense.infrastructure.concurrency;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import system.assessment.defense.infrastructure.migration.OwnedMigrationMysql;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import system.assessment.defense.application.service.StudentInterviewLifecycleTransitionService;
import system.assessment.defense.infrastructure.repository.dao.mapper.StudentAssessmentAppointmentMapper;
import system.assessment.defense.infrastructure.repository.dao.mapper.StudentAssessmentRecordMapper;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentAppointmentService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.impl.StudentAssessmentAppointmentServiceImpl;
import system.assessment.defense.infrastructure.repository.dao.service.impl.StudentAssessmentRecordServiceImpl;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterviewLifecycleTransactionMySqlIT {

    private static OwnedMigrationMysql ownedMysql;

    @BeforeAll
    static void openOwnedMysql() throws Exception {
        ownedMysql = OwnedMigrationMysql.openIfEnabled();
    }

    @AfterAll
    static void closeOwnedMysql() throws Exception {
        if (ownedMysql != null) ownedMysql.close();
    }

    private static final String SERVER_URL_ENV = "MYSQL_INTEGRATION_SERVER_URL";
    private static final String USER_ENV = "MYSQL_INTEGRATION_USERNAME";
    private static final String PASSWORD_ENV = "MYSQL_INTEGRATION_PASSWORD";

    @Test
    void appointmentAndInterviewRecordCommitOrRollbackTogether() throws Exception {
        IntegrationDatabase database = createDatabase();
        try {
            migrate(database);
            seed(database);
            LifecycleTestConfiguration.dataSource = database.dataSource();

            try (AnnotationConfigApplicationContext context =
                         new AnnotationConfigApplicationContext(LifecycleTestConfiguration.class)) {
                StudentInterviewLifecycleTransitionService transitions =
                        context.getBean(StudentInterviewLifecycleTransitionService.class);
                assertThat(AopUtils.isAopProxy(transitions)).isTrue();

                assertThat(transitions.startDefense(
                        7, 11, LocalDateTime.now(), true)).isTrue();
                assertThat(recordCount(database, 7)).isOne();
                assertThat(appointmentState(database, 7)).isEqualTo(1);

                assertThatThrownBy(() -> transitions.startDefense(
                        9, 11, LocalDateTime.now(), true))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("预约状态");
                assertThat(recordCount(database, 9)).isZero();
                assertThat(appointmentState(database, 9)).isEqualTo(3);
            } finally {
                LifecycleTestConfiguration.dataSource = null;
            }
        } finally {
            database.drop();
        }
    }

    private void migrate(IntegrationDatabase database) {
        Flyway.configure()
                .dataSource(database.schemaUrl(), database.username(), database.password())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    private void seed(IntegrationDatabase database) throws SQLException {
        execute(database, "INSERT INTO `user` (`id`, `name`, `account`, `password`, `type`) " +
                "VALUES (7, 'Student 7', 'student-7', 'hash', 0), " +
                "(9, 'Student 9', 'student-9', 'hash', 0), " +
                "(8, 'Teacher', 'teacher-8', 'hash', 1)");
        execute(database, "INSERT INTO `assessment` (`id`, `theme`, `create_id`, `deleted`) " +
                "VALUES (11, 'Lifecycle transaction', 8, 0)");
        execute(database, "INSERT INTO `student_assessment_appointment` " +
                "(`student_id`, `assessment_id`, `appointment_time`, `time_period`, `state`, `punish_state`) " +
                "VALUES (7, 11, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, -1), " +
                "(9, 11, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 3, 0)");
    }

    private int recordCount(IntegrationDatabase database, int studentId) throws SQLException {
        return integerQuery(database,
                "SELECT COUNT(*) FROM student_assessment_record " +
                        "WHERE assessment_id=11 AND student_id=" + studentId);
    }

    private int appointmentState(IntegrationDatabase database, int studentId) throws SQLException {
        return integerQuery(database,
                "SELECT state FROM student_assessment_appointment " +
                        "WHERE assessment_id=11 AND student_id=" + studentId);
    }

    private int integerQuery(IntegrationDatabase database, String sql) throws SQLException {
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void execute(IntegrationDatabase database, String sql) throws SQLException {
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private IntegrationDatabase createDatabase() throws SQLException {
        String serverUrl = ownedMysql == null ? System.getenv(SERVER_URL_ENV) : ownedMysql.serverUrl();
        Assumptions.assumeTrue(serverUrl != null && !serverUrl.isBlank(),
                () -> SERVER_URL_ENV + " is required for the real MySQL lifecycle transaction test");
        String name = "ai_lifecycle_it_" +
                UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        IntegrationDatabase database = new IntegrationDatabase(
                serverUrl,
                ownedMysql == null ? System.getenv().getOrDefault(USER_ENV, "root") : ownedMysql.username(),
                ownedMysql == null ? System.getenv().getOrDefault(PASSWORD_ENV, "") : ownedMysql.password(),
                name);
        try (Connection connection = DriverManager.getConnection(
                serverUrl, database.username(), database.password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + name +
                    "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        return database;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class LifecycleTestConfiguration {

        private static DriverManagerDataSource dataSource;

        @Bean
        DataSource dataSource() {
            if (dataSource == null) {
                throw new IllegalStateException("Integration test data source was not initialized");
            }
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setEnvironment(new Environment(
                    "mysql-lifecycle-transaction-it",
                    new SpringManagedTransactionFactory(),
                    dataSource));
            configuration.addMapper(StudentAssessmentRecordMapper.class);
            configuration.addMapper(StudentAssessmentAppointmentMapper.class);
            return new MybatisSqlSessionFactoryBuilder().build(configuration);
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        StudentAssessmentRecordMapper recordMapper(SqlSessionTemplate template) {
            return template.getMapper(StudentAssessmentRecordMapper.class);
        }

        @Bean
        StudentAssessmentAppointmentMapper appointmentMapper(SqlSessionTemplate template) {
            return template.getMapper(StudentAssessmentAppointmentMapper.class);
        }

        @Bean
        StudentAssessmentRecordService recordService(StudentAssessmentRecordMapper mapper) {
            StudentAssessmentRecordServiceImpl service =
                    new StudentAssessmentRecordServiceImpl(mapper);
            ReflectionTestUtils.setField(service, "baseMapper", mapper);
            return service;
        }

        @Bean
        StudentAssessmentAppointmentService appointmentService(
                StudentAssessmentAppointmentMapper mapper) {
            StudentAssessmentAppointmentServiceImpl service =
                    new StudentAssessmentAppointmentServiceImpl();
            ReflectionTestUtils.setField(service, "baseMapper", mapper);
            return service;
        }

        @Bean
        StudentInterviewLifecycleTransitionService transitionService(
                StudentAssessmentRecordService recordService,
                StudentAssessmentAppointmentService appointmentService) {
            return new StudentInterviewLifecycleTransitionService(
                    recordService, appointmentService);
        }
    }

    private record IntegrationDatabase(String serverUrl,
                                       String username,
                                       String password,
                                       String name) {

        String schemaUrl() {
            int queryStart = serverUrl.indexOf('?');
            String base = queryStart >= 0 ? serverUrl.substring(0, queryStart) : serverUrl;
            String query = queryStart >= 0 ? serverUrl.substring(queryStart) : "";
            String separator = base.endsWith("/") ? "" : "/";
            return base + separator + name + query;
        }

        DriverManagerDataSource dataSource() {
            return new DriverManagerDataSource(schemaUrl(), username, password);
        }

        Connection connect() throws SQLException {
            return DriverManager.getConnection(schemaUrl(), username, password);
        }

        void drop() throws SQLException {
            try (Connection connection = DriverManager.getConnection(serverUrl, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + name + "`");
            }
        }
    }
}
