package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PreparedLiveDatabaseTest {
    @Test void plainEnvironmentCannotRedirectDestructiveFixturesToAnotherDatabase() {
        for(String address:new String[]{"jdbc:mysql://remote:13317/prepared_interview?sslMode=REQUIRED",
            "jdbc:mysql://127.0.0.1:13003/prepared_interview?sslMode=REQUIRED",
            "jdbc:mysql://127.0.0.1:13317/ai_interview?sslMode=REQUIRED",
            "jdbc:mysql://127.0.0.1:13317/prepared_interview?sslMode=DISABLED",
            "jdbc:mysql://127.0.0.1:13317/prepared_interview?sslMode=REQUIRED&allowMultiQueries=true"})
            assertThatThrownBy(()->PreparedLiveDatabase.validate(Map.of("PREPARED_DB_JDBC_URL",address))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void canonicalLocalConfigIsExplicitAndHasNoSilentFallback() {
        assertThatThrownBy(()->PreparedLiveDatabase.validate(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedLiveDatabase.validate(Map.of("PREPARED_DB_JDBC_URL","jdbc:mysql://127.0.0.1:13317/prepared_interview?sslMode=REQUIRED"))).isInstanceOf(IllegalArgumentException.class);
        var config=PreparedLiveDatabase.validate(Map.of("PREPARED_DB_JDBC_URL","jdbc:mysql://127.0.0.1:13317/prepared_interview?sslMode=REQUIRED","PREPARED_DB_USERNAME","prepared_test","PREPARED_DB_PASSWORD","unit-only-private-identity"));
        assertThat(config.url()).contains(":13317/prepared_interview?");
        assertThat(config.toString()).doesNotContain("unit-only-private-identity");
    }
    @Test void alternateLoopbackServerRequiresPrivateOwnerDescriptor() {
        var env=Map.of("PREPARED_DB_JDBC_URL","jdbc:mysql://127.0.0.1:24444/prepared_interview?sslMode=REQUIRED","PREPARED_DB_USERNAME","prepared_app","PREPARED_DB_PASSWORD","unit-only-private-identity");
        assertThatThrownBy(()->PreparedLiveDatabase.validate(env)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void browserProcessesNeverInheritDatabaseOrProviderSecrets() {
        var env=new java.util.HashMap<String,String>(Map.of("HOME","/unit-home","PATH","/unit-bin","PREPARED_TEST_LOGIN_PASSWORD","fixture-only",
            "PREPARED_DB_PASSWORD","never-forward","PREPARED_VALIDATION_DATABASE_DESCRIPTOR","never-forward","PREPARED_DASHSCOPE_API_KEY","never-forward",
            "PREPARED_VALIDATION_BROWSER_DESCRIPTOR","content-free-path","PREPARED_VALIDATION_BROWSER_RUN_ID","11111111-1111-4111-8111-111111111111"));
        PreparedLiveDatabase.browserEnvironment(env);
        assertThat(env).doesNotContainValue("never-forward");assertThat(env).containsEntry("PREPARED_TEST_LOGIN_PASSWORD","fixture-only").containsEntry("PREPARED_VALIDATION_BROWSER_DESCRIPTOR","content-free-path");
    }
}
