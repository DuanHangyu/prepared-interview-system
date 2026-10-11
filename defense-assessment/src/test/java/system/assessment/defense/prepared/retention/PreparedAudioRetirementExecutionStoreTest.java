package system.assessment.defense.prepared.retention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.processing.PreparedProcessingCodec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedAudioRetirementExecutionStoreTest {
    final String attempt=UUID.randomUUID().toString(),preparation=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();
    final PreparedAudioRetirementPreparation.Receipt prepared=new PreparedAudioRetirementPreparation.Receipt(preparation,1,2,attempt,UUID.randomUUID().toString(),0,1,"2024-08-01T00:00:00Z","2024-08-01T00:00:00.001Z","a".repeat(64));
    final PreparedAudioRetirementExecution.Receipt receipt=new PreparedAudioRetirementExecution.Receipt(UUID.randomUUID().toString(),1,2,attempt,preparation,key,1,2,"2024-08-01T00:00:00.002Z","a".repeat(64),"b".repeat(64),3,140);
    Map<String,Object> row(){
        var r=new HashMap<String,Object>();r.put("id",receipt.id());r.put("assessment_id",2);r.put("student_id",1);r.put("attempt_id",attempt);r.put("preparation_id",preparation);r.put("idempotency_key",key);
        r.put("expected_revision",1L);r.put("revision",2L);r.put("started_at",Timestamp.from(Instant.parse(receipt.startedAt())));r.put("inventory_sha256",receipt.inventorySha256());r.put("object_count",receipt.objectCount());r.put("byte_size",receipt.byteSize());r.put("state","INTENT");put(r,receipt);return r;
    }
    void put(Map<String,Object> r,Object payload){String json=PreparedProcessingCodec.encode(payload);r.put("receipt_json",json);r.put("receipt_sha256",PreparedJobInput.digest(json));}
    PreparedAudioRetirementExecutionStore store(Map<String,Object> row){var jdbc=mock(JdbcTemplate.class);when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Map<String,Object>>>any(),any(Object[].class))).thenReturn(List.of(row));return new PreparedAudioRetirementExecutionStore(jdbc);}
    @Test void immutableExecutionIsOriginalScopedAndContainsNoObjectLocator(){
        assertThat(store(row()).byScope(1,2,attempt).orElseThrow()).isEqualTo(receipt);
        assertThat(store(row()).byKey(1,2,attempt,key).orElseThrow()).isEqualTo(receipt);
        PreparedAudioRetirementExecutionStore.requireBinding(receipt,prepared);
        assertThat(PreparedProcessingCodec.encode(receipt)).doesNotContain("objectKey","transcript","feedback","http","prepared/");
        assertThatThrownBy(()->store(row()).byScope(3,2,attempt)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->store(row()).byKey(1,2,attempt,UUID.randomUUID().toString())).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"id","studentId","assessmentId","attemptId","preparationId","idempotencyKey","expectedRevision","revision","startedAt","evidenceSha256","inventorySha256","objectCount","byteSize","valid-inventory","valid-count","valid-bytes","null","submillisecond","noncanonical"})
    void recomputedHashCannotPermitDamagedExecutionReceipt(String field){
        var r=row();Map<String,Object> p=new HashMap<>(PreparedProcessingCodec.decode(PreparedProcessingCodec.encode(receipt),Map.class));
        switch(field){
            case "id","attemptId","preparationId","idempotencyKey"->p.put(field,UUID.randomUUID().toString());
            case "studentId","assessmentId","expectedRevision","revision","objectCount","byteSize"->p.put(field,0);
            case "startedAt"->p.put(field,"bad");
            case "evidenceSha256","inventorySha256"->p.put(field,"bad");
            case "valid-inventory"->p.put("inventorySha256","e".repeat(64));
            case "valid-count"->p.put("objectCount",4);
            case "valid-bytes"->p.put("byteSize",141);
            case "null"->p.put("id",null);
            case "submillisecond"->p.put("startedAt","2024-08-01T00:00:00.002001Z");
            case "noncanonical"->{ }
            default->throw new AssertionError();
        }
        put(r,p);if(field.equals("noncanonical"))r.put("receipt_json",((String)r.get("receipt_json")).replace(",",", "));
        assertThatThrownBy(()->store(r).byScope(1,2,attempt)).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"id","student_id","assessment_id","attempt_id","preparation_id","idempotency_key","expected_revision","revision","started_at","inventory_sha256","object_count","byte_size","state","receipt_sha256","missing","malformed"})
    void rowBindingsAndUnknownStatesMustFailClosed(String field){
        var r=row();switch(field){
            case "id","attempt_id","preparation_id","idempotency_key"->r.put(field,UUID.randomUUID().toString());
            case "student_id","assessment_id","expected_revision","revision","object_count","byte_size"->r.put(field,42);
            case "inventory_sha256"->r.put(field,"e".repeat(64));
            case "started_at"->r.put(field,Timestamp.from(Instant.parse(receipt.startedAt()).plusMillis(1)));
            case "state"->r.put(field,"DELETED");
            case "receipt_sha256"->r.put(field,"f".repeat(64));
            case "missing"->r.put("started_at",null);
            case "malformed"->r.put("receipt_json","{}");
            default->throw new AssertionError();
        }
        assertThatThrownBy(()->store(r).byScope(1,2,attempt)).isInstanceOf(IllegalStateException.class);
    }
    @Test void constructorRejectsVersionOverflowBoundsAndInvalidOriginalIdentity(){
        for(long revision:new long[]{0,-1,9007199254740991L,Long.MAX_VALUE})assertThatThrownBy(()->new PreparedAudioRetirementExecution.Receipt(receipt.id(),1,2,attempt,preparation,key,revision,revision+1,receipt.startedAt(),receipt.evidenceSha256(),receipt.inventorySha256(),3,140)).isInstanceOf(IllegalArgumentException.class);
        for(int count:new int[]{0,-1,500051})assertThatThrownBy(()->new PreparedAudioRetirementExecution.Receipt(receipt.id(),1,2,attempt,preparation,key,1,2,receipt.startedAt(),receipt.evidenceSha256(),receipt.inventorySha256(),count,140)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedAudioRetirementExecution.Receipt(receipt.id(),1,2,attempt,preparation,key,1,2,receipt.startedAt(),receipt.evidenceSha256(),receipt.inventorySha256(),3,45)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedAudioRetirementExecution.Receipt(receipt.id(),1,2,attempt,preparation,key,1,2,receipt.startedAt(),receipt.evidenceSha256(),receipt.inventorySha256(),3,Long.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void bindingMustMatchOriginalPreparationAndMonotonicTime(){
        var late=new PreparedAudioRetirementPreparation.Receipt(preparation,1,2,attempt,prepared.idempotencyKey(),0,1,prepared.audioDueAt(),"2024-08-01T00:00:00.003Z",prepared.evidenceSha256());
        assertThatThrownBy(()->PreparedAudioRetirementExecutionStore.requireBinding(receipt,late)).isInstanceOf(IllegalStateException.class);
        var other=new PreparedAudioRetirementPreparation.Receipt(preparation,1,2,attempt,prepared.idempotencyKey(),1,2,prepared.audioDueAt(),prepared.preparedAt(),prepared.evidenceSha256());
        assertThatThrownBy(()->PreparedAudioRetirementExecutionStore.requireBinding(receipt,other)).isInstanceOf(IllegalStateException.class);
    }
    @Test void emptyLookupDuplicateRowsAndInsertionRemainBounded(){
        var jdbc=mock(JdbcTemplate.class);var store=new PreparedAudioRetirementExecutionStore(jdbc);
        assertThat(store.byScope(1,2,attempt)).isEmpty();
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Map<String,Object>>>any(),any(Object[].class))).thenReturn(List.of(row(),row()));
        assertThatThrownBy(()->store.byScope(1,2,attempt)).isInstanceOf(IllegalStateException.class);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);store.insert(receipt);verify(jdbc).update(contains("INSERT INTO prepared_audio_retirement_execution"),any(Object[].class));
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);assertThatThrownBy(()->store.insert(receipt)).isInstanceOf(IllegalStateException.class);
    }
}
