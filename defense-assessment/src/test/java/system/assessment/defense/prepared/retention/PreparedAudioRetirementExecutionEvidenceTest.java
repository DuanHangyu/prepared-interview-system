package system.assessment.defense.prepared.retention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.RowMapper;
import system.assessment.defense.prepared.processing.PreparedProcessingService;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Checks the real evidence entry fence; full DB/task/cost/IO evidence remains live QA. */
class PreparedAudioRetirementExecutionEvidenceTest {
    PreparedRetentionEvidenceService service(PreparedAudioRetirementExecutionServiceTest.Fixture f){
        return new PreparedRetentionEvidenceService(f.submissions,mock(PreparedProcessingService.class),f.jdbc,f.manager,Clock.fixed(f.time,ZoneOffset.UTC));
    }
    @Test void onlyCurrentPreparationCanReachTheFreshFullEvidenceReload(){
        var f=new PreparedAudioRetirementExecutionServiceTest.Fixture();var marker=new IllegalStateException("full-original-evidence-reload");
        doThrow(marker).when(f.jdbc).query(startsWith("SELECT first_results_completed_at"),org.mockito.ArgumentMatchers.<RowMapper<?>>any(),any(Object[].class));
        assertThatThrownBy(()->service(f).inspectForExecution(1,2,f.attempt,f.preparation,1)).isSameAs(marker);
        verify(f.submissions).retentionSources(1,2,f.attempt);
        // The marker proves the fence proceeded to fresh original evidence, not that
        // processing results or costs were verified by this offline fixture.
    }
    @ParameterizedTest @ValueSource(strings={"wrong-preparation","wrong-revision","missing-preparation","missing-guard","held","already-executing"})
    void oldOrDamagedPreparationCannotBypassRetiringReadFence(String fault){
        var f=new PreparedAudioRetirementExecutionServiceTest.Fixture();String preparation=f.preparation;long revision=1;
        switch(fault){
            case "wrong-preparation"->preparation=UUID.randomUUID().toString();
            case "wrong-revision"->revision=2;
            case "missing-preparation"->f.missingPreparation=true;
            case "missing-guard"->f.guard=null;
            case "held"->f.guard=new PreparedRetentionProtectionStore.Guard(1,true,"RETIRING","RETAINED",null,f.prepared.preparedAt());
            case "already-executing"->f.begin();
            default->throw new AssertionError();
        }
        var id=preparation;long version=revision;assertThatThrownBy(()->service(f).inspectForExecution(1,2,f.attempt,id,version)).isInstanceOf(IllegalStateException.class);
        verify(f.jdbc,never()).query(startsWith("SELECT first_results_completed_at"),org.mockito.ArgumentMatchers.<RowMapper<?>>any(),any(Object[].class));
    }
    @Test void malformedInternalScopeIsNotTreatedAsCandidate(){
        var f=new PreparedAudioRetirementExecutionServiceTest.Fixture();var service=service(f);
        assertThatThrownBy(()->service.inspectForExecution(1,2,f.attempt,"bad",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.inspectForExecution(1,2,f.attempt,f.preparation,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.inspectForExecution(0,2,f.attempt,f.preparation,1)).isInstanceOf(IllegalArgumentException.class);
    }
}
