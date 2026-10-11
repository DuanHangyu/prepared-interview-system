package system.assessment.defense.prepared.jobs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.math.BigDecimal;
import java.util.*;
import static system.assessment.defense.prepared.jobs.PreparedJobInput.*;

/** Internal persistence boundary; callers must authorize the resource before enqueue/get.
 * No external API calls occur in these transactions. Lock order is job -> budget -> call.
 */
@Repository
public class PreparedJobLedger {
    public enum Kind { QUESTIONS, TTS, ASSEMBLE, ANSWER_MEDIA, TRANSCRIBE, SCORE }
    public enum State { QUEUED, RUNNING, RETRY_WAIT, SUCCEEDED, NEEDS_REVIEW }
    public static final String ANSWER_MEDIA_UPLOAD_WINDOW_EXPIRED="ANSWER_MEDIA_UPLOAD_WINDOW_EXPIRED";
    public record Job(String id, int assessmentId, int actorId, Kind kind, long inputVersion,
                      String inputJson, String outputJson, State state, int attempts) {}
    public record Lease(Job job, long generation, String worker) {}
    public record Budget(String scope, BigDecimal cap, BigDecimal pauseAt, BigDecimal spent,
                         BigDecimal reserved, boolean paused) {}
    public record Decision(boolean approved, String reason) {}
    public record AsyncTask(String jobId,String callId,String inputJson,String taskId,String resultJson,java.time.Instant recoveryDeadline,int queryFailures,int resultFailures) {}
    public record ScoreCall(String jobId,String callId,String inputJson,String replyJson,int publicationFailures) {}
    public enum AsyncRetry { STALE, DEFERRED, EXHAUSTED }
    private record Call(String id, String job, long generation, String scope, BigDecimal bound,
                        BigDecimal actual, String state, String providerId) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private static final RowMapper<Job> JOB = (rs, n) -> new Job(rs.getString("id"), rs.getInt("assessment_id"),
            rs.getInt("created_by"), Kind.valueOf(rs.getString("kind")), rs.getLong("input_version"),
            rs.getString("input_json"), rs.getString("output_json"), State.valueOf(rs.getString("state")), rs.getInt("attempts"));
    private static final RowMapper<Call> CALL = (rs, n) -> new Call(rs.getString("id"), rs.getString("job_id"),
            rs.getLong("lease_generation"), rs.getString("scope_id"), rs.getBigDecimal("reserved_cny"),
            rs.getBigDecimal("actual_cny"), rs.getString("state"), rs.getString("provider_request_id"));

    public PreparedJobLedger(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager);
    }
    public Job enqueue(int actor, int assessment, Kind kind, long version, String key, String payload) {
        return enqueueAfter(actor,assessment,kind,version,key,payload,List.of());
    }
    /** Dependency edges are created atomically with a new command and are immutable.
     * Kind ordering prevents cycles; the payload separately retains semantic audio-part order.
     * Standalone commands retain their original request digest for historical compatibility. */
    public Job enqueueAfter(int actor,int assessment,Kind kind,long version,String key,String payload,List<String> dependencies) {
        if (actor < 1 || assessment < 1 || kind == null || version < 0) throw new IllegalArgumentException("任务归属或版本无效");
        uuid(key); String input = json(payload, 65536);
        if(dependencies==null || dependencies.size()>50)throw new IllegalArgumentException("任务依赖无效");
        List<String> children=dependencies.stream().map(PreparedJobInput::uuid).sorted().toList();
        if(new HashSet<>(children).size()!=children.size())throw new IllegalArgumentException("任务依赖重复");
        Set<Kind> dependencyKinds=children.isEmpty()?Set.of():switch(kind){
            case ASSEMBLE->Set.of(Kind.TTS);case TRANSCRIBE->Set.of(Kind.ASSEMBLE,Kind.ANSWER_MEDIA);case SCORE->Set.of(Kind.TRANSCRIBE);
            default->throw new IllegalArgumentException("任务依赖顺序无效");};
        String hash = digest(kind.name() + ":" + version + ":" + input+(children.isEmpty()?"":":after:"+String.join(",",children)));
        return transaction.execute(status -> {
            var parent = jdbc.queryForList("SELECT id FROM assessment WHERE id=? AND deleted=0 FOR UPDATE", Integer.class, assessment);
            if (parent.isEmpty()) throw new BusinessException(403, "考核不存在或无权限");
            var existing = jdbc.query("SELECT * FROM prepared_job WHERE assessment_id=? AND created_by=? AND kind=? AND idempotency_key=?",
                    JOB, assessment, actor, kind.name(), key);
            if (!existing.isEmpty()) {
                String old = jdbc.queryForObject("SELECT request_sha256 FROM prepared_job WHERE id=?", String.class, existing.getFirst().id());
                if (!hash.equals(old)) throw conflict(); return existing.getFirst();
            }
            Kind firstKind=null;
            for(String childId:children) {
                var child=get(childId).orElseThrow(()->new BusinessException(403,"任务依赖不存在或归属无效"));
                if(child.assessmentId()!=assessment || child.actorId()!=actor || !dependencyKinds.contains(child.kind())
                        || (firstKind!=null&&child.kind()!=firstKind) || (child.kind()==Kind.ANSWER_MEDIA&&children.size()!=1))
                    throw new BusinessException(403,"任务依赖不存在或归属无效");
                firstKind=child.kind();
            }
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO prepared_job(id,assessment_id,created_by,kind,input_version,idempotency_key,request_sha256,input_json)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, id, assessment, actor, kind.name(), version, key, hash, input);
            for(String child:children)jdbc.update("INSERT INTO prepared_job_dependency(job_id,depends_on_id,assessment_id,created_by) VALUES (?,?,?,?)",id,child,assessment,actor);
            return get(id).orElseThrow();
        });
    }
    public Optional<Job> get(String id) {
        return jdbc.query("SELECT * FROM prepared_job WHERE id=?", JOB, uuid(id)).stream().findFirst();
    }
    /** Current committed read for an already-authorized, parent-locked short proof transaction.
     * A prior nonlocking permission read may have established an older REPEATABLE READ view.
     * Never combine that old view with a current submission/answer and invent missing outbox.
     * This is not authorization, a lease, or permission to hold locks during external IO. */
    public Optional<Job> getForShare(String id) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("当前任务证明需要短事务");
        return jdbc.query("SELECT * FROM prepared_job WHERE id=? FOR SHARE", JOB, uuid(id)).stream().findFirst();
    }
    /** Public projection of one specifically classified, non-paid media pause. Other
     * NEEDS_REVIEW errors remain manual; callers authorize the original resource first. */
    public boolean answerMediaWaitingForApproval(String id){
        // Read the reason from the same current committed view as the checked media/job.
        // Nonlocking COUNT could retain an older QUEUED snapshot after a window transition.
        return jdbc.queryForList("SELECT id FROM prepared_job WHERE id=? AND kind='ANSWER_MEDIA' AND state='NEEDS_REVIEW' AND error_code=? FOR SHARE",String.class,uuid(id),ANSWER_MEDIA_UPLOAD_WINDOW_EXPIRED).size()==1;
    }
    /** Internal only. The original-authorized media service must first verify a fresh
     * teacher grant, original end/bytes and manifest. No paid job or uncertain IO is reset.
     * The same job and input survive; a generation fence rejects every previous lease. */
    public boolean resumeAnswerMediaAfterApproval(Job expected){
        if(expected==null||expected.kind()!=Kind.ANSWER_MEDIA)throw new IllegalArgumentException("原录音核验任务无效");uuid(expected.id());
        return Boolean.TRUE.equals(transaction.execute(status->{
            var found=jdbc.query("SELECT * FROM prepared_job WHERE id=? FOR UPDATE",JOB,expected.id());
            if(found.isEmpty()||!found.getFirst().equals(expected)||expected.state()!=State.NEEDS_REVIEW)return false;
            String hash=digest(Kind.ANSWER_MEDIA.name()+":"+expected.inputVersion()+":"+json(expected.inputJson(),65536));
            return jdbc.update("""
                    UPDATE prepared_job SET state='QUEUED',attempts=0,lease_generation=lease_generation+1,
                    error_code=NULL,next_run_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3)
                    WHERE id=? AND kind='ANSWER_MEDIA' AND state='NEEDS_REVIEW' AND error_code=?
                    AND request_sha256=? AND output_json IS NULL AND external_sent=0
                    AND lease_owner IS NULL AND lease_until IS NULL
                    AND NOT EXISTS(SELECT 1 FROM prepared_model_call c WHERE c.job_id=prepared_job.id)
                    AND NOT EXISTS(SELECT 1 FROM prepared_job_dependency d WHERE d.job_id=prepared_job.id)
                    """,expected.id(),ANSWER_MEDIA_UPLOAD_WINDOW_EXPIRED,hash)==1;
        }));
    }
    public Optional<Lease> claim(Kind kind, String worker, int leaseSeconds) {
        if (kind == null) throw new IllegalArgumentException("任务类型无效");
        worker(worker); leaseSeconds(leaseSeconds);
        return transaction.execute(status -> {
            var failedParents=jdbc.queryForList("""
                    SELECT j.id FROM prepared_job j WHERE j.kind=? AND j.state IN ('QUEUED','RETRY_WAIT')
                    AND EXISTS(SELECT 1 FROM prepared_job_dependency d JOIN prepared_job c ON c.id=d.depends_on_id
                               WHERE d.job_id=j.id AND c.state='NEEDS_REVIEW')
                    ORDER BY j.next_run_at,j.id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """,String.class,kind.name());
            for(String parent:failedParents)jdbc.update("""
                    UPDATE prepared_job SET state='NEEDS_REVIEW',error_code='DEPENDENCY_NEEDS_REVIEW',
                    lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?
                    """,parent);
            // One recovery per poll avoids acquiring multiple budget scopes in opposite orders.
            // Sent requests need reconciliation, never automatic paid replay.
            var expired = jdbc.queryForList("""
                    SELECT id FROM prepared_job WHERE kind=? AND state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(3)
                    ORDER BY lease_until,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, String.class, kind.name());
            for (String id : expired) {
                if (knownRecovery(id)) {
                    jdbc.update("UPDATE prepared_job SET state='RETRY_WAIT',lease_owner=NULL,lease_until=NULL,next_run_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",id);
                    continue; // Known task: GET only; no uncertain charge or another paid attempt.
                }
                var sent = jdbc.queryForObject("SELECT external_sent FROM prepared_job WHERE id=?", Boolean.class, id);
                int attempts = Objects.requireNonNull(jdbc.queryForObject("SELECT attempts FROM prepared_job WHERE id=?", Integer.class, id));
                if (Boolean.TRUE.equals(sent)) {
                    var calls = jdbc.query("SELECT * FROM prepared_model_call WHERE job_id=? AND state='SENT'", CALL, id);
                    for (Call call : calls) uncertainWithinTransaction(call);
                } else {
                    // A pre-dispatch reservation still represents a bounded pending call until explicitly cancelled.
                    var calls = jdbc.query("SELECT * FROM prepared_model_call WHERE job_id=? AND state='RESERVED'", CALL, id);
                    for (Call call : calls) cancelWithinTransaction(call);
                }
                jdbc.update("""
                        UPDATE prepared_job SET state=?,error_code=?,lease_owner=NULL,lease_until=NULL,
                        next_run_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 2 SECOND),updated_at=CURRENT_TIMESTAMP(3) WHERE id=?
                        """, Boolean.TRUE.equals(sent) || attempts >= 3 ? "NEEDS_REVIEW" : "RETRY_WAIT",
                        Boolean.TRUE.equals(sent) ? "AMBIGUOUS_CALL" : "WORKER_LEASE_EXPIRED", id);
            }
            var jobs = jdbc.query("""
                    SELECT j.* FROM prepared_job j WHERE j.kind=? AND j.state IN ('QUEUED','RETRY_WAIT')
                    AND (j.attempts<3 OR EXISTS(SELECT 1 FROM prepared_async_model_task a WHERE a.job_id=j.id AND a.task_id IS NOT NULL)
                         OR EXISTS(SELECT 1 FROM prepared_score_call s JOIN prepared_model_call c ON c.id=s.call_id WHERE s.job_id=j.id AND s.reply_json IS NOT NULL AND c.state='SETTLED'))
                    AND j.next_run_at<=CURRENT_TIMESTAMP(3)
                    AND NOT EXISTS(SELECT 1 FROM prepared_job_dependency d JOIN prepared_job c ON c.id=d.depends_on_id
                                   WHERE d.job_id=j.id AND c.state<>'SUCCEEDED')
                    ORDER BY j.next_run_at,j.id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, JOB, kind.name());
            if (jobs.isEmpty()) return Optional.empty();
            Job job = jobs.getFirst();
            boolean polling=knownRecovery(job.id());
            jdbc.update("""
                    UPDATE prepared_job SET state='RUNNING',attempts=attempts+?,lease_generation=lease_generation+1,
                    lease_owner=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL ? SECOND),external_sent=?,
                    error_code=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?
                    """, polling?0:1,worker, leaseSeconds,polling, job.id());
            long generation = Objects.requireNonNull(jdbc.queryForObject("SELECT lease_generation FROM prepared_job WHERE id=?", Long.class, job.id()));
            return Optional.of(new Lease(get(job.id()).orElseThrow(), generation, worker));
        });
    }
    public boolean heartbeat(Lease lease, int seconds) {
        validateLease(lease); leaseSeconds(seconds);
        return jdbc.update("""
                UPDATE prepared_job SET lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL ? SECOND),updated_at=CURRENT_TIMESTAMP(3)
                WHERE id=? AND state='RUNNING' AND lease_generation=? AND lease_owner=? AND lease_until>CURRENT_TIMESTAMP(3)
                """, seconds, lease.job().id(), lease.generation(), lease.worker()) == 1;
    }
    public boolean succeed(Lease lease, String output) {
        validateLease(lease); String canonical = json(output, 262144);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockActive(lease) || unresolved(lease)) return false;
            jdbc.update("""
                    UPDATE prepared_job SET state='SUCCEEDED',output_json=?,lease_owner=NULL,lease_until=NULL,
                    error_code=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?
                    """, canonical, lease.job().id()); return true;
        }));
    }
    public boolean fail(Lease lease, String code, boolean retryable) {
        validateLease(lease);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,47}")) throw new IllegalArgumentException("任务错误标识无效");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockActive(lease)) return false;
            Job current = get(lease.job().id()).orElseThrow();
            boolean retry = retryable && current.attempts() < 3 && !unresolved(lease);
            jdbc.update("""
                    UPDATE prepared_job SET state=?,error_code=?,lease_owner=NULL,lease_until=NULL,
                    next_run_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL ? SECOND),updated_at=CURRENT_TIMESTAMP(3) WHERE id=?
                    """, retry ? "RETRY_WAIT" : "NEEDS_REVIEW", code, 1 << current.attempts(), current.id()); return true;
        }));
    }
    /** Internal supersession fence: cannot cancel a running, sent or uncertain paid request. */
    public boolean cancelQueuedUnsent(String id){
        uuid(id);return Boolean.TRUE.equals(transaction.execute(status->{
            var found=jdbc.query("SELECT * FROM prepared_job WHERE id=? FOR UPDATE",JOB,id);
            if(found.isEmpty())return false;var job=found.getFirst();
            if(job.state()!=State.QUEUED && job.state()!=State.RETRY_WAIT)return false;
            int unresolved=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_model_call WHERE job_id=? AND state IN ('RESERVED','SENT','UNCERTAIN')",Integer.class,id));
            if(unresolved!=0 || Boolean.TRUE.equals(jdbc.queryForObject("SELECT external_sent FROM prepared_job WHERE id=?",Boolean.class,id)))return false;
            return jdbc.update("UPDATE prepared_job SET state='NEEDS_REVIEW',error_code='SUPERSEDED_BEFORE_SEND',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",id)==1;
        }));
    }
    public void initializeBudget(String id, BigDecimal cap, BigDecimal pause) {
        scope(id); money(cap, true); money(pause, true);
        if (cap.compareTo(new BigDecimal("100")) > 0 || pause.compareTo(new BigDecimal("80")) > 0 || pause.compareTo(cap) > 0)
            throw new IllegalArgumentException("本轮预算不能超过 100 元，暂停阈值不能超过 80 元");
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO prepared_budget(scope_id,cap_cny,pause_cny) VALUES (?,?,?)
                    ON DUPLICATE KEY UPDATE scope_id=VALUES(scope_id)
                    """, id, cap, pause);
            Budget existing = lockBudget(id);
            if (existing.cap().compareTo(cap) != 0 || existing.pauseAt().compareTo(pause) != 0) throw conflict();
        });
    }
    public Budget budget(String id) { return readBudget(scope(id), false); }
    /** Read-only UI probe; never initializes or resets the shared cost ledger. */
    public Optional<Budget> initializedBudget(String id) {
        scope(id);
        return jdbc.query("SELECT * FROM prepared_budget WHERE scope_id=?",(rs,n)->new Budget(rs.getString("scope_id"),
                rs.getBigDecimal("cap_cny"),rs.getBigDecimal("pause_cny"),rs.getBigDecimal("spent_cny"),rs.getBigDecimal("reserved_cny"),rs.getBoolean("paused")),id).stream().findFirst();
    }
    public Decision reserve(Lease lease, String scopeId, String callId, BigDecimal maxCost) {
        validateLease(lease); scope(scopeId); uuid(callId); money(maxCost, false);
        return transaction.execute(status -> {
            if (!lockActive(lease)) return new Decision(false, "STALE_LEASE");
            if (knownAsync(lease.job().id())) return new Decision(false,"ASYNC_QUERY_ONLY");
            if (!jdbc.queryForList("SELECT job_id FROM prepared_score_call WHERE job_id=?",String.class,lease.job().id()).isEmpty())return new Decision(false,"SCORE_REPLY_ONLY");
            Budget budget = lockBudget(scopeId);
            var same = jdbc.query("SELECT * FROM prepared_model_call WHERE id=? FOR UPDATE", CALL, callId);
            if (!same.isEmpty()) {
                Call call = same.getFirst();
                if (!call.job().equals(lease.job().id()) || call.generation() != lease.generation()
                        || !call.scope().equals(scopeId) || call.bound().compareTo(maxCost) != 0) throw conflict();
                return new Decision(call.state().equals("RESERVED"), "EXISTING_CALL");
            }
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM prepared_model_call WHERE job_id=? AND lease_generation=?",
                    Integer.class, lease.job().id(), lease.generation());
            if (count != null && count > 0) throw conflict();
            if (budget.paused()) return new Decision(false, "BUDGET_PAUSED");
            BigDecimal prospective = budget.spent().add(budget.reserved()).add(maxCost);
            if (prospective.compareTo(budget.pauseAt()) >= 0 || prospective.compareTo(budget.cap()) > 0) {
                pauseBudget(scopeId, "PAUSE_THRESHOLD"); return new Decision(false, "PAUSE_THRESHOLD");
            }
            jdbc.update("INSERT INTO prepared_model_call(id,job_id,lease_generation,scope_id,reserved_cny) VALUES (?,?,?,?,?)",
                    callId, lease.job().id(), lease.generation(), scopeId, maxCost);
            jdbc.update("UPDATE prepared_budget SET reserved_cny=reserved_cny+?,updated_at=CURRENT_TIMESTAMP(3) WHERE scope_id=?", maxCost, scopeId);
            event(scopeId, callId, "RESERVED", maxCost); return new Decision(true, "RESERVED");
        });
    }
    /** A durable dispatch fence. Returning false must NEVER cause another provider request. */
    public boolean markDispatched(Lease lease, String callId) {
        validateLease(lease); uuid(callId);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!lockActive(lease)) return false;
            Call call = lockCallWithBudget(callId);
            if (!call.job().equals(lease.job().id()) || call.generation() != lease.generation()) throw conflict();
            if (!call.state().equals("RESERVED") || budget(call.scope()).paused()) return false;
            jdbc.update("UPDATE prepared_model_call SET state='SENT',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?", callId);
            jdbc.update("UPDATE prepared_job SET external_sent=TRUE WHERE id=?", lease.job().id());
            event(call.scope(), call.id(), "SENT", call.bound()); return true;
        }));
    }
    /** Atomically persist the credential-free original snapshot and paid dispatch fence BEFORE POST.
     * Returning false NEVER authorizes another send. Only whole-file TRANSCRIBE is async today. */
    public boolean dispatchAsync(Lease lease,String callId,String snapshot) {
        validateLease(lease);uuid(callId);String input=json(snapshot,65536);
        if(lease.job().kind()!=Kind.TRANSCRIBE)throw new IllegalArgumentException("异步供应商任务类型无效");
        return Boolean.TRUE.equals(transaction.execute(s->{
            if(!lockActive(lease))return false;
            var call=lockCallWithBudget(callId);
            if(!call.job().equals(lease.job().id())||call.generation()!=lease.generation())throw conflict();
            if(!call.state().equals("RESERVED")||budget(call.scope()).paused())return false;
            if(!jdbc.queryForList("SELECT job_id FROM prepared_async_model_task WHERE job_id=?",String.class,call.job()).isEmpty())throw conflict();
            jdbc.update("INSERT INTO prepared_async_model_task(job_id,call_id,input_json,input_sha256,recovery_deadline) VALUES (?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 23 HOUR))",call.job(),callId,input,digest(input));
            jdbc.update("UPDATE prepared_model_call SET state='SENT',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",callId);
            jdbc.update("UPDATE prepared_job SET external_sent=TRUE WHERE id=?",call.job());event(call.scope(),callId,"SENT",call.bound());return true;
        }));
    }
    /** The original submitter may durably attach a late known ID even after its lease expired.
     * It cannot change the ID, result or any newer lease, or unpause an uncertain-charge budget. */
    public void acceptAsync(Lease original,String callId,String taskId) {
        validateLease(original);uuid(callId);uuid(taskId);
        transaction.executeWithoutResult(s->{
            var rows=jdbc.queryForList("SELECT state,error_code FROM prepared_job WHERE id=? FOR UPDATE",original.job().id());
            if(rows.isEmpty())throw conflict();var job=rows.getFirst();var call=lockCallWithBudget(callId);
            if(!call.job().equals(original.job().id())||call.generation()!=original.generation()||!Set.of("SENT","UNCERTAIN","SETTLED").contains(call.state()))throw conflict();
            var saved=async(call.job()).orElseThrow(PreparedJobLedger::conflict);
            if(!saved.callId().equals(callId))throw conflict();
            if(saved.taskId()!=null){if(!saved.taskId().equals(taskId))throw conflict();return;}
            if(!"RUNNING".equals(job.get("state"))&&!("NEEDS_REVIEW".equals(job.get("state"))&&Set.of("AMBIGUOUS_CALL","ASR_SUBMIT_OUTCOME_UNKNOWN").contains(job.get("error_code"))))throw conflict();
            jdbc.update("UPDATE prepared_async_model_task SET task_id=?,phase='POLLING',updated_at=CURRENT_TIMESTAMP(3) WHERE job_id=?",taskId,call.job());
            if("NEEDS_REVIEW".equals(job.get("state")))jdbc.update("UPDATE prepared_job SET state='RETRY_WAIT',error_code=NULL,next_run_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",call.job());
        });
    }
    public Optional<AsyncTask> async(String jobId) {
        uuid(jobId);return jdbc.query("SELECT * FROM prepared_async_model_task WHERE job_id=?",(rs,n)->{
            String input=json(rs.getString("input_json"),65536),result=rs.getString("result_json");
            if(!digest(input).equals(rs.getString("input_sha256")))throw conflict();
            if(result!=null){result=json(result,262144);if(!digest(result).equals(rs.getString("result_sha256")))throw conflict();}
            return new AsyncTask(rs.getString("job_id"),rs.getString("call_id"),input,rs.getString("task_id"),result,rs.getTimestamp("recovery_deadline").toInstant(),rs.getInt("query_failures"),rs.getInt("result_failures"));
        },jobId).stream().findFirst();
    }
    /** Durable non-paid polling schedule; polls do not count toward the three paid attempts. */
    public boolean yieldAsync(Lease lease,int seconds) {
        validateLease(lease);if(seconds<10||seconds>300)throw new IllegalArgumentException("异步查询间隔无效");
        return Boolean.TRUE.equals(transaction.execute(s->{if(!lockActive(lease)||!knownAsync(lease.job().id()))return false;
            return jdbc.update("UPDATE prepared_job SET state='RETRY_WAIT',lease_owner=NULL,lease_until=NULL,next_run_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL ? SECOND),updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",seconds,lease.job().id())==1;
        }));
    }
    /** Only healthy status reads reset query failures; they cannot erase download failures. */
    public boolean pollSucceeded(Lease lease){
        validateLease(lease);return Boolean.TRUE.equals(transaction.execute(s->{if(!lockActive(lease)||!knownAsync(lease.job().id()))return false;
            jdbc.update("UPDATE prepared_async_model_task SET query_failures=0,updated_at=CURRENT_TIMESTAMP(3) WHERE job_id=?",lease.job().id());return true;
        }));
    }
    /** Bounded GET failures, separate from paid dispatch attempts. Third failure atomically
     * escalates the task and protects an unresolved charge, including a crash at this boundary. */
    public AsyncRetry retryAsync(Lease lease,boolean resultRead){
        validateLease(lease);return transaction.execute(s->{
            if(!lockActive(lease)||!knownAsync(lease.job().id()))return AsyncRetry.STALE;
            var saved=async(lease.job().id()).orElseThrow();int failures=(resultRead?saved.resultFailures():saved.queryFailures())+1;
            String column=resultRead?"result_failures":"query_failures"; // constant identifiers, never caller SQL
            jdbc.update("UPDATE prepared_async_model_task SET "+column+"=?,updated_at=CURRENT_TIMESTAMP(3) WHERE job_id=?",Math.min(failures,3),lease.job().id());
            if(failures>=3){
                uncertainWithinTransaction(lockCallWithBudget(saved.callId()));
                jdbc.update("UPDATE prepared_job SET state='NEEDS_REVIEW',error_code=?,lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",resultRead?"ASR_RESULT_RETRIES_EXHAUSTED":"ASR_QUERY_RETRIES_EXHAUSTED",lease.job().id());
                return AsyncRetry.EXHAUSTED;
            }
            yieldAsync(lease,30*(1<<failures));return AsyncRetry.DEFERRED;
        });
    }
    /** Checkpoint only a normalized credential-free result after historical fee reconciliation.
     * Final scoped result publication remains in the result service's fresh transaction. */
    public boolean saveAsyncResult(Lease lease,String content) {
        validateLease(lease);String result=json(content,262144);
        return Boolean.TRUE.equals(transaction.execute(s->{
            if(!lockActive(lease)||unresolved(lease)||!knownAsync(lease.job().id()))return false;
            var task=async(lease.job().id()).orElseThrow();var call=lockCallWithBudget(task.callId());
            if(!"SETTLED".equals(call.state())||!task.taskId().equals(call.providerId()))return false;
            if(task.resultJson()!=null){if(!result.equals(task.resultJson()))throw conflict();return true;}
            jdbc.update("UPDATE prepared_async_model_task SET result_json=?,result_sha256=?,result_failures=0,phase='RESULT_READY',updated_at=CURRENT_TIMESTAMP(3) WHERE job_id=?",result,digest(result),lease.job().id());return true;
        }));
    }
    private boolean knownAsync(String id){return !jdbc.queryForList("SELECT job_id FROM prepared_async_model_task WHERE job_id=? AND task_id IS NOT NULL",String.class,id).isEmpty();}
    private boolean knownScore(String id){return !jdbc.queryForList("SELECT s.job_id FROM prepared_score_call s JOIN prepared_model_call c ON c.id=s.call_id WHERE s.job_id=? AND s.reply_json IS NOT NULL AND c.state='SETTLED'",String.class,id).isEmpty();}
    private boolean knownRecovery(String id){return knownAsync(id)||knownScore(id);}
    /** Synchronous SCORE dispatch checkpoint; no credentials, and exactly one paid send per job. */
    public boolean dispatchScore(Lease lease,String callId,String snapshot){
        validateLease(lease);uuid(callId);String input=json(snapshot,524288);
        if(lease.job().kind()!=Kind.SCORE)throw new IllegalArgumentException("评分请求类型无效");
        return Boolean.TRUE.equals(transaction.execute(s->{
            if(!lockActive(lease))return false;var call=lockCallWithBudget(callId);
            if(!call.job().equals(lease.job().id())||call.generation()!=lease.generation())throw conflict();
            if(!"RESERVED".equals(call.state())||budget(call.scope()).paused())return false;
            if(scoreCall(call.job()).isPresent())throw conflict();
            jdbc.update("INSERT INTO prepared_score_call(job_id,call_id,input_json,input_sha256) VALUES (?,?,?,?)",call.job(),callId,input,digest(input));
            jdbc.update("UPDATE prepared_model_call SET state='SENT',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",callId);
            jdbc.update("UPDATE prepared_job SET external_sent=TRUE WHERE id=?",call.job());event(call.scope(),callId,"SENT",call.bound());return true;
        }));
    }
    public Optional<ScoreCall> scoreCall(String jobId){
        uuid(jobId);return jdbc.query("SELECT * FROM prepared_score_call WHERE job_id=?",(rs,n)->{
            String input=json(rs.getString("input_json"),524288),reply=rs.getString("reply_json");
            if(!digest(input).equals(rs.getString("input_sha256")))throw conflict();
            if(reply!=null){reply=json(reply,524288);if(!digest(reply).equals(rs.getString("reply_sha256")))throw conflict();}
            return new ScoreCall(rs.getString("job_id"),rs.getString("call_id"),input,reply,rs.getInt("publication_failures"));
        },jobId).stream().findFirst();
    }
    /** Original paid caller may save a late credible reply. Fee settlement and reply persistence
     * share one transaction; a lost DB response is recoverable without paid model replay.
     * Only original generation can attach it; no current lease/authorization is bypassed on publish. */
    public void saveScoreReply(Lease original,String callId,String raw,BigDecimal actual,String providerId){
        validateLease(original);uuid(callId);String reply=json(raw,524288);
        transaction.executeWithoutResult(s->{
            var states=jdbc.queryForList("SELECT state,error_code FROM prepared_job WHERE id=? FOR UPDATE",original.job().id());if(states.isEmpty())throw conflict();
            var call=lockCallWithBudget(callId);var saved=scoreCall(call.job()).orElseThrow(PreparedJobLedger::conflict);
            if(original.job().kind()!=Kind.SCORE||!call.job().equals(original.job().id())||call.generation()!=original.generation()||!saved.callId().equals(callId))throw conflict();
            if(saved.replyJson()!=null){if(!saved.replyJson().equals(reply))throw conflict();settle(callId,actual,providerId);return;}
            settle(callId,actual,providerId);
            jdbc.update("UPDATE prepared_score_call SET reply_json=?,reply_sha256=?,updated_at=CURRENT_TIMESTAMP(3) WHERE job_id=?",reply,digest(reply),call.job());
            var job=states.getFirst();if("NEEDS_REVIEW".equals(job.get("state"))&&Set.of("AMBIGUOUS_CALL","SCORE_RESPONSE_UNKNOWN").contains(job.get("error_code")))
                jdbc.update("UPDATE prepared_job SET state='RETRY_WAIT',error_code=NULL,next_run_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",call.job());
        });
    }
    /** Bounded non-paid publication retries; does not reset paid attempt counts on restart. */
    public AsyncRetry retryScorePublication(Lease lease){
        validateLease(lease);return transaction.execute(s->{
            if(!lockActive(lease)||!knownScore(lease.job().id())||unresolved(lease))return AsyncRetry.STALE;
            int failures=scoreCall(lease.job().id()).orElseThrow().publicationFailures()+1;
            jdbc.update("UPDATE prepared_score_call SET publication_failures=?,updated_at=CURRENT_TIMESTAMP(3) WHERE job_id=?",Math.min(3,failures),lease.job().id());
            jdbc.update("UPDATE prepared_job SET state=?,error_code=?,lease_owner=NULL,lease_until=NULL,next_run_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL ? SECOND),updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",
                failures>=3?"NEEDS_REVIEW":"RETRY_WAIT",failures>=3?"SCORE_PUBLICATION_RETRIES_EXHAUSTED":"SCORE_PUBLICATION_RETRY",30*(1<<Math.min(3,failures)),lease.job().id());
            return failures>=3?AsyncRetry.EXHAUSTED:AsyncRetry.DEFERRED;
        });
    }
    public void uncertain(String callId) {
        uuid(callId); transaction.executeWithoutResult(status -> uncertainWithinTransaction(lockCallWithBudget(callId)));
    }
    private void uncertainWithinTransaction(Call original) {
        Call call = lockCallWithBudget(original.id());
        if (!call.state().equals("SENT")) return;
        jdbc.update("UPDATE prepared_model_call SET state='UNCERTAIN',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?", call.id());
        event(call.scope(), call.id(), "UNCERTAIN", call.bound()); pauseBudget(call.scope(), "UNKNOWN_CHARGE");
    }
    public boolean cancelBeforeDispatch(String callId) {
        uuid(callId); return Boolean.TRUE.equals(transaction.execute(status -> cancelWithinTransaction(lockCallWithBudget(callId))));
    }
    private boolean cancelWithinTransaction(Call original) {
        Call call = lockCallWithBudget(original.id());
        if (call.state().equals("CANCELLED")) return true;
        if (!call.state().equals("RESERVED")) return false;
        jdbc.update("UPDATE prepared_model_call SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?", call.id());
        jdbc.update("UPDATE prepared_budget SET reserved_cny=reserved_cny-?,updated_at=CURRENT_TIMESTAMP(3) WHERE scope_id=?", call.bound(), call.scope());
        event(call.scope(), call.id(), "CANCELLED", call.bound()); return true;
    }
    /** Cost reconciliation is allowed after lease expiry; result publication is still generation-fenced. */
    public void settle(String callId, BigDecimal actual, String providerId) {
        uuid(callId); money(actual, true);
        if (providerId == null || !providerId.matches("[a-zA-Z0-9_.:-]{1,128}")) throw new IllegalArgumentException("供应商请求标识无效");
        transaction.executeWithoutResult(status -> {
            Call call = lockCallWithBudget(callId);
            if (call.state().equals("SETTLED")) {
                if (call.actual().compareTo(actual) != 0 || !providerId.equals(call.providerId())) throw conflict(); return;
            }
            if (!Set.of("SENT", "UNCERTAIN").contains(call.state())) throw conflict();
            jdbc.update("""
                    UPDATE prepared_model_call SET state='SETTLED',actual_cny=?,provider_request_id=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?
                    """, actual, providerId, callId);
            jdbc.update("""
                    UPDATE prepared_budget SET spent_cny=spent_cny+?,reserved_cny=reserved_cny-?,updated_at=CURRENT_TIMESTAMP(3) WHERE scope_id=?
                    """, actual, call.bound(), call.scope());
            event(call.scope(), call.id(), "SETTLED", actual);
            if (actual.compareTo(call.bound()) > 0) {
                pauseBudget(call.scope(), "BOUND_EXCEEDED"); event(call.scope(), call.id(), "BOUND_EXCEEDED", actual);
            }
        });
    }
    private boolean lockActive(Lease lease) {
        return !jdbc.queryForList("""
                SELECT id FROM prepared_job WHERE id=? AND state='RUNNING' AND lease_generation=?
                AND lease_owner=? AND lease_until>CURRENT_TIMESTAMP(3) FOR UPDATE
                """, String.class, lease.job().id(), lease.generation(), lease.worker()).isEmpty();
    }
    private boolean unresolved(Lease lease) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM prepared_model_call WHERE job_id=? AND state IN ('RESERVED','SENT','UNCERTAIN')
                """, Integer.class, lease.job().id());
        return count != null && count > 0;
    }
    private Budget lockBudget(String id) { return readBudget(id, true); }
    private Budget readBudget(String id, boolean lock) {
        var budgets = jdbc.query("SELECT * FROM prepared_budget WHERE scope_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs,n) -> new Budget(rs.getString("scope_id"), rs.getBigDecimal("cap_cny"), rs.getBigDecimal("pause_cny"),
                        rs.getBigDecimal("spent_cny"), rs.getBigDecimal("reserved_cny"), rs.getBoolean("paused")), id);
        if (budgets.isEmpty()) throw new BusinessException(409, "独立模型预算尚未初始化");
        return budgets.getFirst();
    }
    private Call lockCallWithBudget(String id) {
        var calls = jdbc.query("SELECT * FROM prepared_model_call WHERE id=?", CALL, id);
        if (calls.isEmpty()) throw new BusinessException(409, "调用费用记录不存在");
        lockBudget(calls.getFirst().scope());
        return jdbc.query("SELECT * FROM prepared_model_call WHERE id=? FOR UPDATE", CALL, id).getFirst();
    }
    private void pauseBudget(String id, String reason) {
        if (jdbc.update("UPDATE prepared_budget SET paused=TRUE,pause_reason=?,updated_at=CURRENT_TIMESTAMP(3) WHERE scope_id=? AND paused=FALSE", reason, id) == 1)
            event(id, null, "PAUSED", null);
    }
    private void event(String scope, String call, String action, BigDecimal amount) {
        jdbc.update("INSERT INTO prepared_budget_event(scope_id,call_id,action,amount_cny) VALUES (?,?,?,?)", scope, call, action, amount);
    }
    private static void validateLease(Lease lease) {
        if (lease == null || lease.job() == null || lease.generation() < 1) throw new IllegalArgumentException("任务租约无效");
        uuid(lease.job().id()); worker(lease.worker());
    }
    private static void worker(String worker) {
        if (worker == null || !worker.matches("[a-zA-Z0-9][a-zA-Z0-9-]{0,63}")) throw new IllegalArgumentException("后台实例标识无效");
    }
    private static void leaseSeconds(int seconds) {
        if (seconds < 15 || seconds > 300) throw new IllegalArgumentException("任务租约必须在 15 到 300 秒内");
    }
    private static BusinessException conflict() { return new BusinessException(409, "任务或费用幂等信息冲突"); }
}
