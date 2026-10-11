package system.assessment.defense.infrastructure.migration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.Modifier;
import static org.assertj.core.api.Assertions.assertThat;

/** Inventory guard: every destructive suite must own a fail-visible lifecycle.
 * Actual routing, SQL scenarios and native shutdown are verified by live tests. */
class OwnedMigrationSuiteContractTest {
    @ParameterizedTest
    @ValueSource(strings={
        "infrastructure.migration.DatabaseMigrationMySqlIT",
        "infrastructure.concurrency.InterviewConcurrencyMySqlIT",
        "infrastructure.concurrency.InterviewLifecycleTransactionMySqlIT",
        "application.service.DataRetentionMySqlIT",
        "application.service.ManagedObjectCleanupMySqlIT",
        "application.service.ManagedUploadMySqlIT"
    })
    void everyDestructiveSuiteOwnsInitializationAndFailVisibleShutdown(String name)throws Exception{
        Class<?> suite=Class.forName("system.assessment.defense."+name);
        var open=suite.getDeclaredMethod("openOwnedMysql");
        var close=suite.getDeclaredMethod("closeOwnedMysql");
        assertThat(open.isAnnotationPresent(BeforeAll.class)).isTrue();
        assertThat(close.isAnnotationPresent(AfterAll.class)).isTrue();
        assertThat(Modifier.isStatic(open.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(close.getModifiers())).isTrue();
        assertThat(suite.getDeclaredField("ownedMysql").getType()).isEqualTo(OwnedMigrationMysql.class);
    }
}
