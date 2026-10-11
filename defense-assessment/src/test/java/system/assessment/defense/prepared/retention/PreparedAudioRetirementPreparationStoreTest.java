package system.assessment.defense.prepared.retention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.processing.PreparedProcessingCodec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static system.assessment.defense.prepared.retention.PreparedAudioRetirementPreparation.*;

class PreparedAudioRetirementPreparationStoreTest {
    final String id=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();
    final Receipt receipt=new Receipt(id,1,2,attempt,key,0,1,"2024-08-01T00:00:00Z","2024-08-01T00:00:00.001Z","a".repeat(64));
    final Cancellation cancellation=new Cancellation(id,1,2,attempt,UUID.randomUUID().toString(),1,2,"2024-08-01T00:00:00.002Z");
    Map<String,Object> row(){var row=new HashMap<String,Object>();row.put("id",id);row.put("student_id",1);row.put("assessment_id",2);row.put("attempt_id",attempt);row.put("idempotency_key",key);row.put("expected_revision",0L);row.put("revision",1L);row.put("prepared_at",Timestamp.from(Instant.parse(receipt.preparedAt())));row.put("state","STAGED");put(row,"receipt",receipt);return row;}
    void put(Map<String,Object> row,String prefix,Object value){String json=PreparedProcessingCodec.encode(value);row.put(prefix+"_json",json);row.put(prefix+"_sha256",PreparedJobInput.digest(json));}
    Map<String,Object> payload(Object value){return new HashMap<>(PreparedProcessingCodec.decode(PreparedProcessingCodec.encode(value),Map.class));}
    PreparedAudioRetirementPreparationStore store(Map<String,Object> row){var jdbc=mock(JdbcTemplate.class);when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Map<String,Object>>>any(),any(Object[].class))).thenReturn(List.of(row));return new PreparedAudioRetirementPreparationStore(jdbc);}
    @Test void commandsReserveSafeCancellationVersionAndNeverAcceptCoercionOrInvalidIdentity(){
        assertThat(new Command(key,9007199254740990L).expectedRevision()).isEqualTo(9007199254740990L);
        for(long revision:new long[]{-1,9007199254740991L,Long.MAX_VALUE})assertThatThrownBy(()->new Command(key,revision)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Command("bad",0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void validImmutablePreparationAndCancellationAreReadSeparatelyFromCurrentLifecycle(){
        var row=row();assertThat(store(row).byKey(1,2,attempt,key).orElseThrow()).isEqualTo(new View("STAGED",receipt,null));
        row.put("state","CANCELLED");put(row,"cancellation",cancellation);assertThat(store(row).byId(1,2,attempt,id).orElseThrow()).isEqualTo(new View("CANCELLED",receipt,cancellation));
    }
    @Test void cancellationWriteMustFenceAnAlreadyRegisteredIrreversibleExecution(){
        var jdbc=mock(JdbcTemplate.class);
        // Model the database predicate, not an executor mock: a registered execution
        // must make this write affect zero rows even if a caller bypasses its guard.
        when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(call->{
            String sql=call.getArgument(0);
            return sql.contains("NOT EXISTS")&&sql.contains("prepared_audio_retirement_execution")?0:1;
        });
        assertThatThrownBy(()->new PreparedAudioRetirementPreparationStore(jdbc).cancel(cancellation))
            .isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"id","studentId","assessmentId","attemptId","idempotencyKey","expectedRevision","revision","audioDueAt","preparedAt","evidenceSha256","null-id","null-evidence"})
    void receiptCannotRebindEvenWhenAnAttackerRecomputesItsHash(String field){
        var row=row();var value=payload(receipt);switch(field){
            case "id","attemptId","idempotencyKey"->value.put(field,UUID.randomUUID().toString());
            case "studentId","assessmentId"->value.put(field,0);
            case "expectedRevision"->value.put(field,-1);
            case "revision"->value.put(field,5);
            case "audioDueAt"->value.put(field,"2025-01-01T00:00:00Z");
            case "preparedAt"->value.put(field,"2024-08-01T00:00:00.002Z");
            case "evidenceSha256"->value.put(field,"bad");
            case "null-id"->value.put("id",null);
            case "null-evidence"->value.put("evidenceSha256",null);
            default->throw new AssertionError();
        }put(row,"receipt",value);assertThatThrownBy(()->store(row).byId(1,2,attempt,id)).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"id","student_id","assessment_id","attempt_id","idempotency_key","expected_revision","revision","prepared_at","receipt_sha256","state","partial-cancellation","malformed","noncanonical"})
    void rowMetadataMalformedJsonAndIncompleteStatesCannotHideReceiptDamage(String field){
        var row=row();switch(field){
            case "id","attempt_id","idempotency_key"->row.put(field,UUID.randomUUID().toString());
            case "student_id","assessment_id","expected_revision","revision"->row.put(field,42);
            case "prepared_at"->row.put(field,Timestamp.from(Instant.parse(receipt.preparedAt()).plusMillis(1)));
            case "receipt_sha256"->row.put(field,"b".repeat(64));
            case "state"->row.put(field,"EXECUTING");
            case "partial-cancellation"->row.put("cancellation_sha256","b".repeat(64));
            case "malformed"->row.put("receipt_json","{}");
            case "noncanonical"->row.put("receipt_json",((String)row.get("receipt_json")).replace(",",", "));
            default->throw new AssertionError();
        }assertThatThrownBy(()->store(row).byId(1,2,attempt,id)).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"preparationId","studentId","assessmentId","attemptId","idempotencyKey","expectedRevision","revision","cancelledAt","hash","noncanonical","missing"})
    void cancellationCannotRestoreAudioWithForgedScopeVersionOrTimeline(String field){
        var row=row();row.put("state","CANCELLED");var value=payload(cancellation);
        switch(field){
            case "preparationId","attemptId"->value.put(field,UUID.randomUUID().toString());
            case "studentId","assessmentId","expectedRevision","revision"->value.put(field,42);
            case "idempotencyKey"->value.put(field,"bad");
            case "cancelledAt"->value.put(field,"2024-08-01T00:00:00Z");
            case "hash","noncanonical","missing"->{ }
            default->throw new AssertionError();
        }put(row,"cancellation",value);
        if(field.equals("hash"))row.put("cancellation_sha256","b".repeat(64));
        if(field.equals("noncanonical"))row.put("cancellation_json",((String)row.get("cancellation_json")).replace(",",", "));
        if(field.equals("missing"))row.put("cancellation_json",null);
        assertThatThrownBy(()->store(row).byId(1,2,attempt,id)).isInstanceOf(IllegalStateException.class);
    }
}
