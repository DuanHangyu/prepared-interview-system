package system.assessment.defense.prepared.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.processing.PreparedProcessingCodec;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import static system.assessment.defense.prepared.retention.PreparedAudioRetirementPreparation.*;

/** Caller holds the same original assessment/guard transaction. No content or
 * object key is retained here; immutable receipts are distinct from current state. */
final class PreparedAudioRetirementPreparationStore {
    private final JdbcTemplate jdbc;
    PreparedAudioRetirementPreparationStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    Optional<View> byKey(int student,int assessment,String attempt,String key){return read("idempotency_key",student,assessment,attempt,key);}
    Optional<View> byId(int student,int assessment,String attempt,String id){return read("id",student,assessment,attempt,id);}
    private Optional<View> read(String column,int student,int assessment,String attempt,String value){
        return rows("SELECT * FROM prepared_audio_retirement_preparation WHERE assessment_id=? AND student_id=? AND attempt_id=? AND "+column+"=? FOR UPDATE",assessment,student,attempt,value).stream().map(this::verify).findFirst();
    }
    Optional<View> latest(int student,int assessment,String attempt){
        return rows("SELECT * FROM prepared_audio_retirement_preparation WHERE assessment_id=? AND student_id=? AND attempt_id=? ORDER BY revision DESC LIMIT 1 FOR UPDATE",assessment,student,attempt).stream().map(this::verify).findFirst();
    }
    private List<Map<String,Object>> rows(String sql,Object... args){
        // MySQL DATETIME getObject() is LocalDateTime, not Timestamp. Use the
        // same explicit JDBC timestamp conversion as original attempts/guards.
        return jdbc.query(sql,(rs,n)->{
            Map<String,Object> row=new HashMap<>();
            for(String column:List.of("id","attempt_id","idempotency_key","state","receipt_json","receipt_sha256","cancellation_json","cancellation_sha256"))row.put(column,rs.getString(column));
            row.put("student_id",rs.getInt("student_id"));row.put("assessment_id",rs.getInt("assessment_id"));
            row.put("expected_revision",rs.getLong("expected_revision"));row.put("revision",rs.getLong("revision"));row.put("prepared_at",rs.getTimestamp("prepared_at"));return row;
        },args);
    }
    void requireConsistent(int student,int assessment,String attempt,Optional<PreparedRetentionProtectionStore.Guard> guard){
        var history=latest(student,assessment,attempt);var execution=new PreparedAudioRetirementExecutionStore(jdbc).byScope(student,assessment,attempt);
        if(history.isEmpty()){if(execution.isPresent())throw invalid();return;}
        var state=guard.orElseThrow(PreparedAudioRetirementPreparationStore::invalid);var view=history.orElseThrow();var receipt=view.preparation();
        if("STAGED".equals(view.state())){
            execution.ifPresent(value->PreparedAudioRetirementExecutionStore.requireBinding(value,receipt));
            long revision=execution.map(PreparedAudioRetirementExecution.Receipt::revision).orElse(receipt.revision());
            String updated=execution.map(PreparedAudioRetirementExecution.Receipt::startedAt).orElse(receipt.preparedAt());
            if(state.revision()!=revision||state.held()||!"RETIRING".equals(state.audio())||!"RETAINED".equals(state.results())||!state.updated().equals(updated))throw invalid();
        }else if(execution.isPresent()||state.revision()<view.cancellation().revision()||Instant.parse(state.updated()).isBefore(Instant.parse(view.cancellation().cancelledAt())))throw invalid();
        // A damaged/latest pointer cannot hide another unclosed preparation.
        var active=jdbc.queryForList("SELECT id FROM prepared_audio_retirement_preparation WHERE assessment_id=? AND student_id=? AND attempt_id=? AND state='STAGED' ORDER BY revision LIMIT 2 FOR UPDATE",String.class,assessment,student,attempt);
        if(active.size()>("STAGED".equals(view.state())?1:0)||"STAGED".equals(view.state())&&(active.size()!=1||!active.getFirst().equals(receipt.id())))throw invalid();
    }
    void insert(Receipt receipt){
        jdbc.update("""
            INSERT INTO prepared_audio_retirement_preparation(id,assessment_id,student_id,attempt_id,idempotency_key,
                expected_revision,revision,prepared_at,receipt_json,receipt_sha256,state)
            VALUES (?,?,?,?,?,?,?,?,?,?,'STAGED')
            """,receipt.id(),receipt.assessmentId(),receipt.studentId(),receipt.attemptId(),receipt.idempotencyKey(),receipt.expectedRevision(),receipt.revision(),Timestamp.from(Instant.parse(receipt.preparedAt())),PreparedProcessingCodec.encode(receipt),hash(receipt));
    }
    void cancel(Cancellation receipt){
        if(jdbc.update("""
            UPDATE prepared_audio_retirement_preparation p SET state='CANCELLED',cancellation_json=?,cancellation_sha256=?
            WHERE p.assessment_id=? AND p.student_id=? AND p.attempt_id=? AND p.id=? AND p.state='STAGED' AND p.revision=?
              AND NOT EXISTS(SELECT 1 FROM prepared_audio_retirement_execution e WHERE e.preparation_id=p.id
                AND e.assessment_id=p.assessment_id AND e.student_id=p.student_id AND e.attempt_id=p.attempt_id)
            """,PreparedProcessingCodec.encode(receipt),hash(receipt),receipt.assessmentId(),receipt.studentId(),receipt.attemptId(),receipt.preparationId(),receipt.expectedRevision())!=1)throw invalid();
    }
    private View verify(Map<String,Object> row){
        try{
            var receipt=PreparedProcessingCodec.decode((String)row.get("receipt_json"),Receipt.class);
            PreparedJobInput.uuid(receipt.id());PreparedJobInput.uuid(receipt.attemptId());new Command(receipt.idempotencyKey(),receipt.expectedRevision());
            Instant prepared=Instant.parse(receipt.preparedAt()),due=Instant.parse(receipt.audioDueAt());
            if(receipt.studentId()<1||receipt.assessmentId()<1||receipt.expectedRevision()>=9007199254740990L||receipt.revision()!=receipt.expectedRevision()+1||prepared.isBefore(due)
                ||receipt.evidenceSha256()==null||!receipt.evidenceSha256().matches("[a-f0-9]{64}")
                ||!receipt.id().equals(row.get("id"))||!receipt.attemptId().equals(row.get("attempt_id"))
                ||receipt.studentId()!=((Number)row.get("student_id")).intValue()||receipt.assessmentId()!=((Number)row.get("assessment_id")).intValue()
                ||!receipt.idempotencyKey().equals(row.get("idempotency_key"))||receipt.expectedRevision()!=((Number)row.get("expected_revision")).longValue()
                ||receipt.revision()!=((Number)row.get("revision")).longValue()||!prepared.equals(((Timestamp)row.get("prepared_at")).toInstant())
                ||!hash(receipt).equals(row.get("receipt_sha256"))||!PreparedProcessingCodec.encode(receipt).equals(row.get("receipt_json")))throw invalid();
            String state=(String)row.get("state");Cancellation cancellation=null;
            if("STAGED".equals(state)){
                if(row.get("cancellation_json")!=null||row.get("cancellation_sha256")!=null)throw invalid();
            }else if("CANCELLED".equals(state)){
                cancellation=PreparedProcessingCodec.decode((String)row.get("cancellation_json"),Cancellation.class);new Command(cancellation.idempotencyKey(),cancellation.expectedRevision());
                if(!cancellation.preparationId().equals(receipt.id())||!cancellation.attemptId().equals(receipt.attemptId())
                    ||cancellation.studentId()!=receipt.studentId()||cancellation.assessmentId()!=receipt.assessmentId()
                    ||cancellation.expectedRevision()!=receipt.revision()||cancellation.revision()!=receipt.revision()+1
                    ||Instant.parse(cancellation.cancelledAt()).isBefore(prepared)||!hash(cancellation).equals(row.get("cancellation_sha256"))
                    ||!PreparedProcessingCodec.encode(cancellation).equals(row.get("cancellation_json")))throw invalid();
            }else throw invalid();
            return new View(state,receipt,cancellation);
        }catch(IllegalArgumentException|NullPointerException|ClassCastException|java.time.DateTimeException failure){throw invalid();}
    }
    private static String hash(Object value){return PreparedJobInput.digest(PreparedProcessingCodec.encode(value));}
    private static IllegalStateException invalid(){return new IllegalStateException("原录音清理准备或恢复绑定无法核验，不能恢复读取或执行删除");}
}
