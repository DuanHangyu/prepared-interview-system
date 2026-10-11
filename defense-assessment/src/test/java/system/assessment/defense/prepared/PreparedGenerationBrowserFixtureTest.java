package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import system.assessment.defense.prepared.generation.*;
import system.assessment.defense.prepared.jobs.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit synthetic output fixture, real HTTP/database/browser adoption, zero provider calls. */
@EnabledIfEnvironmentVariable(named="PREPARED_BROWSER_GENERATION_FIXTURES",matches="true")
class PreparedGenerationBrowserFixtureTest {
    @Test void provisionThreeIndependentSyntheticSuggestionsForRepeatedBrowserRuns() {
        var ds=new DriverManagerDataSource(PreparedLiveDatabase.jdbcUrl(),
                Objects.requireNonNull(System.getenv("PREPARED_DB_USERNAME")),Objects.requireNonNull(System.getenv("PREPARED_DB_PASSWORD")));
        var jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("prepared_interview");
        String namespace=PreparedBrowserFixtureScope.namespace();
        // Full discovery order is not guaranteed. Provision the SAME owned scope,
        // rather than relying on an earlier suite or adopting a previous teacher.
        if(namespace!=null)new PreparedBrowserFixtureTest().provisionIsolatedBrowserFixtures();
        int teacher=jdbc.queryForObject("SELECT id FROM `user` WHERE account=? AND type=1",Integer.class,
            PreparedBrowserFixtureScope.account("teacher",namespace));
        int assessment=jdbc.queryForObject("SELECT id FROM assessment WHERE theme='预置题目 · 独立浏览器联调' AND create_id=? AND deleted=0",Integer.class,teacher);
        var ledger=new PreparedJobLedger(jdbc,new JdbcTransactionManager(ds));List<String> ids=new ArrayList<>();
        var p=new PreparedGenerationPolicyTest();var policy=new PreparedGenerationPolicy(p.configured(),p.clock).snapshot();
        for(int i=0;i<3;i++) {
            var cmd=new PreparedGeneration.Command(0L,UUID.randomUUID().toString(),"浏览器合成建议测试，非真实模型",1,10,120);
            var job=ledger.enqueue(teacher,assessment,PreparedJobLedger.Kind.QUESTIONS,0,cmd.idempotencyKey(),
                    PreparedGeneration.encode(new PreparedGeneration.Input(cmd,policy)));
            String content="{\"questions\":[{\"body\":\"自动化合成建议（非真实模型）\",\"rubric\":\"合成测试标准\",\"maxScore\":10,\"answerSeconds\":120}]}";
            assertThat(new PreparedGenerationCodec().decode(job.id(),content,1,10,120)).hasSize(1);
            jdbc.update("UPDATE prepared_job SET state='SUCCEEDED',output_json=? WHERE id=? AND state='QUEUED'",content,job.id());ids.add(job.id());
        }
        System.out.println("PREPARED_BROWSER_GENERATION_JOB_IDS="+String.join(",",ids));
    }
}
