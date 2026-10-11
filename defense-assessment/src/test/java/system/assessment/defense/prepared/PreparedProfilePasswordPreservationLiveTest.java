package system.assessment.defense.prepared;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.application.dto.StudentModifyCmd;
import system.assessment.defense.application.dto.TeacherModifyCmd;
import system.assessment.defense.application.service.*;
import system.assessment.defense.infrastructure.common.HttpUtils;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.mapper.UserMapper;
import system.assessment.defense.infrastructure.repository.dao.po.UserPO;
import system.assessment.defense.infrastructure.repository.dao.service.*;
import system.assessment.defense.infrastructure.repository.dao.service.impl.UserServiceImpl;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real profile services and MySQL/MyBatis-Plus. Auxiliary services and encoder are labelled doubles.
 * No controller, caller-role, Spring transaction, real password reset/session or full-flow acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreparedProfilePasswordPreservationLiveTest {
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private int id;
    private String account;

    @BeforeAll void initializeVerifiedDisposableDatabase() {
        assertThat(System.getenv("PREPARED_VALIDATION_DATABASE_DESCRIPTOR")).isNotBlank();
        var owned = PreparedLiveDatabase.checked();
        assertThat(owned.pid()).isPositive();
        source = new DriverManagerDataSource(owned.url(), owned.username(), owned.password());
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .baselineOnMigrate(false).validateOnMigrate(true).load().migrate();
    }

    @BeforeEach void resetFixtureIdentity() { id = 0; }

    @AfterEach void removeOnlyOwnedAccount() {
        if (id > 0) assertThat(jdbc.update("DELETE FROM `user` WHERE id=? AND account=?", id, account)).isEqualTo(1);
    }

    private SqlSessionFactory factory(FieldStrategy strategy) throws Exception {
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setDefaultStatementTimeout(5);
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setTransactionFactory(new JdbcTransactionFactory());
        bean.setConfiguration(config);
        bean.setGlobalConfig(new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig().setUpdateStrategy(strategy))
                .setSqlInjector(new DefaultSqlInjector()));
        var sessions = bean.getObject();
        assertThat(sessions).isNotNull();
        sessions.getConfiguration().addMapper(UserMapper.class);
        return sessions;
    }

    private void createAccount(int role) {
        account = "prepared-profile-password-" + UUID.randomUUID();
        jdbc.update("INSERT INTO `user`(name,account,password,type,phone,school_class) VALUES (?,?,?,?,?,?)",
                "fixture-original-profile", account, "fixture-original-credential", role, "00000000000", "Fixture Class");
        id = jdbc.queryForObject("SELECT id FROM `user` WHERE account=?", Integer.class, account);
    }

    private UserServiceImpl users(UserMapper mapper) {
        var service = spy(new UserServiceImpl(null, null, null, null, null, null));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        return service;
    }

    private void concurrentCredentialWriteOnAnotherConnection() throws Exception {
        try (var connection = source.getConnection();
             var command = connection.prepareStatement("UPDATE `user` SET password=? WHERE id=?")) {
            assertThat(connection.getAutoCommit()).isTrue();
            command.setQueryTimeout(5);
            command.setString(1, "fixture-current-credential");
            command.setInt(2, id);
            assertThat(command.executeUpdate()).isEqualTo(1);
        }
    }

    private boolean modify(int role, UserService users, PasswordSecurityService passwords, String password) {
        if (role == 0) {
            var command = new StudentModifyCmd();
            command.setId(id); command.setName("fixture-updated-profile"); command.setAccount(account);
            command.setSchoolClass("Fixture Class"); command.setPassword(password);
            return new StudentAppService(users, mock(StudentAssessmentAppointmentService.class),
                    mock(AssessmentStudentRelationService.class), mock(AssessmentSchoolClassService.class),
                    mock(StudentAssessmentRecordService.class), mock(HttpUtils.class),
                    mock(StudentAssessmentQuestionAnswerService.class), mock(ApplicationEventPublisher.class),
                    passwords, mock(ManagedObjectCleanupService.class)).modifyStudent(command);
        }
        var command = new TeacherModifyCmd();
        command.setId(id); command.setName("fixture-updated-profile"); command.setAccount(account);
        command.setPhone("00000000000"); command.setPassword(password);
        return new TeacherAppService(users, passwords, mock(AssessmentSettingService.class),
                mock(AssessmentInviteService.class)).modifyStudent(command);
    }

    static Stream<Arguments> omittedCases() {
        return Stream.of(FieldStrategy.NOT_NULL, FieldStrategy.ALWAYS).flatMap(strategy -> Stream.of(0, 1)
                .flatMap(role -> Stream.of(1, 2, 3).flatMap(round -> Stream.of((String)null, "", "        ")
                        .map(password -> Arguments.of(strategy, role, round, password)))));
    }

    static Stream<Arguments> roleCases() {
        return Stream.of(0, 1).flatMap(role -> Stream.of(1, 2, 3).map(round -> Arguments.of(role, round)));
    }

    @ParameterizedTest @MethodSource("omittedCases")
    void omittedOrInternalBlankPasswordNeverRestoresTheStaleCredential(
            FieldStrategy strategy, int role, int round, String omitted) throws Exception {
        var sessions = factory(strategy);
        createAccount(role);
        try (var session = sessions.openSession(true)) {
            var users = users(session.getMapper(UserMapper.class));
            doAnswer(call -> {
                var snapshot = (UserPO) call.callRealMethod();
                assertThat(snapshot.getPassword()).isEqualTo("fixture-original-credential");
                concurrentCredentialWriteOnAnotherConnection();
                return snapshot;
            }).when(users).getById(id);
            var passwords = mock(PasswordSecurityService.class);

            assertThat(modify(role, users, passwords, omitted)).isTrue();
            assertThat(jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, id))
                    .isEqualTo("fixture-current-credential");
            assertThat(jdbc.queryForObject("SELECT name FROM `user` WHERE id=?", String.class, id))
                    .isEqualTo("fixture-updated-profile");
            assertThat(jdbc.queryForObject("SELECT type FROM `user` WHERE id=?", Integer.class, id)).isEqualTo(role);
            var patch = ArgumentCaptor.forClass(UserPO.class);
            verify(users).updateById(patch.capture());
            var sql = sessions.getConfiguration().getMappedStatement(UserMapper.class.getName() + ".updateById")
                    .getBoundSql(Map.of("et", patch.getValue())).getSql();
            assertThat(sql).doesNotContain("password");
            verifyNoInteractions(passwords);
        }
    }

    @ParameterizedTest @MethodSource("roleCases")
    void explicitPasswordStillUsesTheEncoderAndPersistsItsOutput(int role, int round) throws Exception {
        var sessions = factory(FieldStrategy.NOT_NULL);
        createAccount(role);
        try (var session = sessions.openSession(true)) {
            var users = users(session.getMapper(UserMapper.class));
            var passwords = mock(PasswordSecurityService.class);
            when(passwords.encode("fixture-new-password")).thenReturn("fixture-encoded-value");
            assertThat(modify(role, users, passwords, "fixture-new-password")).isTrue();
            assertThat(jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, id))
                    .isEqualTo("fixture-encoded-value");
            verify(passwords).encode("fixture-new-password");
        }
    }

    @ParameterizedTest @MethodSource("roleCases")
    void wrongTargetRoleIsStillRejectedWithoutUpdatingTheAccount(int role, int round) throws Exception {
        var sessions = factory(FieldStrategy.NOT_NULL);
        createAccount(2);
        try (var session = sessions.openSession(true)) {
            var users = users(session.getMapper(UserMapper.class));
            var passwords = mock(PasswordSecurityService.class);
            assertThatThrownBy(() -> modify(role, users, passwords, null)).isInstanceOf(BusinessException.class);
            verify(users, never()).updateById(any(UserPO.class));
            verifyNoInteractions(passwords);
            assertThat(jdbc.queryForObject("SELECT password FROM `user` WHERE id=?", String.class, id))
                    .isEqualTo("fixture-original-credential");
        }
    }
}
