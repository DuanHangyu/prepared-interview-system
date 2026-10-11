package system.assessment.defense.infrastructure.concurrency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySqlAdvisoryLockServiceTest {

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement acquireStatement;
    private PreparedStatement releaseStatement;
    private ResultSet acquireResult;
    private ResultSet releaseResult;
    private MySqlAdvisoryLockService service;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        acquireStatement = mock(PreparedStatement.class);
        releaseStatement = mock(PreparedStatement.class);
        acquireResult = mock(ResultSet.class);
        releaseResult = mock(ResultSet.class);
        service = new MySqlAdvisoryLockService(dataSource);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(
                "SELECT GET_LOCK(SHA2(CONCAT(DATABASE(), ':', ?), 256), 0)"))
                .thenReturn(acquireStatement);
        when(connection.prepareStatement(
                "SELECT RELEASE_LOCK(SHA2(CONCAT(DATABASE(), ':', ?), 256))"))
                .thenReturn(releaseStatement);
        when(acquireStatement.executeQuery()).thenReturn(acquireResult);
        when(releaseStatement.executeQuery()).thenReturn(releaseResult);
        when(acquireResult.next()).thenReturn(true);
        when(releaseResult.next()).thenReturn(true);
        when(releaseResult.getInt(1)).thenReturn(1);
    }

    @Test
    void executesWorkAndReleasesLockOnTheSameConnection() throws Exception {
        when(acquireResult.getInt(1)).thenReturn(1);
        AtomicInteger calls = new AtomicInteger();

        boolean acquired = service.tryWithLock("scheduler:analysis", calls::incrementAndGet);

        assertThat(acquired).isTrue();
        assertThat(calls).hasValue(1);
        verify(acquireStatement).setString(1, "ai-interview:scheduler:analysis");
        verify(releaseStatement).setString(1, "ai-interview:scheduler:analysis");
        verify(releaseStatement).executeQuery();
        verify(connection).close();
    }

    @Test
    void skipsWorkWithoutReleasingAnotherOwnersLock() throws Exception {
        when(acquireResult.getInt(1)).thenReturn(0);
        AtomicInteger calls = new AtomicInteger();

        boolean acquired = service.tryWithLock("scheduler:analysis", calls::incrementAndGet);

        assertThat(acquired).isFalse();
        assertThat(calls).hasValue(0);
        verify(connection, never()).prepareStatement(
                "SELECT RELEASE_LOCK(SHA2(CONCAT(DATABASE(), ':', ?), 256))");
    }

    @Test
    void releasesLockWhenWorkFails() throws Exception {
        when(acquireResult.getInt(1)).thenReturn(1);

        RuntimeException failure = new RuntimeException("boom");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.tryWithLock("analysis:42", () -> {
                            throw failure;
                        }))
                .isSameAs(failure);

        verify(releaseStatement).executeQuery();
        verify(connection).close();
    }
}
