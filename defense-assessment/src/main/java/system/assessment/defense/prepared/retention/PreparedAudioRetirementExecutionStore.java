package system.assessment.defense.prepared.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.processing.PreparedProcessingCodec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static system.assessment.defense.prepared.retention.PreparedAudioRetirementExecution.*;

/** Same original parent transaction as preparation/protection. No object locator,
 * answer content, reversible state, expiry lease or storage authority is persisted. */
final class PreparedAudioRetirementExecutionStore {
    private final JdbcTemplate jdbc;
    PreparedAudioRetirementExecutionStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    Optional<Receipt> byScope(int student,int assessment,String attempt){return read(student,assessment,attempt,null);}
    Optional<Receipt> byKey(int student,int assessment,String attempt,String key){PreparedJobInput.uuid(key);return read(student,assessment,attempt,key);}
    private Optional<Receipt> read(int student,int assessment,String attempt,String key){
        if(student<1||assessment<1)throw new IllegalArgumentException("原录音退出范围无效");PreparedJobInput.uuid(attempt);
        String sql="SELECT * FROM prepared_audio_retirement_execution WHERE assessment_id=? AND student_id=? AND attempt_id=?"+(key==null?"":" AND idempotency_key=?")+" FOR UPDATE";
        Object[] args=key==null?new Object[]{assessment,student,attempt}:new Object[]{assessment,student,attempt,key};
        var rows=jdbc.query(sql,(rs,n)->{
            Map<String,Object> r=new HashMap<>();for(String c:List.of("id","attempt_id","preparation_id","idempotency_key","inventory_sha256","state","receipt_json","receipt_sha256"))r.put(c,rs.getString(c));
            r.put("student_id",rs.getInt("student_id"));r.put("assessment_id",rs.getInt("assessment_id"));r.put("expected_revision",rs.getLong("expected_revision"));r.put("revision",rs.getLong("revision"));r.put("started_at",rs.getTimestamp("started_at"));r.put("object_count",rs.getInt("object_count"));r.put("byte_size",rs.getLong("byte_size"));return r;
        },args);
        if(rows.size()>1)throw invalid();if(rows.isEmpty())return Optional.empty();var receipt=verify(rows.getFirst());
        if(receipt.studentId()!=student||receipt.assessmentId()!=assessment||!receipt.attemptId().equals(attempt)||key!=null&&!key.equals(receipt.idempotencyKey()))throw invalid();
        return Optional.of(receipt);
    }
    void insert(Receipt receipt){
        if(jdbc.update("""
            INSERT INTO prepared_audio_retirement_execution(id,assessment_id,student_id,attempt_id,preparation_id,idempotency_key,
                expected_revision,revision,started_at,inventory_sha256,object_count,byte_size,receipt_json,receipt_sha256,state)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'INTENT')
            """,receipt.id(),receipt.assessmentId(),receipt.studentId(),receipt.attemptId(),receipt.preparationId(),receipt.idempotencyKey(),receipt.expectedRevision(),receipt.revision(),Timestamp.from(Instant.parse(receipt.startedAt())),receipt.inventorySha256(),receipt.objectCount(),receipt.byteSize(),PreparedProcessingCodec.encode(receipt),hash(receipt))!=1)throw invalid();
    }
    static void requireBinding(Receipt execution,PreparedAudioRetirementPreparation.Receipt preparation){
        if(!execution.preparationId().equals(preparation.id())||execution.studentId()!=preparation.studentId()||execution.assessmentId()!=preparation.assessmentId()
            ||!execution.attemptId().equals(preparation.attemptId())||execution.expectedRevision()!=preparation.revision()
            ||!execution.evidenceSha256().equals(preparation.evidenceSha256())||Instant.parse(execution.startedAt()).isBefore(Instant.parse(preparation.preparedAt())))throw invalid();
    }
    private Receipt verify(Map<String,Object> row){
        try{
            var r=PreparedProcessingCodec.decode((String)row.get("receipt_json"),Receipt.class);
            if(!"INTENT".equals(row.get("state"))||!r.id().equals(row.get("id"))||r.studentId()!=((Number)row.get("student_id")).intValue()
                ||r.assessmentId()!=((Number)row.get("assessment_id")).intValue()||!r.attemptId().equals(row.get("attempt_id"))||!r.preparationId().equals(row.get("preparation_id"))
                ||!r.idempotencyKey().equals(row.get("idempotency_key"))||r.expectedRevision()!=((Number)row.get("expected_revision")).longValue()||r.revision()!=((Number)row.get("revision")).longValue()
                ||!Instant.parse(r.startedAt()).equals(((Timestamp)row.get("started_at")).toInstant())||!r.inventorySha256().equals(row.get("inventory_sha256"))
                ||r.objectCount()!=((Number)row.get("object_count")).intValue()||r.byteSize()!=((Number)row.get("byte_size")).longValue()
                ||!PreparedProcessingCodec.encode(r).equals(row.get("receipt_json"))||!hash(r).equals(row.get("receipt_sha256")))throw invalid();
            return r;
        }catch(IllegalArgumentException|NullPointerException|ClassCastException|java.time.DateTimeException failure){throw invalid();}
    }
    private static String hash(Object value){return PreparedJobInput.digest(PreparedProcessingCodec.encode(value));}
    private static IllegalStateException invalid(){return new IllegalStateException("原录音不可逆退出代次无法核验，禁止取消、恢复读取或执行删除");}
}
