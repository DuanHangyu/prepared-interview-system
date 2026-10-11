package system.assessment.defense.prepared;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.infrastructure.repository.dao.mapper.UserMapper;
import system.assessment.defense.infrastructure.repository.dao.service.impl.UserServiceImpl;

import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Actual MySQL and mapper/service writes. No HTTP, Redis/session or whole-interview acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreparedPasswordConditionalWriteLiveTest {
    private JdbcTemplate jdbc;
    private SqlSessionFactory sessions;
    private int id;
    private String account;

    @BeforeAll
    void initializeVerifiedDisposableDatabaseUsingTheActualMigrations() {
        assertThat(System.getenv("PREPARED_VALIDATION_DATABASE_DESCRIPTOR")).isNotBlank();
        var owned = PreparedLiveDatabase.checked();
        assertThat(owned.pid()).isPositive();
        var source = new DriverManagerDataSource(owned.url(), owned.username(), owned.password());
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .baselineOnMigrate(false).validateOnMigrate(true).load().migrate();
        var configuration = new Configuration(new Environment("owned-password-cas",
                new JdbcTransactionFactory(), source));
        configuration.setDefaultStatementTimeout(5);
        configuration.addMapper(UserMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void createOnlyOwnedAccountInVerifiedDisposableDatabase() {
        id = 0;
        account = "prepared-password-cas-" + UUID.randomUUID();
        jdbc.update("INSERT INTO `user`(name,account,password,type,phone,school_class) VALUES (?,?,?,0,?,?)",
                "original-profile", account, "CaseCredential", "00000000000", "fixture-class");
        id = jdbc.queryForObject("SELECT id FROM `user` WHERE account=?", Integer.class, account);
    }

    @AfterEach
    void removeOnlyThisAccount() {
        if (jdbc != null && id > 0) {
            assertThat(jdbc.update("DELETE FROM `user` WHERE id=? AND account=?", id, account)).isEqualTo(1);
        }
    }

    private boolean replace(int userId, String expected, String replacement) {
        try (var session = sessions.openSession(true)) {
            var service = new UserServiceImpl(null, null, null, null, null, null);
            ReflectionTestUtils.setField(service, "baseMapper", session.getMapper(UserMapper.class));
            return service.compareAndSetPassword(userId, expected, replacement);
        }
    }

    private String password() {
        return jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, id);
    }

    @RepeatedTest(3)
    void byteExactConditionRejectsCaseAndTrailingSpaceAndPreservesChangedProfile() {
        jdbc.update("UPDATE `user` SET name='concurrent-profile',type=1 WHERE id=?", id);
        assertThat(replace(id, "casecredential", "wrong-case-value")).isFalse();
        assertThat(replace(id, "CaseCredential ", "wrong-space-value")).isFalse();
        assertThat(replace(id, "CaseCredential", "accepted-value")).isTrue();
        assertThat(replace(id, "CaseCredential", "stale-value")).isFalse();
        assertThat(replace(Integer.MAX_VALUE, "accepted-value", "foreign-value")).isFalse();
        assertThat(password()).isEqualTo("accepted-value");
        assertThat(jdbc.queryForMap("SELECT name,account,type,phone,school_class FROM `user` WHERE id=?", id))
                .containsEntry("name", "concurrent-profile").containsEntry("account", account)
                .containsEntry("type", 1).containsEntry("phone", "00000000000")
                .containsEntry("school_class", "fixture-class");
    }

    @RepeatedTest(3)
    void twoActualConcurrentWritersHaveExactlyOneWinnerAndOldCredentialCannotReturn() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> { ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return replace(id, "CaseCredential", "first-value"); });
            var second = pool.submit(() -> { ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return replace(id, "CaseCredential", "second-value"); });
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                boolean wonFirst = first.get(10, TimeUnit.SECONDS);
                boolean wonSecond = second.get(10, TimeUnit.SECONDS);
                assertThat(wonFirst ^ wonSecond).isTrue();
                assertThat(password()).isEqualTo(wonFirst ? "first-value" : "second-value");
                assertThat(replace(id, "CaseCredential", "restored-old-value")).isFalse();
                assertThat(password()).isEqualTo(wonFirst ? "first-value" : "second-value");
                assertThat(jdbc.queryForObject("SELECT name FROM `user` WHERE id=?", String.class, id))
                        .isEqualTo("original-profile");
            } finally { start.countDown(); }
        }
    }

    @RepeatedTest(3)
    void sqlMetacharactersRemainLiteralAndCannotBypassTheIdentityOrExpectedValue() {
        String literal = "fixture' OR '1'='1";
        assertThat(replace(id, literal, "unauthorized-value")).isFalse();
        assertThat(password()).isEqualTo("CaseCredential");
        assertThat(replace(id, "CaseCredential", literal)).isTrue();
        assertThat(password()).isEqualTo(literal);
        assertThat(replace(id, literal, "final-value")).isTrue();
        assertThat(password()).isEqualTo("final-value");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE account=?", Integer.class, account))
                .isEqualTo(1);
    }
}
