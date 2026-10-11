package system.assessment.defense.infrastructure.concurrency;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import system.assessment.defense.infrastructure.migration.OwnedMigrationMysql;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import system.assessment.defense.infrastructure.repository.dao.mapper.StudentAssessmentRecordMapper;
import system.assessment.defense.infrastructure.repository.dao.service.impl.StudentAssessmentRecordServiceImpl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewConcurrencyMySqlIT {

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
    void concurrentLifecycleClaimsAndCrossInstanceLocksAreExclusive() throws Exception {
        IntegrationDatabase database = createDatabase();
        try {
            Flyway.configure()
                    .dataSource(database.schemaUrl(), database.username(), database.password())
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion(MigrationVersion.fromVersion("1"))
                    .cleanDisabled(true)
                    .load()
                    .migrate();
            execute(database, "INSERT INTO `user` (`id`, `name`, `account`, `password`, `type`) " +
                    "VALUES (7, 'Student', 'student-7', 'hash', 0), " +
                    "(8, 'Teacher', 'teacher-8', 'hash', 1)");
            execute(database, "INSERT INTO `assessment` (`id`, `theme`, `create_id`, `deleted`) " +
                    "VALUES (11, 'Concurrency assessment', 8, 0)");

            SqlSessionFactory sessionFactory = buildSessionFactory(database.dataSource());
            LocalDateTime startedAt = LocalDateTime.now();

            assertThat(runConcurrently(16, () -> withService(sessionFactory,
                    service -> service.startInterview(7, 11, startedAt))))
                    .filteredOn(Boolean::booleanValue)
                    .hasSize(1);
            assertThat(rowCount(database, "student_assessment_record")).isEqualTo(1);
            assertThat(nullableTeacherScoreCount(database)).isEqualTo(1);

            assertThat(withService(sessionFactory,
                    service -> service.startQuestionPhase(
                            7, 11, startedAt.plusSeconds(1), true)))
                    .isFalse();
            execute(database, "UPDATE student_assessment_record " +
                    "SET end_defense_time = CURRENT_TIMESTAMP WHERE student_id = 7 AND assessment_id = 11");
            assertThat(runConcurrently(16, () -> withService(sessionFactory,
                    service -> service.startQuestionPhase(
                            7, 11, startedAt.plusSeconds(1), true))))
                    .filteredOn(Boolean::booleanValue)
                    .hasSize(1);

            int recordId = recordId(database);
            assertThat(runConcurrently(16, () -> withService(sessionFactory,
                    service -> service.claimInitialAnalysis(recordId, LocalDateTime.now()))))
                    .filteredOn(Boolean::booleanValue)
                    .hasSize(1);

            assertThat(withService(sessionFactory,
                    service -> service.completeAnalysis(recordId, "{}", LocalDateTime.now())))
                    .isTrue();

            assertThat(runConcurrently(16, () -> withService(sessionFactory,
                    service -> service.claimManualAnalysis(recordId, LocalDateTime.now()))))
                    .filteredOn(Boolean::booleanValue)
                    .hasSize(1);
            assertThat(withService(sessionFactory,
                    service -> service.releaseAnalysisClaim(recordId, LocalDateTime.now())))
                    .isTrue();

            verifyCrossInstanceAdvisoryLock(database.dataSource());
        } finally {
            database.drop();
        }
    }

    private void verifyCrossInstanceAdvisoryLock(DriverManagerDataSource dataSource) throws Exception {
        MySqlAdvisoryLockService instanceOne = new MySqlAdvisoryLockService(dataSource);
        MySqlAdvisoryLockService instanceTwo = new MySqlAdvisoryLockService(dataSource);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> first = executor.submit(() -> instanceOne.tryWithLock("it:shared", () -> {
                held.countDown();
                await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(instanceTwo.tryWithLock("it:shared", () -> { })).isFalse();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(instanceTwo.tryWithLock("it:shared", () -> { })).isTrue();
        }
    }

    private List<Boolean> runConcurrently(int workers, Callable<Boolean> operation) throws Exception {
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    return operation.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private boolean withService(SqlSessionFactory sessionFactory,
                                ServiceOperation operation) throws Exception {
        try (SqlSession session = sessionFactory.openSession(true)) {
            StudentAssessmentRecordMapper mapper = session.getMapper(StudentAssessmentRecordMapper.class);
            return operation.apply(new StudentAssessmentRecordServiceImpl(mapper));
        }
    }

    private SqlSessionFactory buildSessionFactory(DriverManagerDataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment(
                "mysql-concurrency-it", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(StudentAssessmentRecordMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private IntegrationDatabase createDatabase() throws SQLException {
        String serverUrl = ownedMysql == null ? System.getenv(SERVER_URL_ENV) : ownedMysql.serverUrl();
        Assumptions.assumeTrue(serverUrl != null && !serverUrl.isBlank(),
                () -> SERVER_URL_ENV + " is required for the real MySQL concurrency test");
        String name = "ai_concurrency_it_" +
                UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        IntegrationDatabase database = new IntegrationDatabase(
                serverUrl,
                ownedMysql == null ? System.getenv().getOrDefault(USER_ENV, "root") : ownedMysql.username(),
                ownedMysql == null ? System.getenv().getOrDefault(PASSWORD_ENV, "") : ownedMysql.password(),
                name
        );
        try (Connection connection = DriverManager.getConnection(serverUrl, database.username(), database.password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + name +
                    "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        return database;
    }

    private int recordId(IntegrationDatabase database) throws SQLException {
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT id FROM student_assessment_record WHERE assessment_id=11 AND student_id=7")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int rowCount(IntegrationDatabase database, String table) throws SQLException {
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
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

    private int nullableTeacherScoreCount(IntegrationDatabase database) throws SQLException {
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM student_assessment_record WHERE check_score IS NULL")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrency barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrency barrier", exception);
        }
    }

    @FunctionalInterface
    private interface ServiceOperation {
        boolean apply(StudentAssessmentRecordServiceImpl service) throws Exception;
    }

    private record IntegrationDatabase(String serverUrl, String username, String password, String name) {

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
