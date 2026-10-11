package system.assessment.defense.prepared.retention;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.processing.PreparedProcessingService;
import system.assessment.defense.prepared.processing.PreparedProcessingCodec;
import system.assessment.defense.prepared.jobs.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static system.assessment.defense.prepared.retention.PreparedAudioRetentionPolicy.*;

/** Internal read-only ORIGINAL evidence. No controller, scheduler, deletion token,
 * model/storage call or lifecycle mutation. Candidate snapshots expire when this
 * transaction ends: an eventual executor MUST revalidate and fence all original IO.
 * Reads do not impersonate a student/teacher or relax any existing HTTP permissions. */
@Service
public class PreparedRetentionEvidenceService {
    private static final int MAX_ROWS=1000;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final PreparedSubmissionService submissions;
    private final PreparedProcessingService processing;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PreparedRetentionProtectionStore protection;
    private final Clock clock;
    private record Anchor(Instant first,boolean review){}
    private record ReadAuthorization(String answer,String sha,long revision,Instant created,Instant expires){}
    @Autowired public PreparedRetentionEvidenceService(PreparedSubmissionService submissions,PreparedProcessingService processing,JdbcTemplate jdbc,PlatformTransactionManager manager){this(submissions,processing,jdbc,manager,Clock.systemUTC());}
    public PreparedRetentionEvidenceService(PreparedSubmissionService submissions,PreparedProcessingService processing,JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock){
        this.submissions=submissions;this.processing=processing;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.protection=new PreparedRetentionProtectionStore(jdbc);this.clock=clock;
    }
    public Decision inspect(int student,int assessment,String attempt){
        return inspectOriginal(student,assessment,attempt,false,null,PreparedAudioRetentionPolicy::inspect);
    }
    /** Only the still-current, verified preparation can recheck a RETIRING original.
     * Package-internal; neither a client state nor an old candidate bypasses the fence. */
    Decision inspectForExecution(int student,int assessment,String attempt,String preparation,long revision){
        PreparedJobInput.uuid(preparation);if(revision<1)throw new IllegalArgumentException("原清理准备版本无效");
        return inspectOriginal(student,assessment,attempt,false,new Staged(preparation,revision),PreparedAudioRetentionPolicy::inspect);
    }
    private record Staged(String id,long revision){}
    /** Internal year-result candidate only. Retired audio may retain structural
     * result proofs; audio still retiring or non-retained results remain protected.
     * No public permission, content exit or cleanup audit is created here. */
    public PreparedResultRetentionPolicy.Decision inspectResults(int student,int assessment,String attempt){
        return inspectOriginal(student,assessment,attempt,true,null,PreparedResultRetentionPolicy::inspect);
    }
    private <T> T inspectOriginal(int student,int assessment,String attempt,boolean resultsOnly,Staged staged,
            java.util.function.BiFunction<Evidence,Instant,T> policy){
        if(student<1||assessment<1)throw new IllegalArgumentException("原留存范围无效");PreparedJobInput.uuid(attempt);
        return tx.execute(s->{
            // Acquires the SAME parent first, including archived/historical originals.
            var original=submissions.retentionSources(student,assessment,attempt);var sources=original.sources();
            var guard=protection.guard(student,assessment,attempt);guard.ifPresent(g->protection.latest(student,assessment,attempt,g));
            boolean verifiedStaged=false;
            if(staged!=null){
                var preparation=new PreparedAudioRetirementPreparationStore(jdbc).latest(student,assessment,attempt).orElseThrow(PreparedRetentionEvidenceService::invalid);
                var state=guard.orElseThrow(PreparedRetentionEvidenceService::invalid);
                if(!"STAGED".equals(preparation.state())||!staged.id().equals(preparation.preparation().id())||staged.revision()!=preparation.preparation().revision()
                    ||state.revision()!=staged.revision()||!"RETIRING".equals(state.audio())||!"RETAINED".equals(state.results()))throw invalid();
                verifiedStaged=true;
            }
            var head=jdbc.query("SELECT first_results_completed_at,requires_supplement_review FROM prepared_attempt WHERE assessment_id=? AND student_id=? AND id=? FOR UPDATE",
                (rs,n)->{var first=rs.getTimestamp(1);return new Anchor(first==null?null:first.toInstant(),rs.getBoolean(2));},assessment,student,attempt).stream().findFirst().orElseThrow(PreparedRetentionEvidenceService::invalid);
            var submitted=jdbc.query("SELECT submitted_at FROM prepared_submission WHERE assessment_id=? AND student_id=? AND attempt_id=? FOR UPDATE",(rs,n)->rs.getTimestamp(1).toInstant(),assessment,student,attempt);
            if(submitted.size()>1)throw invalid();
            var work=jobs(student,assessment,attempt,sources);var calls=calls(student,assessment,work.ids());
            final boolean stagedBinding=verifiedStaged;
            boolean retained=guard.map(g->"RETAINED".equals(g.results())&&("RETAINED".equals(g.audio())
                ||resultsOnly&&"RETIRED".equals(g.audio())||stagedBinding&&"RETIRING".equals(g.audio()))).orElse(true);
            return policy.apply(new Evidence(original.attempt().state(),Instant.parse(original.attempt().startedAt()),
                submitted.isEmpty()?null:submitted.getFirst(),head.first(),!sources.isEmpty(),
                processing.retentionResultsComplete(sources),retained&&work.verified(),head.review(),
                guard.map(PreparedRetentionProtectionStore.Guard::held).orElse(false),work.states(),calls,readExpires(student,assessment,attempt,sources),
                new PreparedOriginalAudioIoJournal(jdbc).inFlight(student,assessment,attempt,sources)),clock.instant().truncatedTo(ChronoUnit.MILLIS));
        });
    }
    private record Work(Set<String> ids,Set<String> states,boolean verified){Work{ids=Set.copyOf(ids);states=Set.copyOf(states);}}
    private Work jobs(int student,int assessment,String attempt,List<PreparedSubmissionService.ProcessingSource> sources){
        var byAnswer=new HashMap<String,PreparedSubmissionService.ProcessingSource>();var byJob=new HashMap<String,PreparedSubmissionService.ProcessingSource>();
        for(var source:sources){var binding=source.binding();byAnswer.put(binding.input().answer(),source);for(String id:List.of(binding.input().mediaJob(),binding.transcribeJob(),binding.scoreJob()))if(byJob.put(id,source)!=null)throw invalid();}
        var rows=jdbc.queryForList("SELECT id,kind,state,input_json,input_version FROM prepared_job WHERE assessment_id=? AND created_by=? AND kind IN ('ANSWER_MEDIA','TRANSCRIBE','SCORE') ORDER BY id LIMIT 1001 FOR UPDATE",assessment,student);bounded(rows);
        // Include historical regrades as well as the original three jobs before
        // excluding a different original. Row ordering must not hide a reference.
        var originalIds=new HashSet<>(byJob.keySet());
        for(var row:rows){
            try{var input=JSON.readTree(PreparedJobInput.json((String)row.get("input_json"),262144));var scope=input.has("work")?input.get("work"):input;
                if(scope.isObject()&&attempt.equals(scope.path("attempt").textValue()))originalIds.add((String)row.get("id"));
            }catch(Exception unknown){/* The main pass retains malformed evidence. */}
        }
        var ids=new HashSet<String>();var states=new HashSet<String>();boolean verified=true;
        for(var row:rows){
            String id=(String)row.get("id");var source=byJob.get(id);
            if(source==null){
                final JsonNode scope;try{var input=JSON.readTree(PreparedJobInput.json((String)row.get("input_json"),262144));scope=input.has("work")?input.get("work"):input;}
                catch(Exception missing){ids.add(id);states.add("UNVERIFIED");verified=false;continue;}
                if(!scope.isObject()||!scope.path("student").isIntegralNumber()||!scope.path("student").canConvertToInt()||scope.path("student").intValue()!=student
                    ||!scope.path("assessment").isIntegralNumber()||!scope.path("assessment").canConvertToInt()||scope.path("assessment").intValue()!=assessment||!scope.path("attempt").isTextual()||!scope.path("answer").isTextual()){
                    ids.add(id);states.add("UNVERIFIED");verified=false;continue;
                }
                if(!attempt.equals(scope.path("attempt").textValue())){
                    // A payload mentioning an existing foreign answer is NOT a registration.
                    if(!referencesOriginal(id,scope,originalIds)&&foreignRegistered(id,(String)row.get("kind"),(String)row.get("input_json"),scope,student,assessment))continue;
                    ids.add(id);states.add("UNVERIFIED");verified=false;continue;
                }
                source=byAnswer.get(scope.path("answer").textValue());
            }
            ids.add(id);states.add((String)row.get("state"));
            if(source==null){verified=false;continue;}
            var job=new PreparedJobLedger.Job(id,assessment,student,PreparedJobLedger.Kind.valueOf((String)row.get("kind")),((Number)row.get("input_version")).longValue(),(String)row.get("input_json"),null,PreparedJobLedger.State.valueOf((String)row.get("state")),0);
            if(!processing.retentionJobMatches(job,source))verified=false;
        }
        if(!ids.containsAll(byJob.keySet()))verified=false;return new Work(ids,states,verified);
    }
    private boolean referencesOriginal(String job,JsonNode scope,Set<String> originals){
        if(originals.contains(scope.path("mediaJob").textValue()))return true;
        var dependencies=jdbc.queryForList("SELECT depends_on_id FROM prepared_job_dependency WHERE job_id=? LIMIT 1001 FOR UPDATE",String.class,job);bounded(dependencies);
        return dependencies.stream().anyMatch(originals::contains);
    }
    private boolean foreignRegistered(String job,String kind,String json,JsonNode scope,int student,int assessment){
        try{
            String attempt=PreparedJobInput.uuid(scope.path("attempt").textValue()),answer=PreparedJobInput.uuid(scope.path("answer").textValue());
            if("ANSWER_MEDIA".equals(kind)){
                var input=PreparedProcessingCodec.decode(json,PreparedAnswerMediaService.Input.class);
                if(!PreparedProcessingCodec.encode(input).equals(PreparedJobInput.json(json,262144)))return false;
                return jdbc.queryForList("""
                    SELECT m.answer_id FROM prepared_answer_media m JOIN prepared_answer a ON a.id=m.answer_id
                    WHERE m.assessment_id=? AND m.student_id=? AND m.attempt_id=? AND m.answer_id=? AND m.job_id=?
                    AND a.capture_client_id=? AND m.manifest_sha256=? AND m.declaration_revision=? AND m.deployment_id=? FOR UPDATE
                    """,String.class,assessment,student,attempt,answer,job,input.client(),input.manifestSha256(),input.declarationRevision(),input.deployment()).size()==1;
            }
            if(scope!=null&&JSON.readTree(PreparedJobInput.json(json,262144)).has("work")){
                if(!"SCORE".equals(kind))return false;var input=PreparedProcessingCodec.decode(json,PreparedProcessingService.RegradeInput.class);
                if(input.schemaVersion()!=1||!PreparedProcessingCodec.encode(input).equals(PreparedJobInput.json(json,262144)))return false;
                return jdbc.queryForList("""
                    SELECT job_id FROM prepared_score_request WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? AND job_id=?
                    AND transcript_id=? AND requested_revision=? AND requested_by=? AND reason=? FOR UPDATE
                    """,String.class,assessment,student,attempt,answer,job,input.transcriptId(),input.requestedRevision(),input.requestedBy(),input.reason()).size()==1;
            }
            var input=PreparedProcessingCodec.decode(json,PreparedSubmission.Work.class);
            if(input.schemaVersion()!=1||!PreparedProcessingCodec.encode(input).equals(PreparedJobInput.json(json,262144)))return false;
            String column="TRANSCRIBE".equals(kind)?"transcribe_job_id":"score_job_id";
            return jdbc.queryForList("SELECT answer_id FROM prepared_submission_answer WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? AND submission_id=? AND ordinal=? AND "+column+"=? FOR UPDATE",String.class,assessment,student,attempt,answer,input.submission(),input.ordinal(),job).size()==1;
        }catch(java.io.IOException|IllegalArgumentException unknown){return false;}
    }
    private Set<String> calls(int student,int assessment,Set<String> ids){
        var rows=jdbc.queryForList("""
            SELECT c.job_id,c.state,c.lease_generation,c.actual_cny,c.provider_request_id,j.lease_generation AS job_generation,
              (SELECT COUNT(*) FROM prepared_budget_event e WHERE e.call_id=c.id AND e.scope_id=c.scope_id AND e.action=c.state
                AND e.amount_cny=CASE WHEN c.state='CANCELLED' THEN c.reserved_cny ELSE c.actual_cny END) AS disposition_count
            FROM prepared_model_call c JOIN prepared_job j ON j.id=c.job_id
            WHERE j.assessment_id=? AND j.created_by=? AND j.kind IN ('ANSWER_MEDIA','TRANSCRIBE','SCORE') ORDER BY c.id LIMIT 1001 FOR UPDATE
            """,assessment,student);bounded(rows);var states=new HashSet<String>();
        for(var row:rows)if(ids.contains(row.get("job_id"))){
            String state=(String)row.get("state");long generation=((Number)row.get("lease_generation")).longValue(),jobGeneration=((Number)row.get("job_generation")).longValue();
            boolean valid=generation>0&&generation<=jobGeneration;
            if("SETTLED".equals(state))valid=valid&&row.get("actual_cny")!=null&&row.get("provider_request_id") instanceof String provider&&provider.matches("[a-zA-Z0-9_.:-]{1,128}");
            else valid=valid&&row.get("actual_cny")==null&&row.get("provider_request_id")==null;
            if("SETTLED".equals(state)||"CANCELLED".equals(state))valid=valid&&((Number)row.get("disposition_count")).longValue()==1;
            states.add(valid?state:"UNVERIFIED");
        }return Set.copyOf(states);
    }
    private Instant readExpires(int student,int assessment,String attempt,List<PreparedSubmissionService.ProcessingSource> sources){
        var originals=new HashMap<String,PreparedSubmissionService.ProcessingSource>();for(var source:sources)originals.put(source.binding().input().answer(),source);
        var rows=jdbc.query("""
            SELECT resource_id,resource_sha256,resource_version,created_at,authorization_expires_at FROM prepared_audit
            WHERE assessment_id=? AND action IN('ORIGINAL_AUDIO_AUTHORIZED','ORIGINAL_ASR_INPUT_AUTHORIZED') AND resource_id IN(
                SELECT id FROM prepared_answer WHERE assessment_id=? AND student_id=? AND attempt_id=?) ORDER BY id LIMIT 1001 FOR UPDATE
            """,(rs,n)->{var created=rs.getTimestamp("created_at");var expires=rs.getTimestamp("authorization_expires_at");return new ReadAuthorization(rs.getString("resource_id"),rs.getString("resource_sha256"),rs.getLong("resource_version"),created==null?null:created.toInstant(),expires==null?null:expires.toInstant());},assessment,assessment,student,attempt);bounded(rows);Instant latest=null;
        for(var row:rows){
            var source=originals.get(row.answer());
            if(source==null||row.expires()==null||row.created()==null||row.expires().isBefore(row.created())||!source.media().proof().sha256().equals(row.sha())
                ||row.revision()!=source.binding().input().answerRevision())throw invalid();
            if(latest==null||row.expires().isAfter(latest))latest=row.expires();
        }return latest;
    }
    private static void bounded(List<?> rows){if(rows.size()>MAX_ROWS)throw new IllegalStateException("原留存核验范围超过有界处理限制，原数据保持保留");}
    private static IllegalStateException invalid(){return new IllegalStateException("原留存数据库依据无法核验，原数据保持保留");}
}
