package system.assessment.defense.prepared.retention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.processing.PreparedProcessingCodec;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static system.assessment.defense.prepared.retention.PreparedAudioRetentionPolicy.*;
import static system.assessment.defense.prepared.retention.PreparedAudioRetirementPreparation.*;

/** Offline protocol tests with a scoped SQL fixture, NOT native database or OSS QA. */
class PreparedAudioRetirementExecutionServiceTest {
    static final class Fixture {
        final String attempt=UUID.randomUUID().toString(),preparation=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();
        final Instant due=Instant.parse("2024-08-01T00:00:00Z"),time=due.plusMillis(2);
        final JdbcTemplate jdbc=mock(JdbcTemplate.class);
        final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        final PreparedRetentionEvidenceService evidence=mock(PreparedRetentionEvidenceService.class);
        final PreparedSubmissionService submissions=mock(PreparedSubmissionService.class);
        final PreparedOriginalAudioInventory inventory=mock(PreparedOriginalAudioInventory.class);
        final PreparedOriginalAudioInventory.Snapshot physical=new PreparedOriginalAudioInventory.Snapshot(List.of(),140,"b".repeat(64));
        final PreparedSubmission.Binding binding=new PreparedSubmission.Binding(new PreparedSubmission.Work(1,1,2,attempt,UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,2,"c".repeat(64),UUID.randomUUID().toString(),"d".repeat(64)),UUID.randomUUID().toString(),UUID.randomUUID().toString());
        final Receipt prepared;
        final Map<String,Object> preparationRow=new HashMap<>();
        Map<String,Object> executionRow;
        PreparedRetentionProtectionStore.Guard guard;
        boolean missingAssessment,missingAttempt,missingPreparation,failGuardCas;
        int inserts;
        Fixture(){
            // Snapshot is mocked solely to avoid manufacturing physical object locators.
            var snapshot=mock(PreparedOriginalAudioInventory.Snapshot.class);
            when(snapshot.objects()).thenReturn(Collections.nCopies(3,null));when(snapshot.byteSize()).thenReturn(140L);when(snapshot.sha256()).thenReturn("b".repeat(64));
            prepared=new Receipt(preparation,1,2,attempt,UUID.randomUUID().toString(),0,1,due.toString(),due.plusMillis(1).toString(),PreparedAudioRetirementExecution.evidenceHash(List.of(binding),snapshot,due));
            preparationRow.putAll(preparationRow(prepared));guard=new PreparedRetentionProtectionStore.Guard(1,false,"RETIRING","RETAINED",null,prepared.preparedAt());
            when(evidence.inspectForExecution(1,2,attempt,preparation,1)).thenReturn(new Decision(State.DUE,due,Set.of()));
            when(submissions.retentionSources(1,2,attempt)).thenReturn(new PreparedSubmissionService.ReviewSources(null,null,List.of(new PreparedSubmissionService.ProcessingSource(binding,null,null))));
            when(inventory.read(1,2,attempt)).thenReturn(snapshot);
            when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<?>>any(),any(Object[].class))).thenAnswer(call->{
                String sql=call.getArgument(0);Object[] args=(Object[])call.getRawArguments()[2];
                RowMapper<?> mapper=call.getArgument(1);
                if(sql.contains("FROM prepared_retention_guard")){
                    if(guard==null)return List.of();var row=new HashMap<String,Object>();row.put("revision",guard.revision());row.put("held",guard.held());row.put("audio_state",guard.audio());row.put("results_state",guard.results());row.put("last_command_id",guard.last());row.put("updated_at",Timestamp.from(Instant.parse(guard.updated())));
                    return mapped(row,mapper);
                }
                if(sql.contains("FROM prepared_audio_retirement_preparation"))return missingPreparation?List.of():mapped(preparationRow,mapper);
                if(sql.contains("FROM prepared_audio_retirement_execution")){
                    if(executionRow==null||sql.contains("idempotency_key=?")&&!Objects.equals(args[3],executionRow.get("idempotency_key")))return List.of();
                    return mapped(executionRow,mapper);
                }
                throw new AssertionError("Unexpected SQL query");
            });
            when(jdbc.queryForList(anyString(),any(Class.class),any(Object[].class))).thenAnswer(call->{
                String sql=call.getArgument(0);
                if(sql.contains("FROM assessment"))return missingAssessment?List.of():List.of(2);
                if(sql.contains("FROM prepared_attempt"))return missingAttempt?List.of():List.of(attempt);
                if(sql.contains("FROM prepared_retention_command"))return List.of();
                if(sql.contains("FROM prepared_audio_retirement_preparation"))return "STAGED".equals(preparationRow.get("state"))&&!missingPreparation?List.of(preparation):List.of();
                throw new AssertionError("Unexpected SQL list query");
            });
            when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(call->{
                String sql=call.getArgument(0);Object[] a=(Object[])call.getRawArguments()[1];
                if(sql.contains("INSERT INTO prepared_audio_retirement_execution")){
                    inserts++;executionRow=new HashMap<>();String[] fields={"id","assessment_id","student_id","attempt_id","preparation_id","idempotency_key","expected_revision","revision","started_at","inventory_sha256","object_count","byte_size","receipt_json","receipt_sha256"};
                    for(int i=0;i<fields.length;i++)executionRow.put(fields[i],a[i]);executionRow.put("state","INTENT");return 1;
                }
                if(sql.startsWith("UPDATE prepared_retention_guard")){
                    if(failGuardCas)return 0;
                    guard=new PreparedRetentionProtectionStore.Guard(((Number)a[0]).longValue(),false,sql.contains("audio_state='RETAINED',")?"RETAINED":"RETIRING","RETAINED",null,((Timestamp)a[1]).toInstant().toString());return 1;
                }
                if(sql.contains("UPDATE prepared_audio_retirement_preparation")){
                    if(executionRow!=null&&sql.contains("NOT EXISTS"))return 0;
                    preparationRow.put("state","CANCELLED");preparationRow.put("cancellation_json",a[0]);preparationRow.put("cancellation_sha256",a[1]);return 1;
                }
                throw new AssertionError("Unexpected SQL write");
            });
        }
        List<?> mapped(Map<String,Object> row,RowMapper<?> mapper)throws Exception{
            var rs=mock(ResultSet.class);
            when(rs.getString(anyString())).thenAnswer(c->(String)row.get(c.getArgument(0)));
            when(rs.getInt(anyString())).thenAnswer(c->((Number)row.get(c.getArgument(0))).intValue());
            when(rs.getLong(anyString())).thenAnswer(c->((Number)row.get(c.getArgument(0))).longValue());
            when(rs.getBoolean(anyString())).thenAnswer(c->(Boolean)row.get(c.getArgument(0)));
            when(rs.getTimestamp(anyString())).thenAnswer(c->(Timestamp)row.get(c.getArgument(0)));
            return List.of(mapper.mapRow(rs,0));
        }
        PreparedAudioRetirementExecutionService service(){return service(time);}
        PreparedAudioRetirementExecutionService service(Instant now){return new PreparedAudioRetirementExecutionService(evidence,submissions,jdbc,manager,Clock.fixed(now,ZoneOffset.UTC),inventory);}
        Command command(){return new Command(key,1);}
        PreparedAudioRetirementExecution.Receipt begin(){return service().begin(1,2,attempt,preparation,command());}
        Map<String,Object> preparationRow(Receipt receipt){
            var r=new HashMap<String,Object>();r.put("id",receipt.id());r.put("assessment_id",2);r.put("student_id",1);r.put("attempt_id",attempt);r.put("idempotency_key",receipt.idempotencyKey());r.put("expected_revision",receipt.expectedRevision());r.put("revision",receipt.revision());r.put("prepared_at",Timestamp.from(Instant.parse(receipt.preparedAt())));r.put("state","STAGED");String json=PreparedProcessingCodec.encode(receipt);r.put("receipt_json",json);r.put("receipt_sha256",PreparedJobInput.digest(json));return r;
        }
    }
    @Test void beginRevalidatesCurrentEvidenceAndOriginalPhysicalInventoryBeforeDurableIntent(){
        var f=new Fixture();var result=f.begin();
        assertThat(result.preparationId()).isEqualTo(f.preparation);assertThat(result.revision()).isEqualTo(2);assertThat(result.objectCount()).isEqualTo(3);assertThat(result.byteSize()).isEqualTo(140);
        assertThat(f.guard.audio()).isEqualTo("RETIRING");assertThat(f.inserts).isEqualTo(1);
        var order=inOrder(f.evidence,f.submissions,f.inventory,f.jdbc);
        order.verify(f.evidence).inspectForExecution(1,2,f.attempt,f.preparation,1);
        order.verify(f.submissions).retentionSources(1,2,f.attempt);order.verify(f.inventory).read(1,2,f.attempt);
        order.verify(f.jdbc).update(contains("INSERT INTO prepared_audio_retirement_execution"),any(Object[].class));
        assertThat(PreparedProcessingCodec.encode(result)).doesNotContain("transcript","objectKey","feedback");
    }
    @Test void restartedServiceReplaysOnlyTheOriginalCommandWithoutNewIoOrGeneration(){
        var f=new Fixture();var original=f.begin();clearInvocations(f.evidence,f.submissions,f.inventory);
        assertThat(f.service().begin(1,2,f.attempt,f.preparation,f.command())).isEqualTo(original);
        assertThat(f.service().status(1,2,f.attempt)).contains(original);assertThat(f.inserts).isEqualTo(1);
        verifyNoInteractions(f.evidence,f.submissions,f.inventory);
        assertThatThrownBy(()->f.service().begin(1,2,f.attempt,f.preparation,new Command(UUID.randomUUID().toString(),2))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.service().begin(1,2,f.attempt,f.preparation,new Command(f.key,0))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->f.service().begin(1,2,f.attempt,UUID.randomUUID().toString(),f.command())).isInstanceOf(BusinessException.class);
    }
    @Test void cancellationAndReadRecoveryRemainBlockedAfterIntentIncludingWithOldReceipt(){
        var f=new Fixture();f.begin();
        var preparationService=new PreparedAudioRetirementPreparationService(f.evidence,f.submissions,f.jdbc,f.manager,Clock.fixed(f.time,ZoneOffset.UTC));
        assertThat(preparationService.status(1,2,f.attempt).orElseThrow().state()).isEqualTo("EXECUTING");
        assertThatThrownBy(()->preparationService.cancel(1,2,f.attempt,f.preparation,new Command(UUID.randomUUID().toString(),1))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->preparationService.cancel(1,2,f.attempt,f.preparation,new Command(UUID.randomUUID().toString(),2))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->new PreparedAudioRetirementPreparationStore(f.jdbc).cancel(new Cancellation(f.preparation,1,2,f.attempt,UUID.randomUUID().toString(),1,2,f.time.toString()))).isInstanceOf(IllegalStateException.class);
        assertThat(f.guard.audio()).isEqualTo("RETIRING");assertThat(f.preparationRow.get("state")).isEqualTo("STAGED");
    }
    @ParameterizedTest @ValueSource(strings={"missing-assessment","missing-attempt","missing-preparation","missing-guard","stale-version","held","audio-retained","results-retiring","future-time","not-due","protected","different-due","different-inventory","missing-bindings"})
    void invalidScopeVersionProtectionOrFreshEvidenceDoesNotCreateExecution(String fault){
        var f=new Fixture();switch(fault){
            case "missing-assessment"->f.missingAssessment=true;
            case "missing-attempt"->f.missingAttempt=true;
            case "missing-preparation"->f.missingPreparation=true;
            case "missing-guard"->f.guard=null;
            case "stale-version"->f.guard=new PreparedRetentionProtectionStore.Guard(3,false,"RETIRING","RETAINED",null,f.prepared.preparedAt());
            case "held"->f.guard=new PreparedRetentionProtectionStore.Guard(1,true,"RETIRING","RETAINED",null,f.prepared.preparedAt());
            case "audio-retained"->f.guard=new PreparedRetentionProtectionStore.Guard(1,false,"RETAINED","RETAINED",null,f.prepared.preparedAt());
            case "results-retiring"->f.guard=new PreparedRetentionProtectionStore.Guard(1,false,"RETIRING","RETIRING",null,f.prepared.preparedAt());
            case "future-time"->{ }
            case "not-due"->when(f.evidence.inspectForExecution(1,2,f.attempt,f.preparation,1)).thenReturn(new Decision(State.WITHIN_RETENTION,f.due,Set.of()));
            case "protected"->when(f.evidence.inspectForExecution(1,2,f.attempt,f.preparation,1)).thenReturn(new Decision(State.PROTECTED,f.due,Set.of(Protection.PROCESSING)));
            case "different-due"->when(f.evidence.inspectForExecution(1,2,f.attempt,f.preparation,1)).thenReturn(new Decision(State.DUE,f.due.minusMillis(1),Set.of()));
            case "different-inventory"->when(f.inventory.read(1,2,f.attempt)).thenReturn(f.physical);
            case "missing-bindings"->when(f.submissions.retentionSources(1,2,f.attempt)).thenReturn(new PreparedSubmissionService.ReviewSources(null,null,List.of()));
            default->throw new AssertionError();
        }
        var service=f.service(fault.equals("future-time")?f.due:f.time);
        assertThatThrownBy(()->service.begin(1,2,f.attempt,f.preparation,f.command()))
            .isInstanceOfAny(BusinessException.class,IllegalStateException.class,IllegalArgumentException.class);
        assertThat(f.inserts).isZero();assertThat(f.executionRow).isNull();verify(f.manager).rollback(any());
    }
    @Test void failedGuardCasRollsBackInsteadOfPublishingReceipt(){
        var f=new Fixture();f.failGuardCas=true;
        assertThatThrownBy(f::begin).isInstanceOf(BusinessException.class);verify(f.manager).rollback(any());verify(f.manager,never()).commit(any());
        // This checks transaction disposition only. Native DB rollback remains a live gate.
    }
    @ParameterizedTest @ValueSource(strings={"execution-missing","execution-hash","guard-time","guard-revision","prepared-cancelled","prepared-missing","wrong-preparation"})
    void restartNeverTrustsDamagedOrDetachedExecution(String fault){
        var f=new Fixture();f.begin();switch(fault){
            case "execution-missing"->f.executionRow=null;
            case "execution-hash"->f.executionRow.put("receipt_sha256","f".repeat(64));
            case "guard-time"->f.guard=new PreparedRetentionProtectionStore.Guard(2,false,"RETIRING","RETAINED",null,f.prepared.preparedAt());
            case "guard-revision"->f.guard=new PreparedRetentionProtectionStore.Guard(1,false,"RETIRING","RETAINED",null,f.time.toString());
            case "prepared-cancelled"->{f.preparationRow.put("state","CANCELLED");var c=new Cancellation(f.preparation,1,2,f.attempt,UUID.randomUUID().toString(),1,2,f.time.toString());var json=PreparedProcessingCodec.encode(c);f.preparationRow.put("cancellation_json",json);f.preparationRow.put("cancellation_sha256",PreparedJobInput.digest(json));}
            case "prepared-missing"->f.missingPreparation=true;
            case "wrong-preparation"->f.executionRow.put("preparation_id",UUID.randomUUID().toString());
            default->throw new AssertionError();
        }
        assertThatThrownBy(()->f.service().status(1,2,f.attempt)).isInstanceOf(IllegalStateException.class);
    }
    @Test void noIntentKeepsPreparationCancellableAndDoesNotActivateItOnRead(){
        var f=new Fixture();assertThat(f.service().status(1,2,f.attempt)).isEmpty();
        var original=new PreparedAudioRetirementPreparationService(f.evidence,f.submissions,f.jdbc,f.manager,Clock.fixed(f.time,ZoneOffset.UTC));
        var cancelled=original.cancel(1,2,f.attempt,f.preparation,new Command(UUID.randomUUID().toString(),1));
        assertThat(f.guard.audio()).isEqualTo("RETAINED");assertThat(cancelled.revision()).isEqualTo(2);assertThat(f.service().status(1,2,f.attempt)).isEmpty();
        assertThatThrownBy(f::begin).isInstanceOf(BusinessException.class);assertThat(f.inserts).isZero();
    }
    @Test void malformedCommandsAndScopeFailBeforeEvidenceAndWrites(){
        var f=new Fixture();assertThatThrownBy(()->f.service().begin(1,2,f.attempt,"bad",f.command())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.service().begin(1,2,f.attempt,f.preparation,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.service().status(0,2,f.attempt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.service().status(1,2,"bad")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(f.evidence,f.submissions,f.inventory);assertThat(f.inserts).isZero();
    }
    @Test void constructorAndInternalDigestKeepTheirOriginalBounds(){
        var f=new Fixture();assertThat(new PreparedAudioRetirementExecutionService(f.evidence,f.submissions,f.jdbc,f.manager)).isNotNull();
        for(List<?> bindings:Arrays.asList(null,List.of(),Collections.nCopies(1001,f.binding)))
            assertThatThrownBy(()->PreparedAudioRetirementExecution.evidenceHash(bindings,f.physical,f.due)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedAudioRetirementExecution.evidenceHash(List.of(f.binding),null,f.due)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedAudioRetirementExecution.evidenceHash(List.of(f.binding),f.physical,null)).isInstanceOf(IllegalArgumentException.class);
        var store=new PreparedAudioRetirementExecutionStore(f.jdbc);
        assertThatThrownBy(()->store.byScope(0,2,f.attempt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->store.byScope(1,0,f.attempt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->store.byKey(1,2,f.attempt,"bad")).isInstanceOf(IllegalArgumentException.class);
    }
}
