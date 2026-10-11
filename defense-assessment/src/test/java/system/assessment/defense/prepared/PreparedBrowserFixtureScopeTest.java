package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PreparedBrowserFixtureScopeTest {
    @Test void manualFixtureAliasesRemainCompatibleWhenNoFullRunScopeIsProvided(){
        assertThat(PreparedBrowserFixtureScope.account("teacher",null)).isEqualTo("prepared_browser_teacher");
        assertThat(PreparedBrowserFixtureScope.account("student",null)).isEqualTo("prepared_browser_student");
        assertThat(PreparedBrowserFixtureScope.account("unrelated",null)).isEqualTo("prepared_browser_unrelated");
    }
    @Test void fullRunAliasesAreStableDistinctAndCannotAdoptTheLegacyFixtureIdentity(){
        String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();
        for(String role:new String[]{"teacher","student","unrelated"}){
            String account=PreparedBrowserFixtureScope.account(role,first);
            assertThat(account).hasSizeLessThanOrEqualTo(64).matches("prepared_gate_[a-z]+_[a-f0-9]{32}");
            assertThat(account).isEqualTo(PreparedBrowserFixtureScope.account(role,first));
            assertThat(account).isNotEqualTo(PreparedBrowserFixtureScope.account(role,second));
            assertThat(account).isNotEqualTo(PreparedBrowserFixtureScope.account(role,null));
        }
        assertThat(PreparedBrowserFixtureScope.account("teacher",first)).isNotEqualTo(PreparedBrowserFixtureScope.account("student",first));
    }
    @ParameterizedTest @ValueSource(strings={"","../existing","not-a-uuid","00000000-0000-0000-0000-000000000000","01234567-89ab-4def-89ab-0123456789ab\n"})
    void invalidScopeCannotFallBackToAnExistingIdentity(String scope){
        assertThatThrownBy(()->PreparedBrowserFixtureScope.account("teacher",scope)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void arbitraryRoleCannotBecomeAnAccountSelector(){
        assertThatThrownBy(()->PreparedBrowserFixtureScope.account("administrator",UUID.randomUUID().toString())).isInstanceOf(IllegalArgumentException.class);
    }
}
