package system.assessment.defense.prepared.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.publication.PreparedPublication;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import system.assessment.defense.prepared.media.PreparedAnswerMedia;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static system.assessment.defense.prepared.attempt.PreparedSubmission.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;

/** Final truth is a unique durable receipt plus scoped processing outbox, committed with the
 * attempt/compatible record. No storage IO, model availability checks or paid calls here. */
@Service
public class PreparedSubmissionService {
    /** Internal worker boundary. Full question/rubric/private scope must never become a student response. */
    public record ProcessingSource(Binding binding,PreparedPublication.Question question,PreparedPrivateOssStore.StoredAnswer media) {}
    /** Internal authorized manager read, never an HTTP payload. */
    public record ReviewSources(PreparedAttemptStart.Summary attempt,PreparedPublication.Snapshot paper,List<ProcessingSource> sources) {
        public ReviewSources {sources=List.copyOf(sources);}
    }
    private record Context(PreparedAttemptStart.Summary attempt,PreparedPublication.Snapshot paper,List<PreparedQuestionGrantRepository.Answer> answers) {}
    private record Head(String id,String paper,long revision,int count,Instant time,String json,String sha) {}
    private record Frozen(int schemaVersion,List<Binding> answers) {public Frozen{answers=List.copyOf(answers);}}
    private final ResourceAccessControlService access;private final PreparedStudentEligibilityRepository eligibility;private final PreparedAttemptStartRepository attempts;
    private final PreparedQuestionGrantRepository questions;private final PreparedAnswerEndRepository endings;private final PreparedAnswerMediaService media;
    private final PreparedJobLedger jobs;private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;
    private static final ObjectMapper JSON=new ObjectMapper();
    @Autowired public PreparedSubmissionService(ResourceAccessControlService access,PreparedStudentEligibilityRepository eligibility,PreparedAttemptStartRepository attempts,
            PreparedQuestionGrantRepository questions,PreparedAnswerEndRepository endings,PreparedAnswerMediaService media,PreparedJobLedger jobs,JdbcTemplate jdbc,PlatformTransactionManager manager){
        this(access,eligibility,attempts,questions,endings,media,jobs,jdbc,manager,Clock.systemUTC());
    }
    public PreparedSubmissionService(ResourceAccessControlService access,PreparedStudentEligibilityRepository eligibility,PreparedAttemptStartRepository attempts,
            PreparedQuestionGrantRepository questions,PreparedAnswerEndRepository endings,PreparedAnswerMediaService media,PreparedJobLedger jobs,JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock){
        this.access=access;this.eligibility=eligibility;this.attempts=attempts;this.questions=questions;this.endings=endings;this.media=media;this.jobs=jobs;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.clock=clock;
    }
    public Summary status(int student,int assessment,String attempt){return tx.execute(s->{var context=authorize(student,assessment,attempt);return summary(student,assessment,context,head(student,assessment,attempt));});}
    public ProcessingSource processingSource(Work expected){
        if(expected==null)throw new IllegalArgumentException("原处理输入缺失");
        return tx.execute(s->{
            var context=authorize(expected.student(),expected.assessment(),expected.attempt());
            return originalSource(expected,context,answer->media.status(expected.student(),expected.assessment(),expected.attempt(),answer));
        });
    }
    /** Internal bounded current-student reader. Verify the entire immutable submission once,
     * rather than re-verifying all answers for every result in a summary. The caller must retain
     * its outer transaction; these full teacher/worker sources are never HTTP student DTOs. */
    public List<ProcessingSource> currentSubmittedSources(int student,int assessment,String attempt){
        return tx.execute(s->{
            var context=authorize(student,assessment,attempt);
            var original=head(student,assessment,attempt).orElseThrow(()->conflict("原唯一提交回执缺失"));
            var saved=new HashMap<String,PreparedAnswerMediaService.Summary>();
            java.util.function.Function<String,PreparedAnswerMediaService.Summary> read=answer->saved.computeIfAbsent(answer,id->media.status(student,assessment,attempt,id));
            verified(student,assessment,context,original,read);
            return decode(original.json()).answers().stream().map(binding->boundSource(binding,context)).toList();
        });
    }
    /** Teacher-only immutable historical reader; worker/student authorization stays current.
     * All original receipt, media, frozen question, task and dependency checks are reused. */
    public ProcessingSource reviewSource(int actor,Work expected){
        if(expected==null)throw new IllegalArgumentException("原评阅输入缺失");
        return tx.execute(s->{
            var attempt=PreparedManagerReadScope.attempt(access,attempts,actor,expected.student(),expected.assessment(),expected.attempt());
            var context=boundContext(expected.student(),expected.assessment(),attempt);
            return originalSource(expected,context,answer->media.reviewStatus(actor,expected.student(),expected.assessment(),expected.attempt(),answer));
        });
    }
    /** One whole-submission verification for a teacher summary, including immutable historical
     * attempts. Does not revive the current-only worker/student write authorization. */
    public ReviewSources reviewSources(int actor,int student,int assessment,String attempt){
        return tx.execute(s->{
            var originalAttempt=PreparedManagerReadScope.attempt(access,attempts,actor,student,assessment,attempt);
            var context=boundContext(student,assessment,originalAttempt);
            var original=head(student,assessment,attempt);
            if(original.isEmpty()){
                if("SUBMITTED".equals(originalAttempt.state()))throw new IllegalStateException("原提交状态缺少唯一回执");
                return new ReviewSources(originalAttempt,context.paper(),List.of());
            }
            var saved=new HashMap<String,PreparedAnswerMediaService.Summary>();
            verified(student,assessment,context,original.orElseThrow(),answer->saved.computeIfAbsent(answer,
                id->media.reviewStatus(actor,student,assessment,attempt,id)));
            return new ReviewSources(originalAttempt,context.paper(),decode(original.orElseThrow().json()).answers().stream().map(binding->boundSource(binding,context)).toList());
        });
    }
    /** INTERNAL retention evidence, independent of current user roles/assignment/anchor.
     * No HTTP caller, read URL, task write, lease, storage IO or permission is exposed.
     * Reuses the SAME frozen paper, answer, media, receipt and dependency checks. */
    public ReviewSources retentionSources(int student,int assessment,String attempt){
        if(student<1||assessment<1)throw new IllegalArgumentException("原留存范围无效");PreparedJobInput.uuid(attempt);
        return tx.execute(s->{
            if(jdbc.queryForList("SELECT id FROM assessment WHERE id=? FOR UPDATE",Integer.class,assessment).size()!=1)throw new IllegalStateException("原留存考核缺失");
            var original=attempts.get(student,assessment,attempt);
            var bound=jdbc.queryForList("""
                SELECT t.id FROM prepared_attempt t
                JOIN student_assessment_record r ON r.id=t.record_id AND r.assessment_id=t.assessment_id AND r.student_id=t.student_id
                JOIN prepared_attempt_anchor h ON h.record_id=t.record_id AND h.assessment_id=t.assessment_id AND h.student_id=t.student_id
                WHERE t.assessment_id=? AND t.student_id=? AND t.id=? AND (t.state<>'SUBMITTED' OR r.state=2) FOR UPDATE
                """,String.class,assessment,student,attempt);
            if(bound.size()!=1)throw new IllegalStateException("原留存记录与锚点绑定无法核验");
            var context=boundContext(student,assessment,original);var head=head(student,assessment,attempt);
            if(head.isEmpty()){
                if("SUBMITTED".equals(original.state()))throw new IllegalStateException("原提交状态缺少唯一回执");
                return new ReviewSources(original,context.paper(),List.of());
            }
            var saved=new HashMap<String,PreparedAnswerMediaService.Summary>();
            verified(student,assessment,context,head.orElseThrow(),answer->saved.computeIfAbsent(answer,id->media.retentionStatus(student,assessment,attempt,id)));
            return new ReviewSources(original,context.paper(),decode(head.orElseThrow().json()).answers().stream().map(binding->boundSource(binding,context)).toList());
        });
    }
    private ProcessingSource originalSource(Work expected,Context context,java.util.function.Function<String,PreparedAnswerMediaService.Summary> originalMedia){
            var original=head(expected.student(),expected.assessment(),expected.attempt()).orElseThrow(()->conflict("原唯一提交回执缺失"));
            verified(expected.student(),expected.assessment(),context,original,originalMedia);
            var binding=decode(original.json()).answers().stream().filter(b->b.input().equals(expected)).findFirst().orElseThrow(()->conflict("处理任务不能更换原回答或试卷"));
            return boundSource(binding,context);
    }
    private ProcessingSource boundSource(Binding binding,Context context){
            var expected=binding.input();
            var question=context.paper().questions().get(expected.ordinal()-1);
            var job=jobs.getForShare(expected.mediaJob()).orElseThrow();
            try {
                var input=JSON.readValue(PreparedJobInput.json(job.inputJson(),65536),PreparedAnswerMediaService.Input.class);
                var proof=JSON.readValue(PreparedJobInput.json(job.outputJson(),262144),PreparedAnswerMedia.Proof.class);
                var scope=new PreparedPrivateOssStore.AnswerFile(expected.assessment(),expected.student(),expected.attempt(),expected.answer(),expected.manifestSha256());
                return new ProcessingSource(binding,question,new PreparedPrivateOssStore.StoredAnswer(scope,scope.key(input.deployment()),proof.audio()));
            }catch(java.io.IOException failure){throw new IllegalStateException("原完整录音处理证明损坏");}
    }
    public Summary submit(int student,int assessment,String attempt,Command command){
        if(command==null)throw new IllegalArgumentException("原提交命令缺失");
        var result=tx.execute(s->{
            var context=authorize(student,assessment,attempt);String hash=PreparedJobInput.digest("prepared-submit-v1:"+student+":"+assessment+":"+attempt+":"+command.expectedRevision());
            var previous=jdbc.query("SELECT request_sha256,submission_id FROM prepared_submission_command WHERE assessment_id=? AND student_id=? AND attempt_id=? AND idempotency_key=? FOR UPDATE",
                (rs,n)->List.of(rs.getString(1),rs.getString(2)),assessment,student,attempt,command.idempotencyKey());
            var existing=head(student,assessment,attempt);
            if(!previous.isEmpty()){
                if(!hash.equals(previous.getFirst().getFirst()))throw conflict("相同提交标识不能修改原请求");
                if(existing.isEmpty()||!existing.orElseThrow().id().equals(previous.getFirst().getLast()))throw new IllegalStateException("原提交回执绑定缺失");
                return summary(student,assessment,context,existing);
            }
            // A new key cannot create another final receipt. Recover original only after fresh
            // scope/proof/outbox validation; it never overwrites the original command/version.
            if(existing.isPresent()){
                var recovered=summary(student,assessment,context,existing);recordCommand(student,assessment,attempt,command.idempotencyKey(),hash,existing.orElseThrow().id());return recovered;
            }
            if("SUBMITTED".equals(context.attempt().state()))throw new IllegalStateException("已提交状态缺少唯一回执");
            var pending=pending(student,assessment,context);
            if(!pending.isEmpty()){
                var deadline=context.attempt().supplementalUploadDeadline();
                if(deadline!=null&&!now().isBefore(Instant.parse(deadline))){endings.expirePending(student,assessment,attempt,now());return null;}
                throw conflict("原回答尚未完整保存，请查询提交状态中的待处理题目并保留本机录音");
            }
            if(!"UPLOAD_PENDING".equals(context.attempt().state())&&!supplementRecovered(student,assessment,context))throw conflict("原尝试需要教师核验，不能自动提交");
            if(command.expectedRevision()!=context.attempt().revision())throw conflict("原尝试版本已变化，请先查询原提交状态");
            verifyEndWindow(context.attempt());
            String id=UUID.randomUUID().toString();long revision=context.attempt().revision()+1;Instant time=now();var bindings=new ArrayList<Binding>();
            for(var answer:context.answers()){
                var saved=media.status(student,assessment,attempt,answer.id());
                var work=new Work(1,student,assessment,attempt,context.attempt().paperVersionId(),id,answer.id(),answer.question(),answer.ordinal(),saved.revision(),saved.manifestSha256(),saved.jobId(),proofHash(saved.jobId()));
                String payload=encode(work);String key=key("transcribe",id,answer.id());
                var transcript=jobs.enqueueAfter(student,assessment,Kind.TRANSCRIBE,saved.revision(),key,payload,List.of(saved.jobId()));
                var score=jobs.enqueueAfter(student,assessment,Kind.SCORE,saved.revision(),key("score",id,answer.id()),payload,List.of(transcript.id()));
                bindings.add(new Binding(work,transcript.id(),score.id()));
            }
            String json=encode(new Frozen(1,bindings));
            jdbc.update("INSERT INTO prepared_submission(id,assessment_id,student_id,attempt_id,paper_version_id,submitted_revision,answer_count,submitted_at,snapshot_json,snapshot_sha256) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id,assessment,student,attempt,context.attempt().paperVersionId(),revision,bindings.size(),Timestamp.from(time),json,PreparedJobInput.digest(json));
            for(var binding:bindings)jdbc.update("INSERT INTO prepared_submission_answer(assessment_id,student_id,attempt_id,submission_id,answer_id,ordinal,transcribe_job_id,score_job_id) VALUES (?,?,?,?,?,?,?,?)",
                assessment,student,attempt,id,binding.input().answer(),binding.input().ordinal(),binding.transcribeJob(),binding.scoreJob());
            if(jdbc.update("UPDATE prepared_attempt SET state='SUBMITTED',requires_supplement_review=0,revision=revision+1 WHERE assessment_id=? AND student_id=? AND id=? AND state=? AND revision=?",
                    assessment,student,attempt,context.attempt().state(),command.expectedRevision())!=1)throw conflict("原尝试状态已变化");
            if(jdbc.update("UPDATE student_assessment_record SET state=2,update_time=? WHERE id=? AND assessment_id=? AND student_id=? AND state=0",Timestamp.from(time),context.attempt().recordId(),assessment,student)!=1)throw conflict("原考核记录需核验，不覆盖历史结果");
            recordCommand(student,assessment,attempt,command.idempotencyKey(),hash,id);
            jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,original_student_id,original_attempt_id) VALUES (?,?,'ATTEMPT_SUBMITTED',?,?,?)",assessment,student,revision,student,attempt);
            return summary(student,assessment,authorize(student,assessment,attempt),head(student,assessment,attempt));
        });
        if(result==null)throw conflict("原自动补传窗口已结束，请保留内容并联系教师审批");return result;
    }
    private Context authorize(int student,int assessment,String attempt){
        if(student<1||assessment<1)throw new IllegalArgumentException("身份或考核无效");PreparedJobInput.uuid(attempt);
        access.requireAssignedStudent(student,assessment);attempts.lockAssessment(assessment);eligibility.contextForUpdate(student,assessment);
        var current=attempts.currentForUpdate(student,assessment).filter(a->a.id().equals(attempt)).orElseThrow(()->new BusinessException(403,"无权限或尝试不存在"));
        return boundContext(student,assessment,current);
    }
    private Context boundContext(int student,int assessment,PreparedAttemptStart.Summary current){
        var source=attempts.boundPaperForUpdate(assessment,current.paperVersionId());PreparedStudentPaperVerifier.verify(assessment,source.content(),source.sha256());
        var paper=PreparedPublication.decode(source.content());return new Context(current,paper,questions.answers(student,assessment,current.id(),true));
    }
    private List<Pending> pending(int student,int assessment,Context context){
        return pending(context,answer->media.status(student,assessment,context.attempt().id(),answer));
    }
    private List<Pending> pending(Context context,java.util.function.Function<String,PreparedAnswerMediaService.Summary> originalMedia){
        var result=new ArrayList<Pending>();var answers=context.answers();if(answers.size()>context.paper().questions().size())throw new IllegalStateException("原回答数量无法核验");
        for(var question:context.paper().questions()){
            var answer=answers.stream().filter(a->a.ordinal()==question.ordinal()).findFirst();
            if(answer.isEmpty()){result.add(new Pending(question.ordinal(),null,"NOT_STARTED"));continue;}
            var saved=originalMedia.apply(answer.orElseThrow().id());
            if(!saved.serverSaved())result.add(new Pending(question.ordinal(),answer.orElseThrow().id(),"NOT_QUEUED".equals(saved.phase())?answer.orElseThrow().state():saved.phase()));
        }
        return result;
    }
    private Summary summary(int student,int assessment,Context context,Optional<Head> head){
        if(head.isPresent())return verified(student,assessment,context,head.orElseThrow());
        if("SUBMITTED".equals(context.attempt().state()))throw new IllegalStateException("已提交状态缺少唯一回执");
        var pending=pending(student,assessment,context);boolean recovered=pending.isEmpty()&&supplementRecovered(student,assessment,context);
        String phase=("REQUIRES_REVIEW".equals(context.attempt().state())&&!recovered)||"CANCELLED".equals(context.attempt().state())?"REQUIRES_REVIEW":pending.isEmpty()?"READY":"WAITING_ANSWERS";
        return new Summary(context.attempt().id(),context.attempt().paperVersionId(),context.attempt().revision(),phase,false,pending,null);
    }
    /** Only expiry-origin review can finish automatically after every original media receipt is
     * valid. Capture disputes stay manual. A grant alone never makes pending bytes complete. */
    private boolean supplementRecovered(int student,int assessment,Context context){
        var attempt=context.attempt();if(!"REQUIRES_REVIEW".equals(attempt.state())||!endings.supplementReviewOnly(student,assessment,attempt.id()))return false;
        if(context.answers().size()!=context.paper().questions().size())return false;boolean approved=false;var grants=endings.supplements();
        for(var answer:context.answers()){
            var end=endings.head(student,assessment,attempt.id(),answer.id());if(end.isEmpty()||!end.orElseThrow().reasons().isEmpty()||!"SAVED".equals(answer.state()))return false;
            PreparedAnswerEndService.verifyHead(end.orElseThrow(),answer);
            var bound=new PreparedAnswerUploadService.Bound(attempt,answer,end);var grant=grants.latest(student,assessment,attempt.id(),answer.id());
            if(grant.isPresent()){grants.verify(grant.orElseThrow(),bound);approved=true;}
        }
        return approved;
    }
    private Summary verified(int student,int assessment,Context context,Head head){
        return verified(student,assessment,context,head,answer->media.status(student,assessment,context.attempt().id(),answer));
    }
    private Summary verified(int student,int assessment,Context context,Head head,java.util.function.Function<String,PreparedAnswerMediaService.Summary> originalMedia){
        var attempt=context.attempt();verifyEndWindow(attempt);
        if(!"SUBMITTED".equals(attempt.state())||!head.paper().equals(attempt.paperVersionId())||attempt.revision()<head.revision()||head.count()!=context.paper().questions().size()||head.time().isBefore(Instant.parse(attempt.answeringEndedAt())))throw new IllegalStateException("原提交回执状态损坏");
        // The locked submission verification uses each fully checked original media
        // receipt in both completeness and binding checks. Reuse only within this
        // invocation; a subsequent request must authorize and verify again.
        var checkedMedia=new HashMap<String,PreparedAnswerMediaService.Summary>();
        java.util.function.Function<String,PreparedAnswerMediaService.Summary> readMedia=answer->checkedMedia.computeIfAbsent(answer,originalMedia);
        var frozen=decode(head.json());var bindings=frozen.answers();if(frozen.schemaVersion()!=1||!PreparedJobInput.digest(encode(frozen)).equals(head.sha())||bindings.size()!=head.count()||!pending(context,readMedia).isEmpty())throw new IllegalStateException("原提交回答证明损坏");
        var rows=jdbc.query("SELECT answer_id,ordinal,transcribe_job_id,score_job_id FROM prepared_submission_answer WHERE assessment_id=? AND student_id=? AND attempt_id=? AND submission_id=? ORDER BY ordinal FOR UPDATE",
            (rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),assessment,student,attempt.id(),head.id());
        if(rows.size()!=bindings.size())throw new IllegalStateException("原处理任务绑定缺失");
        for(int n=0;n<bindings.size();n++){
            var binding=bindings.get(n);var work=binding.input();var answer=context.answers().get(n);var saved=readMedia.apply(answer.id());
            var expected=new Work(1,student,assessment,attempt.id(),attempt.paperVersionId(),head.id(),answer.id(),answer.question(),n+1,saved.revision(),saved.manifestSha256(),saved.jobId(),proofHash(saved.jobId()));
            if(!expected.equals(work)||!rows.get(n).equals(List.of(answer.id(),Integer.toString(n+1),binding.transcribeJob(),binding.scoreJob())))throw new IllegalStateException("原提交范围损坏");
            verifyJob(binding.transcribeJob(),Kind.TRANSCRIBE,work,List.of(work.mediaJob()));verifyJob(binding.scoreJob(),Kind.SCORE,work,List.of(binding.transcribeJob()));
        }
        var receipt=new Receipt(head.id(),attempt.id(),head.paper(),head.revision(),head.count(),head.time().toString());
        return new Summary(attempt.id(),head.paper(),head.revision(),"SUBMITTED",true,List.of(),receipt);
    }
    private void verifyJob(String id,Kind kind,Work work,List<String> expectedDependencies){
        var job=jobs.getForShare(id).orElseThrow(()->new IllegalStateException("原处理任务缺失"));
        var dependencies=jdbc.queryForList("SELECT depends_on_id FROM prepared_job_dependency WHERE job_id=? AND assessment_id=? AND created_by=? ORDER BY depends_on_id FOR SHARE",String.class,id,work.assessment(),work.student());
        if(job.kind()!=kind||job.actorId()!=work.student()||job.assessmentId()!=work.assessment()||job.inputVersion()!=work.answerRevision()||!PreparedJobInput.json(job.inputJson(),65536).equals(encode(work))||!dependencies.equals(expectedDependencies))throw new IllegalStateException("原处理任务输入或依赖损坏");
    }
    private Optional<Head> head(int student,int assessment,String attempt){return jdbc.query("SELECT * FROM prepared_submission WHERE assessment_id=? AND student_id=? AND attempt_id=? FOR UPDATE",
        (rs,n)->new Head(rs.getString("id"),rs.getString("paper_version_id"),rs.getLong("submitted_revision"),rs.getInt("answer_count"),rs.getTimestamp("submitted_at").toInstant(),rs.getString("snapshot_json"),rs.getString("snapshot_sha256")),assessment,student,attempt).stream().findFirst();}
    private void recordCommand(int student,int assessment,String attempt,String key,String hash,String id){jdbc.update("INSERT INTO prepared_submission_command(assessment_id,student_id,attempt_id,idempotency_key,request_sha256,submission_id) VALUES (?,?,?,?,?,?)",assessment,student,attempt,key,hash,id);}
    private static void verifyEndWindow(PreparedAttemptStart.Summary attempt){if(attempt.answeringEndedAt()==null||attempt.supplementalUploadDeadline()==null||!Instant.parse(attempt.supplementalUploadDeadline()).equals(Instant.parse(attempt.answeringEndedAt()).plusSeconds(86400)))throw new IllegalStateException("原作答结束或补传起算损坏");}
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private String proofHash(String job){return PreparedJobInput.digest(PreparedJobInput.json(jobs.getForShare(job).orElseThrow().outputJson(),262144));}
    private static String key(String operation,String submission,String answer){return UUID.nameUUIDFromBytes(("prepared-submit-v1:"+operation+":"+submission+":"+answer).getBytes(StandardCharsets.UTF_8)).toString();}
    private static String encode(Object input){try{return PreparedJobInput.json(JSON.writeValueAsString(input),65536);}catch(java.io.IOException failure){throw new IllegalStateException("原提交记录无法保存");}}
    private static Frozen decode(String input){try{return JSON.readValue(PreparedJobInput.json(input,65536),Frozen.class);}catch(java.io.IOException failure){throw new IllegalStateException("原提交记录损坏");}}
    private static BusinessException conflict(String message){return new BusinessException(409,message);}
}
