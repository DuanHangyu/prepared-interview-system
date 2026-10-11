package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import system.assessment.defense.application.service.PasswordSecurityService;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Genuine isolated SQL/credential verification; never changes previous fixture identities. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedBrowserFixtureIsolationLiveTest {
    @Test void freshScopedFixturesAreRepeatableAndKeepAllPriorAliasCredentialsUnchanged()throws Exception{
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(
            PreparedLiveDatabase.jdbcUrl(),
            Objects.requireNonNull(System.getenv("PREPARED_DB_USERNAME")),Objects.requireNonNull(System.getenv("PREPARED_DB_PASSWORD"))));
        jdbc.setQueryTimeout(5);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("prepared_interview");
        assertThat(Path.of(jdbc.queryForObject("SELECT @@datadir",String.class)).toRealPath()).isEqualTo(PreparedLiveDatabase.checked().expectedData().toRealPath());
        var original=jdbc.queryForList("SELECT id,account,password,type FROM `user` WHERE account IN ('prepared_browser_teacher','prepared_browser_student','prepared_browser_unrelated') ORDER BY id");
        String namespace=UUID.randomUUID().toString();
        List<String> accounts=List.of(PreparedBrowserFixtureScope.account("teacher",namespace),PreparedBrowserFixtureScope.account("student",namespace),PreparedBrowserFixtureScope.account("unrelated",namespace));
        for(String account:accounts)assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE account=?",Integer.class,account)).isZero();
        Integer assessment=null;var users=new ArrayList<Integer>();
        try{
            var fixture=new PreparedBrowserFixtureTest();fixture.provisionForScope(namespace);
            Path receipt=Path.of("../.runtime/owned-browser-"+namespace+".json");
            assertThat(Files.getPosixFilePermissions(receipt)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            var parsed=new ObjectMapper().readTree(Files.readAllBytes(receipt));
            assertThat(parsed.path("namespace").asText()).isEqualTo(namespace);
            assessment=parsed.path("assessmentId").asInt();
            for(String key:List.of("teacherId","studentId","unrelatedId"))users.add(parsed.path(key).asInt());
            assertThat(new HashSet<>(users)).hasSize(3);assertThat(assessment).isPositive();
            for(int i=0;i<3;i++){
                assertThat(jdbc.queryForObject("SELECT account FROM `user` WHERE id=?",String.class,users.get(i))).isEqualTo(accounts.get(i));
                assertThat(jdbc.queryForObject("SELECT type FROM `user` WHERE id=?",Integer.class,users.get(i))).isEqualTo(i==1?0:1);
                boolean matched=new PasswordSecurityService().verify(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"),jdbc.queryForObject("SELECT password FROM `user` WHERE id=?",String.class,users.get(i))).matched();
                assertThat(matched).isTrue();
            }
            byte[] first=Files.readAllBytes(receipt);fixture.provisionForScope(namespace);
            assertThat(Arrays.equals(first,Files.readAllBytes(receipt))).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE create_id=?",Integer.class,users.getFirst())).isEqualTo(1);
            var after=jdbc.queryForList("SELECT id,account,password,type FROM `user` WHERE account IN ('prepared_browser_teacher','prepared_browser_student','prepared_browser_unrelated') ORDER BY id");
            assertThat(original.equals(after)).as("no previous alias or credential is adopted, rotated or removed").isTrue();
        }finally{
            // Only this test's freshly declared nonce and verified ids. Preserve all
            // prior aliases and every other full-run fixture; no prefix-wide purge.
            if(assessment!=null&&users.size()==3){
                jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",assessment,users.get(1));
                assertThat(jdbc.update("DELETE FROM assessment WHERE id=? AND create_id=?",assessment,users.getFirst())).isEqualTo(1);
                for(int i=0;i<3;i++)assertThat(jdbc.update("DELETE FROM `user` WHERE id=? AND account=?",users.get(i),accounts.get(i))).isEqualTo(1);
            }
        }
    }
}
