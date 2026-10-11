package system.assessment.defense.prepared.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import system.assessment.defense.prepared.attempt.PreparedSubmissionService;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.time.Instant;
import java.util.*;

/** Internal immutable audit pairs for original IO, not caller authorization or
 * a cleanup executor. Register in the canonical parent's short transaction,
 * perform IO outside it, close only after IO has actually stopped. An abandoned
 * open event has NO TTL: process/lease expiry alone never proves quiescence.
 * Events contain original identifiers/digests only, never URLs or answer text. */
public final class PreparedOriginalAudioIoJournal {
    public enum Kind { PREVIEW, TRANSCRIBE, ASSEMBLY }
    public record Scope(int actor,int student,int assessment,String attempt,String answer,long declarationRevision,String manifestSha256) {
        public Scope {if(actor<1||student<1||assessment<1||declarationRevision<1)throw new IllegalArgumentException("原录音 IO 范围无效");
            PreparedJobInput.uuid(attempt);PreparedJobInput.uuid(answer);
            if(manifestSha256==null||!manifestSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("原录音 IO 清单无效");}
    }
    /** Opaque in-process completion handle; neither serializable authority nor an HTTP DTO. */
    public static final class Handle {
        private final long id;private final Scope scope;private final Kind kind;
        private Handle(long id,Scope scope,Kind kind){this.id=id;this.scope=scope;this.kind=kind;}
        @Override public String toString(){return "OriginalAudioIo[opaque completion handle]";}
    }
    private record Event(long id,int actor,String action,long version,String answer,String sha,Instant created,Integer student,String attempt) {}
    private final JdbcTemplate jdbc;
    public PreparedOriginalAudioIoJournal(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    public Handle open(Scope scope,Kind kind){
        Objects.requireNonNull(scope);Objects.requireNonNull(kind);transaction();
        new PreparedOriginalAudioReadGuard(jdbc).requireRetained(scope.student(),scope.assessment(),scope.attempt());
        original(scope);
        jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,resource_id,resource_sha256,original_student_id,original_attempt_id) VALUES (?,?,?,?,?,?,?,?)",
            scope.assessment(),scope.actor(),opened(kind),scope.declarationRevision(),scope.answer(),scope.manifestSha256(),scope.student(),scope.attempt());
        long id=Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class));
        if(id<1)throw invalid();return new Handle(id,scope,kind);
    }
    public void close(Handle handle){
        if(handle==null)throw new IllegalArgumentException("原录音 IO 完成依据缺失");transaction();var scope=handle.scope;
        // Closing releases only this completed IO, even after caller revocation.
        // It cannot revive retired content or publish a read capability.
        parent(scope);original(scope);
        var event=jdbc.query("SELECT * FROM prepared_audit WHERE assessment_id=? AND id=? FOR UPDATE",(rs,n)->event(rs),scope.assessment(),handle.id).stream().findFirst().orElseThrow(PreparedOriginalAudioIoJournal::invalid);
        if(event.actor()!=scope.actor()||!event.action().equals(opened(handle.kind))||event.version()!=scope.declarationRevision()
            ||!Objects.equals(event.answer(),scope.answer())||!Objects.equals(event.sha(),scope.manifestSha256())||event.created()==null
            ||!Objects.equals(event.student(),scope.student())||!Objects.equals(event.attempt(),scope.attempt()))throw invalid();
        var existing=jdbc.query("SELECT * FROM prepared_audit WHERE assessment_id=? AND action=? AND resource_version=? FOR UPDATE",(rs,n)->event(rs),scope.assessment(),closed(handle.kind),handle.id);
        if(existing.size()>1)throw invalid();
        if(!existing.isEmpty()){matches(event,existing.getFirst());return;}
        jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,resource_id,resource_sha256,original_student_id,original_attempt_id) VALUES (?,?,?,?,?,?,?,?)",
            scope.assessment(),scope.actor(),closed(handle.kind),handle.id,scope.answer(),scope.manifestSha256(),scope.student(),scope.attempt());
    }
    /** Canonical originals were proved by the caller under the same assessment lock. */
    public boolean inFlight(int student,int assessment,String attempt,List<PreparedSubmissionService.ProcessingSource> sources){
        transaction();var originals=new HashMap<String,PreparedSubmissionService.ProcessingSource>();for(var source:sources)originals.put(source.binding().input().answer(),source);
        var rows=jdbc.query("""
            SELECT * FROM prepared_audit WHERE assessment_id=? AND action LIKE 'ORIGINAL!_%!_IO!_%' ESCAPE '!'
              AND ((original_student_id=? AND original_attempt_id=?) OR original_student_id IS NULL OR original_attempt_id IS NULL)
            ORDER BY id LIMIT 1001 FOR UPDATE
            """,(rs,n)->event(rs),assessment,student,attempt);
        if(rows.size()>1000)throw invalid();var opens=new HashMap<Long,Event>();var ended=new HashSet<Long>();
        for(var row:rows){boolean known=false;for(var kind:Kind.values())if(row.action().equals(opened(kind))||row.action().equals(closed(kind)))known=true;
            if(!known||!Objects.equals(row.student(),student)||!Objects.equals(row.attempt(),attempt))throw invalid();}
        for(var row:rows)if(row.action().endsWith("_OPENED")){
            var source=originals.get(row.answer());if(source==null||row.created()==null||row.actor()<1||row.id()<1
                ||row.version()!=source.binding().input().answerRevision()-1||!Objects.equals(row.sha(),source.binding().input().manifestSha256()))throw invalid();
            opens.put(row.id(),row);
        }
        for(var row:rows)if(row.action().endsWith("_CLOSED")){
            var open=opens.get(row.version());if(open==null||!ended.add(row.version()))throw invalid();matches(open,row);
        }
        return opens.size()!=ended.size();
    }
    private void parent(Scope scope){
        if(jdbc.queryForList("SELECT id FROM assessment WHERE id=? FOR UPDATE",Integer.class,scope.assessment()).size()!=1
            ||jdbc.queryForList("SELECT id FROM prepared_attempt WHERE assessment_id=? AND student_id=? AND id=? FOR UPDATE",String.class,scope.assessment(),scope.student(),scope.attempt()).size()!=1)throw invalid();
    }
    private void original(Scope scope){
        if(jdbc.queryForList("""
            SELECT e.answer_id FROM prepared_answer_end e JOIN prepared_answer a ON a.id=e.answer_id
            WHERE e.assessment_id=? AND e.student_id=? AND e.attempt_id=? AND e.answer_id=?
              AND e.answer_revision=? AND e.manifest_sha256=? AND a.assessment_id=e.assessment_id
              AND a.student_id=e.student_id AND a.attempt_id=e.attempt_id FOR UPDATE
            """,String.class,scope.assessment(),scope.student(),scope.attempt(),scope.answer(),scope.declarationRevision(),scope.manifestSha256()).size()!=1)throw invalid();
    }
    private static Event event(java.sql.ResultSet rs)throws java.sql.SQLException{var created=rs.getTimestamp("created_at");return new Event(rs.getLong("id"),rs.getInt("actor_id"),rs.getString("action"),rs.getLong("resource_version"),rs.getString("resource_id"),rs.getString("resource_sha256"),created==null?null:created.toInstant(),rs.getObject("original_student_id",Integer.class),rs.getString("original_attempt_id"));}
    private static void matches(Event open,Event close){if(close.actor()!=open.actor()||close.version()!=open.id()||close.id()<=open.id()
        ||!close.action().equals(open.action().replace("_OPENED","_CLOSED"))||!Objects.equals(close.answer(),open.answer())||!Objects.equals(close.sha(),open.sha())
        ||close.created()==null||close.created().isBefore(open.created())||!Objects.equals(close.student(),open.student())||!Objects.equals(close.attempt(),open.attempt()))throw invalid();}
    private static String opened(Kind kind){return "ORIGINAL_"+kind+"_IO_OPENED";}
    private static String closed(Kind kind){return "ORIGINAL_"+kind+"_IO_CLOSED";}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("原录音 IO 登记须在原事务中核验");}
    private static IllegalStateException invalid(){return new IllegalStateException("原录音 IO 登记无法核验，原数据保持保留");}
}
