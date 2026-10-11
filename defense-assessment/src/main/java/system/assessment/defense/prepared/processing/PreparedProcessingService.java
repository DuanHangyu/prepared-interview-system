package system.assessment.defense.prepared.processing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.jobs.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static system.assessment.defense.prepared.processing.PreparedProcessing.*;
import static system.assessment.defense.prepared.processing.PreparedProcessingCodec.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;

/** Internal short-transaction result boundary. External IO/paid calls belong to separate workers.
 * Reliable receipt/original audio are not rewritten when processing fails. No public API yet. */
@Service
public class PreparedProcessingService {
    public record Execution(String provider,String model,String configSha256) {
        public Execution {identifier(provider);identifier(model);sha(configSha256);}
    }
    public record RegradeInput(int schemaVersion,PreparedSubmission.Work work,String transcriptId,long requestedRevision,int requestedBy,String reason) {}
    public record Plan(PreparedSubmissionService.ProcessingSource original,Result source,Transcript transcript,long expectedRevision,RegradeInput regrade) {}
    public record RegradeReceipt(String jobId,String answerId,long revision,String transcriptId) {}
    public record ConfirmReceipt(String answerId,long revision,String scoreId) {}
    public record AdjustReceipt(String answerId,long revision,String manualScoreId) {}
    public record ManualPage(ManualScore current,List<ManualScore> history,String nextManualScoreId){public ManualPage{history=List.copyOf(history);}}
    public record ReviewPage(long revision,boolean confirmed,system.assessment.defense.prepared.publication.PreparedPublication.Question question,
                             Result transcript,Result score,List<Result> history,String nextResultId,ManualScore manualScore){public ReviewPage{history=List.copyOf(history);}}
    private record Head(long revision,String transcript,String score,Integer confirmer,String manualScore) {}
    private record InputSnapshot(PreparedSubmission.Work original,system.assessment.defense.prepared.publication.PreparedPublication.Question question,Result transcript,long expectedRevision,RegradeInput regrade,Execution execution) {}
    record StudentGrade(String answerId,int ordinal,String body,int maxScore,java.math.BigDecimal score,String scoringSource,
                        int version,boolean confirmed,String identity,List<Sentence> sentences,String feedback) {}
    private final PreparedSubmissionService submissions;private final PreparedJobLedger jobs;private final ResourceAccessControlService access;
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final system.assessment.defense.prepared.retention.PreparedOriginalAudioReadGuard audioReads;
    public PreparedProcessingService(PreparedSubmissionService submissions,PreparedJobLedger jobs,ResourceAccessControlService access,JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.submissions=submissions;this.jobs=jobs;this.access=access;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);
        this.audioReads=new system.assessment.defense.prepared.retention.PreparedOriginalAudioReadGuard(jdbc);
    }
    public Plan plan(Lease lease){return tx.execute(s->planWithin(lease));}
    private Plan planWithin(Lease lease){
        if(lease==null||lease.job()==null||lease.job().kind()!=Kind.TRANSCRIBE&&lease.job().kind()!=Kind.SCORE)throw new IllegalArgumentException("后台任务类型无效");
        var actual=jobs.get(lease.job().id()).orElseThrow(()->conflict("原处理任务缺失"));
        if(!actual.equals(lease.job()))throw conflict("原处理任务内容或状态已变化");
        RegradeInput regrade=null;PreparedSubmission.Work work;
        var requests=jdbc.queryForList("SELECT * FROM prepared_score_request WHERE job_id=?",lease.job().id());
        if(!requests.isEmpty()){
            if(actual.kind()!=Kind.SCORE)throw conflict("原重评任务类型错误");
            regrade=decode(actual.inputJson(),RegradeInput.class);work=regrade.work();
        }else work=decode(actual.inputJson(),PreparedSubmission.Work.class);
        var original=submissions.processingSource(work); // assessment parent lock before any result/job mutation
        if(actual.assessmentId()!=work.assessment()||actual.actorId()!=work.student()||actual.inputVersion()!=work.answerRevision())throw conflict("原处理任务归属不一致");
        if(actual.kind()==Kind.TRANSCRIBE)audioReads.requireRetained(work.student(),work.assessment(),work.attempt());
        if(!jobs.heartbeat(lease,300))throw conflict("原处理任务已失去认领");
        var head=head(work,true);var binding=original.binding();
        if(actual.kind()==Kind.TRANSCRIBE){
            if(!binding.transcribeJob().equals(actual.id())||regrade!=null)throw conflict("转写任务不能引用其他回答");
            dependencies(actual,List.of(work.mediaJob()));return new Plan(original,null,null,head.revision(),null);
        }
        String transcriptId;long revision;
        if(regrade==null){
            if(!binding.scoreJob().equals(actual.id()))throw conflict("评分任务缺少原提交绑定");
            transcriptId=byJob(binding.transcribeJob()).orElseThrow(()->conflict("原完整转写结果未保存")).id();
            revision=1; // initial transcript establishes revision 1; intervening teacher action fences this score
        }else{
            verifyRegrade(regrade,actual);
            access.requireAssessmentManager(regrade.requestedBy(),work.assessment());requireManagerCurrent(regrade.requestedBy(),work.assessment());
            transcriptId=regrade.transcriptId();revision=regrade.requestedRevision();
        }
        var source=result(work,transcriptId,original);if(!"TRANSCRIBE".equals(source.kind())||!source.jobId().equals(binding.transcribeJob()))throw conflict("原转写结果不能被替换");
        dependencies(actual,List.of(source.jobId()));
        var audio=original.media().proof();var transcript=transcript(source.content(),audio.sha256(),audio.durationMs());
        if(!Objects.equals(head.transcript(),source.id()))throw conflict("原有效转写版本已变化");
        return new Plan(original,source,transcript,revision,regrade);
    }
    public Result completeTranscript(Lease lease,String content,Execution execution){return tx.execute(s->{
        var plan=planWithin(lease);if(lease.job().kind()!=Kind.TRANSCRIBE)throw new IllegalArgumentException("不是转写任务");
        var audio=plan.original().media().proof();var value=transcript(content,audio.sha256(),audio.durationMs());
        if(execution==null||!value.provider().equals(execution.provider())||!value.model().equals(execution.model()))throw conflict("转写模型元信息不一致");
        var work=plan.original().binding().input();var head=head(work,false);if(head.transcript()!=null)throw conflict("原转写结果不能覆盖");
        var result=save(lease,plan,encode(value),execution,null,true);
        if(jdbc.update("UPDATE prepared_processing_head SET transcript_id=?,revision=revision+1 WHERE answer_id=? AND revision=? AND transcript_id IS NULL",result.id(),work.answer(),head.revision())!=1)throw conflict("原转写有效版本已变化");
        audit(work,work.student(),"ANSWER_TRANSCRIBED",head.revision()+1);return result;
    });}
    public Result completeScore(Lease lease,String content,Execution execution){return tx.execute(s->{
        var plan=planWithin(lease);if(lease.job().kind()!=Kind.SCORE)throw new IllegalArgumentException("不是评分任务");
        var value=score(content,plan.original().question(),plan.source(),plan.transcript());
        var work=plan.original().binding().input();var head=head(work,false);
        boolean effective=head.revision()==plan.expectedRevision()&&head.confirmer()==null&&Objects.equals(head.transcript(),plan.source().id());
        var result=save(lease,plan,encode(value),execution,plan.source().id(),effective);
        if(effective&&jdbc.update("UPDATE prepared_processing_head SET score_id=?,manual_score_id=NULL,revision=revision+1 WHERE answer_id=? AND revision=? AND confirmed_by IS NULL",result.id(),work.answer(),plan.expectedRevision())!=1)throw conflict("有效评分版本冲突");
        if(effective)completeAttemptIfReady(work);
        audit(work,plan.regrade()==null?work.student():plan.regrade().requestedBy(),effective?"ANSWER_SCORED":"SCORE_HISTORY_ONLY",head.revision());return result;
    });}
    /** Management operations retain the original subject on scoped jobs; the real requester is
     * separately immutable/audited. Enqueue is not a paid call and no old result is deleted. */
    public RegradeReceipt regrade(int actor,int assessment,String attempt,String answer,RegradeCommand command){
        if(command==null||command.expectedRevision()<0)throw new IllegalArgumentException("重评命令缺失或版本无效");
        PreparedJobInput.uuid(command.idempotencyKey());PreparedJobInput.uuid(command.transcriptId());text(command.reason(),1000);
        return tx.execute(s->{
            var work=managerWork(actor,assessment,attempt,answer);String hash=PreparedJobInput.digest(encode(command));
            var previous=command(actor,work,"REGRADE",command.idempotencyKey(),hash,RegradeReceipt.class);if(previous.isPresent())return previous.orElseThrow();
            var head=head(work,false);if(head.revision()!=command.expectedRevision())throw conflict("评阅版本已变化");
            effectiveManual(work,submissions.processingSource(work),head);
            var source=result(work,command.transcriptId());if(!"TRANSCRIBE".equals(source.kind())||!source.id().equals(head.transcript()))throw conflict("必须使用此原回答的有效完整转写");
            long revision=head.revision()+1;var input=new RegradeInput(1,work,source.id(),revision,actor,command.reason());
            String key=UUID.nameUUIDFromBytes(("prepared-regrade-v1:"+actor+":"+answer+":"+command.idempotencyKey()).getBytes(StandardCharsets.UTF_8)).toString();
            var job=jobs.enqueueAfter(work.student(),assessment,Kind.SCORE,work.answerRevision(),key,encode(input),List.of(source.jobId()));
            jdbc.update("INSERT INTO prepared_score_request(job_id,assessment_id,student_id,attempt_id,answer_id,transcript_id,requested_revision,requested_by,reason) VALUES (?,?,?,?,?,?,?,?,?)",job.id(),assessment,work.student(),attempt,answer,source.id(),revision,actor,command.reason());
            jdbc.update("UPDATE prepared_processing_head SET revision=?,confirmed_by=NULL,confirmed_at=NULL WHERE answer_id=?",revision,answer);
            var receipt=new RegradeReceipt(job.id(),answer,revision,source.id());recordCommand(actor,work,"REGRADE",command.idempotencyKey(),hash,command,receipt);audit(work,actor,"REGRADE_REQUESTED",revision);return receipt;
        });
    }
    public ConfirmReceipt confirm(int actor,int assessment,String attempt,String answer,ConfirmCommand command){
        if(command==null||command.expectedRevision()<0)throw new IllegalArgumentException("核分命令缺失或版本无效");
        PreparedJobInput.uuid(command.idempotencyKey());PreparedJobInput.uuid(command.scoreId());text(command.reason(),1000);
        return tx.execute(s->{
            var work=managerWork(actor,assessment,attempt,answer);String hash=PreparedJobInput.digest(encode(command));
            var previous=command(actor,work,"CONFIRM",command.idempotencyKey(),hash,ConfirmReceipt.class);if(previous.isPresent())return previous.orElseThrow();
            var head=head(work,false);var score=result(work,command.scoreId());
            if(head.revision()!=command.expectedRevision())throw conflict("评阅版本已变化");
            if(head.manualScore()!=null)throw conflict("当前有效成绩为人工评分，不能确认被替代的模型评分");
            if(!"SCORE".equals(score.kind())||!Objects.equals(head.score(),score.id())||!Objects.equals(head.transcript(),score.sourceResultId()))throw conflict("只能确认此原回答的当前有效评分");
            long revision=head.revision()+1;jdbc.update("UPDATE prepared_processing_head SET revision=?,confirmed_by=?,confirmed_at=CURRENT_TIMESTAMP(3) WHERE answer_id=?",revision,actor,answer);
            var receipt=new ConfirmReceipt(answer,revision,score.id());recordCommand(actor,work,"CONFIRM",command.idempotencyKey(),hash,command,receipt);audit(work,actor,"SCORE_CONFIRMED",revision);return receipt;
        });
    }
    /** Human grading requires complete submitted original media, but no successful model.
     * Same-version updates serialize under the original parent/head locks; retries recover
     * the exact original immutable decision even after later revisions. */
    public AdjustReceipt adjust(int actor,int assessment,String attempt,String answer,AdjustCommand command){
        PreparedManualScoreStore.validate(command);
        return tx.execute(s->{
            var work=managerWork(actor,assessment,attempt,answer);var original=submissions.processingSource(work);
            String hash=PreparedJobInput.digest(encode(command));
            var previous=command(actor,work,"ADJUST",command.idempotencyKey(),hash,AdjustReceipt.class);if(previous.isPresent())return previous.orElseThrow();
            requireHeadNotLost(work);var head=head(work,true);
            if(head.revision()!=command.expectedRevision())throw conflict("评阅版本已变化，请读取最新成绩后决定");
            if(command.score().compareTo(java.math.BigDecimal.valueOf(original.question().maxScore()))>0)throw new IllegalArgumentException("人工分数不能超过该题冻结满分");
            if(head.transcript()!=null&&!"TRANSCRIBE".equals(result(work,head.transcript(),original).kind()))throw new IllegalStateException("原转写指针类型损坏");
            if(head.score()!=null){var base=result(work,head.score(),original);if(!"SCORE".equals(base.kind())||!Objects.equals(base.sourceResultId(),head.transcript()))throw new IllegalStateException("原评分来源损坏");}
            effectiveManual(work,original,head);
            var id=new PreparedManualScoreStore(jdbc).save(work,original,actor,command,head.transcript(),head.score(),head.manualScore());
            long revision=command.expectedRevision()+1;
            if(jdbc.update("UPDATE prepared_processing_head SET manual_score_id=?,revision=?,confirmed_by=?,confirmed_at=CURRENT_TIMESTAMP(3) WHERE answer_id=? AND revision=?",id,revision,actor,answer,head.revision())!=1)throw conflict("人工调分版本冲突");
            var receipt=new AdjustReceipt(answer,revision,id);recordCommand(actor,work,"ADJUST",command.idempotencyKey(),hash,command,receipt);
            var value=manual(work,original,id);
            audit(work,actor,"SCORE_ADJUSTED",value.revision());completeAttemptIfReady(work);return receipt;
        });
    }
    public ManualPage manualHistory(int actor,int assessment,String attempt,String answer,String after,int limit){
        if(limit<1||limit>20)throw new IllegalArgumentException("人工评分历史每页须为 1 至 20 项");if(after!=null)PreparedJobInput.uuid(after);
        return tx.execute(s->{var original=managerReadSource(actor,assessment,attempt,answer);var work=original.binding().input();requireHeadNotLost(work);
            if(!hasHead(work)){if(after!=null)throw conflict("人工评分历史游标无法核验");return new ManualPage(null,List.of(),null);}
            var current=effectiveManual(work,original,head(work,false));if(after!=null)manual(work,original,after);
            var ids=new PreparedManualScoreStore(jdbc).ids(work,after,limit);var shown=ids.stream().limit(limit).toList();
            return new ManualPage(current,shown.stream().map(id->manual(work,original,id)).toList(),ids.size()>limit?shown.getLast():null);
        });
    }
    public View review(int actor,int assessment,String attempt,String answer){return tx.execute(s->{var original=managerReadSource(actor,assessment,attempt,answer);var work=original.binding().input();
        requireHeadNotLost(work);
        if(!hasHead(work)){
            if(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_processing_result WHERE answer_id=?",Integer.class,answer)!=0)throw new IllegalStateException("原有效结果头缺失");
            return new View(answer,0,null,null,false,List.of(),null);
        }
        var head=head(work,false);
        var history=jdbc.queryForList("SELECT id FROM prepared_processing_result WHERE answer_id=? ORDER BY completed_at,id",String.class,answer).stream().map(id->result(work,id,original)).toList();
        if(head.transcript()!=null&&!"TRANSCRIBE".equals(result(work,head.transcript(),original).kind()))throw new IllegalStateException("原转写指针类型损坏");
        if(head.score()!=null){var score=result(work,head.score(),original);if(!"SCORE".equals(score.kind())||!Objects.equals(score.sourceResultId(),head.transcript()))throw new IllegalStateException("原评分指针类型或来源损坏");}
        effectiveManual(work,original,head);
        return new View(answer,head.revision(),head.transcript(),head.score(),head.confirmer()!=null,history,head.manualScore());
    });}
    /** Teacher adapter only. It does not publish student results or relax Worker authorization. */
    public String reviewTheme(int actor,int assessment){
        if(actor<1||assessment<1)throw new IllegalArgumentException("管理身份或考核无效");
        return tx.execute(s->{access.requireAssessmentManager(actor,assessment);requireManagerCurrent(actor,assessment);
            return jdbc.queryForObject("SELECT theme FROM assessment WHERE id=? AND deleted=0",String.class,assessment);});
    }
    /** Authorized current/historical submitted source. Never grants Worker or student writes. */
    public system.assessment.defense.prepared.storage.PreparedPrivateOssStore.StoredAnswer reviewMedia(int actor,int assessment,String attempt,String answer){
        return tx.execute(s->{var original=managerReadSource(actor,assessment,attempt,answer);var work=original.binding().input();
            audioReads.requireRetained(work.student(),work.assessment(),work.attempt());return original.media();});
    }
    /** Persist before external IO; fresh canonical authorization and original lifecycle
     * are checked in the same parent transaction. This is not a playback capability. */
    public system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal.Handle beginReviewAudioIo(int actor,int assessment,String attempt,String answer,
            system.assessment.defense.prepared.storage.PreparedPrivateOssStore.StoredAnswer expected){
        return tx.execute(s->{var original=managerReadSource(actor,assessment,attempt,answer);var work=original.binding().input();
            if(!original.media().equals(expected))throw conflict("原完整录音来源已变化，请重新授权");
            var scope=new system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal.Scope(actor,work.student(),assessment,attempt,answer,work.answerRevision()-1,work.manifestSha256());
            return new system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal(jdbc).open(scope,system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal.Kind.PREVIEW);});
    }
    public void endAudioIo(system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal.Handle handle){
        tx.executeWithoutResult(s->new system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal(jdbc).close(handle));
    }
    public system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal.Handle beginTranscriptionAudioIo(Lease lease,Plan expected){
        if(expected==null)throw new IllegalArgumentException("原文件转写来源缺失");
        return tx.execute(s->{var original=planWithin(lease).original();var work=original.binding().input();
            if(lease.job().kind()!=Kind.TRANSCRIBE||!original.equals(expected.original()))throw conflict("原文件转写来源已变化");
            var scope=new system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal.Scope(work.student(),work.student(),work.assessment(),work.attempt(),work.answer(),work.answerRevision()-1,work.manifestSha256());
            return new system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal(jdbc).open(scope,system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal.Kind.TRANSCRIBE);});
    }
    /** Persist the actual supplier capability BEFORE paid dispatch. Never persist its URL.
     * Input-fetch expiry does not extend any answer/upload deadline or reset retention. */
    public void recordAsrInputAccess(Lease lease,system.assessment.defense.prepared.storage.PreparedPrivateOssStore.StoredAnswer expected,
            java.time.Instant expires,java.time.Instant observedNow){
        if(expected==null||expires==null||observedNow==null||expires.isBefore(observedNow.plusSeconds(82800))||expires.isAfter(observedNow.plusSeconds(86400)))throw new IllegalArgumentException("原文件转写读取期限无效");
        tx.executeWithoutResult(s->{var original=planWithin(lease).original();var work=original.binding().input();
            if(lease.job().kind()!=Kind.TRANSCRIBE||!original.media().equals(expected))throw conflict("原文件转写来源已变化");
            jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,resource_id,resource_sha256,authorization_expires_at,original_student_id,original_attempt_id) VALUES (?,?,'ORIGINAL_ASR_INPUT_AUTHORIZED',?,?,?,?,?,?)",
                work.assessment(),work.student(),work.answerRevision(),work.answer(),expected.proof().sha256(),java.sql.Timestamp.from(expires),work.student(),work.attempt());
        });
    }
    /** Final fresh authorization and audit after remote IO. Revocation/source changes suppress
     * delivery; an already delivered bearer capability remains bounded to five minutes. */
    public void recordAudioAccess(int actor,int assessment,String attempt,String answer,
            system.assessment.defense.prepared.storage.PreparedPrivateOssStore.StoredAnswer expected,java.time.Instant expires){
        if(expected==null||expires==null)throw new IllegalArgumentException("原录音播放授权缺失");
        tx.executeWithoutResult(s->{var source=managerReadSource(actor,assessment,attempt,answer);var work=source.binding().input();var original=source.media();
            if(!original.equals(expected))throw conflict("原完整录音来源已变化，请重新授权");
            audioReads.requireRetained(work.student(),work.assessment(),work.attempt());
            jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,resource_id,resource_sha256,authorization_expires_at,original_student_id,original_attempt_id) VALUES (?,?,'ORIGINAL_AUDIO_AUTHORIZED',?,?,?,?,?,?)",
                assessment,actor,work.answerRevision(),answer,original.proof().sha256(),java.sql.Timestamp.from(expires),work.student(),work.attempt());
        });
    }
    /** Bounded keyset history. Effective source/score remain explicit even on older history pages.
     * Original receipt/provenance and current manager permission are validated in this transaction. */
    public ReviewPage reviewPage(int actor,int assessment,String attempt,String answer,String after,int limit){
        if(limit<1||limit>20)throw new IllegalArgumentException("评阅历史每页须为 1 至 20 项");if(after!=null)PreparedJobInput.uuid(after);
        return tx.execute(s->{var original=managerReadSource(actor,assessment,attempt,answer);var work=original.binding().input();
            requireHeadNotLost(work);
            if(!hasHead(work)){
                if(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_processing_result WHERE answer_id=?",Integer.class,answer)!=0||after!=null)throw conflict("原结果头或历史游标无法核验");
                return new ReviewPage(0,false,original.question(),null,null,List.of(),null,null);
            }
            var head=head(work,false);Result transcript=head.transcript()==null?null:result(work,head.transcript(),original);
            Result score=head.score()==null?null:result(work,head.score(),original);
            if(transcript!=null&&!"TRANSCRIBE".equals(transcript.kind())||score!=null&&(!"SCORE".equals(score.kind())||!Objects.equals(score.sourceResultId(),head.transcript())))throw new IllegalStateException("原有效结果指针损坏");
            List<String> ids;
            if(after==null)ids=jdbc.queryForList("SELECT id FROM prepared_processing_result WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? ORDER BY completed_at DESC,id DESC LIMIT ?",String.class,assessment,work.student(),attempt,answer,limit+1);
            else{
                result(work,after,original);var time=jdbc.queryForObject("SELECT completed_at FROM prepared_processing_result WHERE id=?",java.sql.Timestamp.class,after);
                ids=jdbc.queryForList("SELECT id FROM prepared_processing_result WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? AND (completed_at<? OR (completed_at=? AND id<?)) ORDER BY completed_at DESC,id DESC LIMIT ?",String.class,assessment,work.student(),attempt,answer,time,time,after,limit+1);
            }
            var shown=ids.stream().limit(limit).toList();var history=shown.stream().map(id->result(work,id,original)).toList();
            return new ReviewPage(head.revision(),head.confirmer()!=null,original.question(),transcript,score,history,ids.size()>limit?shown.getLast():null,effectiveManual(work,original,head));
        });
    }
    private PreparedSubmission.Work managerWork(int actor,int assessment,String attempt,String answer){
        var work=managerSubmissionWork(actor,assessment,attempt,answer);
        submissions.processingSource(work);requireManagerCurrent(actor,assessment);return work;
    }
    /** Package-private projection of a source verified in the caller's same transaction.
     * No manager impersonation, history, rubric, object URL or provider envelope is returned. */
    StudentGrade studentGrade(PreparedSubmissionService.ProcessingSource original){
        var work=original.binding().input();requireHeadNotLost(work);if(!hasHead(work))return null;
        var head=head(work,false);var source=head.transcript()==null?null:result(work,head.transcript(),original);
        var score=head.score()==null?null:result(work,head.score(),original);
        if(source!=null&&!"TRANSCRIBE".equals(source.kind())||score!=null&&(!"SCORE".equals(score.kind())||!Objects.equals(score.sourceResultId(),head.transcript())))throw new IllegalStateException("原有效结果指针损坏");
        var transcript=source==null?null:transcript(source.content(),original.media().proof().sha256(),original.media().proof().durationMs());
        var manual=effectiveManual(work,original,head);var question=original.question();
        if(manual!=null)return new StudentGrade(work.answer(),work.ordinal(),question.body(),question.maxScore(),manual.score(),"TEACHER",manual.version(),true,
            PreparedJobInput.digest(encode(Map.of("headRevision",head.revision(),"resultId",manual.id(),"resultRevision",manual.revision()))),transcript==null?null:transcript.sentences(),null);
        if(score==null)return null;
        if(!decode(jobs.get(score.jobId()).orElseThrow().outputJson(),Receipt.class).effective())throw new IllegalStateException("非有效历史评分不能成为学生成绩");
        var value=score(score.content(),question,source,transcript);
        return new StudentGrade(work.answer(),work.ordinal(),question.body(),question.maxScore(),value.score(),"MODEL",score.version(),head.confirmer()!=null,
            PreparedJobInput.digest(encode(Map.of("headRevision",head.revision(),"resultId",score.id(),"contentSha256",score.contentSha256()))),transcript.sentences(),value.feedback());
    }
    /** Internal retention boolean only. Sources were verified under the same parent
     * lock by retentionSources; no user result, body or permission is returned. */
    public boolean retentionResultsComplete(List<PreparedSubmissionService.ProcessingSource> originals){
        requireRetentionTransaction();
        if(originals==null||originals.isEmpty())return false;
        boolean complete=true;for(var original:originals)if(studentGrade(original)==null)complete=false;return complete;
    }
    /** Reuse canonical provenance for all original processing work, including late
     * regrades. Failed attribution remains protected, never silently ignored. */
    public boolean retentionJobMatches(Job job,PreparedSubmissionService.ProcessingSource original){
        requireRetentionTransaction();if(job==null||original==null)throw new IllegalArgumentException("原留存处理依据缺失");
        var binding=original.binding();var work=binding.input();
        if(job.assessmentId()!=work.assessment()||job.actorId()!=work.student())return false;
        if(job.id().equals(work.mediaJob()))return job.kind()==Kind.ANSWER_MEDIA&&job.inputVersion()==work.answerRevision()-1;
        if(job.inputVersion()!=work.answerRevision())return false;
        if(job.id().equals(binding.transcribeJob())||job.id().equals(binding.scoreJob())){
            var kind=job.id().equals(binding.transcribeJob())?Kind.TRANSCRIBE:Kind.SCORE;
            if(job.kind()!=kind||!encode(work).equals(PreparedJobInput.json(job.inputJson(),65536)))return false;
        }else{
            if(job.kind()!=Kind.SCORE)return false;
            final RegradeInput input;try{input=decode(job.inputJson(),RegradeInput.class);}catch(IllegalArgumentException invalid){return false;}
            if(!input.work().equals(work))return false;verifyRegrade(input,job);
            var transcript=result(work,input.transcriptId(),original);if(!"TRANSCRIBE".equals(transcript.kind()))throw new IllegalStateException("原留存重评来源无效");
            dependencies(job,List.of(transcript.jobId()));
        }
        if(job.state()==State.SUCCEEDED)result(work,byJob(job.id()).orElseThrow(()->new IllegalStateException("原留存成功任务缺少结果")).id(),original);
        return true;
    }
    private static void requireRetentionTransaction(){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("原留存处理核验需要短事务");
    }
    private PreparedSubmissionService.ProcessingSource managerReadSource(int actor,int assessment,String attempt,String answer){
        return submissions.reviewSource(actor,managerSubmissionWork(actor,assessment,attempt,answer));
    }
    private PreparedSubmission.Work managerSubmissionWork(int actor,int assessment,String attempt,String answer){
        if(actor<1||assessment<1)throw new IllegalArgumentException("管理身份或考核无效");access.requireAssessmentManager(actor,assessment);PreparedJobInput.uuid(attempt);PreparedJobInput.uuid(answer);
        var ids=jdbc.queryForList("SELECT transcribe_job_id FROM prepared_submission_answer WHERE assessment_id=? AND attempt_id=? AND answer_id=?",String.class,assessment,attempt,answer);
        if(ids.isEmpty())throw new BusinessException(403,"无权限或原提交回答不存在");
        var work=decode(jobs.get(ids.getFirst()).orElseThrow().inputJson(),PreparedSubmission.Work.class);
        if(work.assessment()!=assessment||!attempt.equals(work.attempt())||!answer.equals(work.answer()))throw new BusinessException(403,"原回答处理范围不一致");
        return work;
    }
    private void requireManagerCurrent(int actor,int assessment){
        var allowed=jdbc.queryForList("""
            SELECT a.id FROM assessment a JOIN `user` u ON u.id=? WHERE a.id=? AND a.deleted=0
            AND (u.type=2 OR (u.type=1 AND (a.create_id=u.id OR EXISTS(SELECT 1 FROM assessment_invite i WHERE i.assessment_id=a.id AND i.invitee_teacher_id=u.id))))
            FOR UPDATE
            """,Integer.class,actor,assessment);
        if(allowed.isEmpty())throw new BusinessException(403,"管理权限已变化或考核不存在");
    }
    private void completeAttemptIfReady(PreparedSubmission.Work work){
        if(jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=? FOR UPDATE",java.sql.Timestamp.class,work.attempt())!=null)return;
        var rows=jdbc.queryForList("""
            SELECT b.answer_id,b.transcribe_job_id,h.transcript_id,h.score_id,h.manual_score_id FROM prepared_submission_answer b
            LEFT JOIN prepared_processing_head h ON h.answer_id=b.answer_id
            WHERE b.assessment_id=? AND b.student_id=? AND b.attempt_id=? ORDER BY b.ordinal FOR UPDATE
            """,work.assessment(),work.student(),work.attempt());
        if(rows.isEmpty())throw new IllegalStateException("原提交回答绑定缺失");
        if(rows.stream().anyMatch(row->row.get("score_id")==null&&row.get("manual_score_id")==null))return;
        for(var row:rows){
            var input=decode(jobs.get((String)row.get("transcribe_job_id")).orElseThrow().inputJson(),PreparedSubmission.Work.class);
            if(input.assessment()!=work.assessment()||input.student()!=work.student()||!input.attempt().equals(work.attempt())||!input.answer().equals(row.get("answer_id")))throw new IllegalStateException("整场结果原归属损坏");
            var original=submissions.processingSource(input);
            if(row.get("manual_score_id")!=null){effectiveManual(input,original,head(input,false));continue;}
            var score=result(input,(String)row.get("score_id"),original);
            var receipt=decode(jobs.get(score.jobId()).orElseThrow().outputJson(),Receipt.class);
            if(!"SCORE".equals(score.kind())||!Objects.equals(score.sourceResultId(),row.get("transcript_id"))||!receipt.effective())throw new IllegalStateException("整场结果不能以非空评分标记代替有效结果");
        }
        if(jdbc.update("UPDATE prepared_attempt SET first_results_completed_at=CURRENT_TIMESTAMP(3) WHERE id=? AND assessment_id=? AND student_id=? AND state='SUBMITTED' AND first_results_completed_at IS NULL",work.attempt(),work.assessment(),work.student())!=1)throw conflict("原结果完成状态已变化");
    }
    private Head head(PreparedSubmission.Work work,boolean initialize){
        if(initialize)jdbc.update("INSERT INTO prepared_processing_head(assessment_id,student_id,attempt_id,answer_id) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE answer_id=VALUES(answer_id)",work.assessment(),work.student(),work.attempt(),work.answer());
        var heads=jdbc.query("SELECT * FROM prepared_processing_head WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? FOR UPDATE",(rs,n)->new Head(rs.getLong("revision"),rs.getString("transcript_id"),rs.getString("score_id"),(Integer)rs.getObject("confirmed_by"),rs.getString("manual_score_id")),work.assessment(),work.student(),work.attempt(),work.answer());
        return heads.stream().findFirst().orElseThrow(()->conflict("原有效结果头缺失"));
    }
    private void requireHeadNotLost(PreparedSubmission.Work work){
        if(!hasHead(work)&&(!jdbc.queryForList("SELECT id FROM prepared_processing_result WHERE answer_id=? LIMIT 1 FOR UPDATE",String.class,work.answer()).isEmpty()
            ||!jdbc.queryForList("SELECT id FROM prepared_manual_score WHERE answer_id=? LIMIT 1 FOR UPDATE",String.class,work.answer()).isEmpty()))throw new IllegalStateException("原有效结果头缺失");
    }
    private boolean hasHead(PreparedSubmission.Work work){return !jdbc.queryForList("SELECT answer_id FROM prepared_processing_head WHERE answer_id=? FOR UPDATE",String.class,work.answer()).isEmpty();}
    private ManualScore effectiveManual(PreparedSubmission.Work work,PreparedSubmissionService.ProcessingSource original,Head head){
        if(head.manualScore()==null)return null;var value=manual(work,original,head.manualScore());
        if(value.revision()>head.revision()||!Objects.equals(value.scoreId(),head.score())
            ||value.transcriptId()!=null&&!Objects.equals(value.transcriptId(),head.transcript())
            ||head.confirmer()!=null&&head.confirmer()!=value.recordedBy())throw new IllegalStateException("原人工有效评分指针损坏");
        return value;
    }
    private ManualScore manual(PreparedSubmission.Work work,PreparedSubmissionService.ProcessingSource original,String id){
        var value=new PreparedManualScoreStore(jdbc).read(work,original,id);
        if(value.transcriptId()!=null&&!"TRANSCRIBE".equals(result(work,value.transcriptId(),original).kind()))throw new IllegalStateException("人工评分引用的原转写损坏");
        if(value.scoreId()!=null){var base=result(work,value.scoreId(),original);if(!"SCORE".equals(base.kind())||!Objects.equals(base.sourceResultId(),value.transcriptId()))throw new IllegalStateException("人工评分引用的原模型结果损坏");}
        return value;
    }
    private Result save(Lease lease,Plan plan,String content,Execution execution,String source,boolean effective){
        if(execution==null)throw new IllegalArgumentException("实际模型元信息缺失");
        var work=plan.original().binding().input();String kind=lease.job().kind().name();
        int version=Objects.requireNonNull(jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM prepared_processing_result WHERE answer_id=? AND kind=?",Integer.class,work.answer(),kind));
        String id=UUID.nameUUIDFromBytes(("prepared-result-v1:"+lease.job().id()).getBytes(StandardCharsets.UTF_8)).toString();
        String inputJson=encodeInput(new InputSnapshot(work,plan.original().question(),plan.source(),plan.expectedRevision(),plan.regrade(),execution)),input=PreparedJobInput.digest(inputJson),hash=PreparedJobInput.digest(content);
        jdbc.update("INSERT INTO prepared_processing_result(id,assessment_id,student_id,attempt_id,answer_id,job_id,kind,version_no,input_sha256,content_sha256,content_json,provider,model,config_sha256,source_result_id,input_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,work.assessment(),work.student(),work.attempt(),work.answer(),lease.job().id(),kind,version,input,hash,content,execution.provider(),execution.model(),execution.configSha256(),source,inputJson);
        if(!jobs.succeed(lease,encode(new Receipt(1,id,work.answer(),kind,version,hash,effective))))throw conflict("处理租约或费用核算未完成，不能提交结果");
        return result(work,id,plan.original());
    }
    private Optional<Result> byJob(String job){return jdbc.query("SELECT * FROM prepared_processing_result WHERE job_id=?",(rs,n)->row(rs),job).stream().findFirst();}
    private Result result(PreparedSubmission.Work work,String id){
        return result(work,id,submissions.processingSource(work));
    }
    /** A source can be reused only inside this same short transaction while its parent and
     * original scope locks are held. No cross-request or cross-worker authorization cache. */
    private Result result(PreparedSubmission.Work work,String id,PreparedSubmissionService.ProcessingSource original){
        PreparedJobInput.uuid(id);var result=jdbc.query("SELECT * FROM prepared_processing_result WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? AND id=?",(rs,n)->row(rs),work.assessment(),work.student(),work.attempt(),work.answer(),id).stream().findFirst().orElseThrow(()->conflict("结果不属于原提交回答"));
        if(!PreparedJobInput.digest(result.content()).equals(result.contentSha256()))throw new IllegalStateException("原结果内容摘要损坏");
        String input=jdbc.queryForObject("SELECT input_json FROM prepared_processing_result WHERE id=?",String.class,id);
        var frozen=decodeInput(input,InputSnapshot.class);
        if(!PreparedJobInput.digest(encodeInput(frozen)).equals(result.inputSha256())||!work.equals(frozen.original())||!original.question().equals(frozen.question())
            ||!new Execution(result.provider(),result.model(),result.configSha256()).equals(frozen.execution()))throw new IllegalStateException("原结果输入或模型绑定损坏");
        var job=jobs.get(result.jobId()).orElseThrow();var receipt=decode(job.outputJson(),Receipt.class);
        if(job.state()!=State.SUCCEEDED||!job.kind().name().equals(result.kind())||job.assessmentId()!=work.assessment()||job.actorId()!=work.student()
            ||job.inputVersion()!=work.answerRevision()||receipt.schemaVersion()!=1||!receipt.resultId().equals(result.id())||!receipt.answerId().equals(work.answer())
            ||!receipt.kind().equals(result.kind())||receipt.version()!=result.version()||!receipt.contentSha256().equals(result.contentSha256()))throw new IllegalStateException("原任务与结果回执损坏");
        if("TRANSCRIBE".equals(result.kind())){
            if(frozen.transcript()!=null||frozen.regrade()!=null||result.sourceResultId()!=null||!original.binding().transcribeJob().equals(job.id()))throw new IllegalStateException("原转写输入损坏");
            var value=transcript(result.content(),original.media().proof().sha256(),original.media().proof().durationMs());
            if(!value.provider().equals(result.provider())||!value.model().equals(result.model()))throw new IllegalStateException("原转写模型损坏");
            dependencies(job,List.of(work.mediaJob()));
        }else{
            var sourceKind=jdbc.queryForList("SELECT kind FROM prepared_processing_result WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? AND id=?",String.class,work.assessment(),work.student(),work.attempt(),work.answer(),result.sourceResultId());
            if(sourceKind.size()!=1||!"TRANSCRIBE".equals(sourceKind.getFirst()))throw new IllegalStateException("原评分来源不是转写，拒绝递归引用");
            var source=result(work,result.sourceResultId(),original);if(!source.equals(frozen.transcript()))throw new IllegalStateException("原评分转写版本损坏");
            var transcript=transcript(source.content(),original.media().proof().sha256(),original.media().proof().durationMs());score(result.content(),original.question(),source,transcript);
            if(frozen.regrade()==null){if(!original.binding().scoreJob().equals(job.id())||frozen.expectedRevision()!=1)throw new IllegalStateException("原评分输入损坏");}
            else {if(!encode(frozen.regrade()).equals(PreparedJobInput.json(job.inputJson(),262144))||frozen.expectedRevision()!=frozen.regrade().requestedRevision())throw new IllegalStateException("原重评输入损坏");verifyRegrade(frozen.regrade(),job);}
            dependencies(job,List.of(source.jobId()));
        }
        return result;
    }
    private static Result row(java.sql.ResultSet rs)throws java.sql.SQLException{return new Result(rs.getString("id"),rs.getString("job_id"),rs.getString("answer_id"),rs.getString("kind"),rs.getInt("version_no"),rs.getString("input_sha256"),rs.getString("content_sha256"),PreparedJobInput.json(rs.getString("content_json"),262144),rs.getString("source_result_id"),rs.getTimestamp("completed_at").toInstant().toString(),rs.getString("provider"),rs.getString("model"),rs.getString("config_sha256"));}
    private void dependencies(Job job,List<String> expected){var actual=jdbc.queryForList("SELECT depends_on_id FROM prepared_job_dependency WHERE job_id=? AND assessment_id=? AND created_by=? ORDER BY depends_on_id",String.class,job.id(),job.assessmentId(),job.actorId());if(!actual.equals(expected))throw conflict("处理依赖不属于原输入");}
    private void verifyRegrade(RegradeInput input,Job job){
        var rows=jdbc.queryForList("SELECT * FROM prepared_score_request WHERE job_id=?",job.id());
        if(rows.size()!=1)throw conflict("原重评任务登记缺失");var row=rows.getFirst();var work=input.work();
        if(input.schemaVersion()!=1||input.requestedRevision()<1||input.requestedBy()<1||!work.equals(decode(job.inputJson(),RegradeInput.class).work())
            ||!Objects.equals(row.get("assessment_id"),work.assessment())||!Objects.equals(row.get("student_id"),work.student())
            ||!Objects.equals(row.get("attempt_id"),work.attempt())||!Objects.equals(row.get("answer_id"),work.answer())
            ||!Objects.equals(row.get("transcript_id"),input.transcriptId())||((Number)row.get("requested_revision")).longValue()!=input.requestedRevision()
            ||!Objects.equals(row.get("requested_by"),input.requestedBy())||!Objects.equals(row.get("reason"),input.reason())
            ||job.kind()!=Kind.SCORE||job.assessmentId()!=work.assessment()||job.actorId()!=work.student()||job.inputVersion()!=work.answerRevision()
            ||!encode(input).equals(PreparedJobInput.json(job.inputJson(),262144)))throw conflict("原重评输入或归属损坏");
    }
    private <T>Optional<T> command(int actor,PreparedSubmission.Work work,String action,String key,String hash,Class<T> type){
        var rows=jdbc.queryForList("SELECT request_sha256,request_json,receipt_json FROM prepared_result_command WHERE assessment_id=? AND actor_id=? AND answer_id=? AND action=? AND idempotency_key=? FOR UPDATE",work.assessment(),actor,work.answer(),action,key);
        if(rows.isEmpty())return Optional.empty();if(!hash.equals(rows.getFirst().get("request_sha256")))throw conflict("相同操作标识不能修改原请求");
        if(!hash.equals(PreparedJobInput.digest(PreparedJobInput.json((String)rows.getFirst().get("request_json"),262144))))throw new IllegalStateException("原管理命令证明损坏");
        var receipt=decode((String)rows.getFirst().get("receipt_json"),type);String request=(String)rows.getFirst().get("request_json");
        if(receipt instanceof RegradeReceipt recovered){
            var original=decode(request,RegradeCommand.class);var job=jobs.get(recovered.jobId()).orElseThrow(()->conflict("原重评回执任务缺失"));var input=decode(job.inputJson(),RegradeInput.class);
            if(!recovered.answerId().equals(work.answer())||!recovered.transcriptId().equals(original.transcriptId())||recovered.revision()!=original.expectedRevision()+1
                ||!input.work().equals(work)||input.requestedBy()!=actor||!input.reason().equals(original.reason())||input.requestedRevision()!=recovered.revision()
                ||!input.transcriptId().equals(recovered.transcriptId()))throw new IllegalStateException("原重评命令回执损坏");
            verifyRegrade(input,job);var source=result(work,recovered.transcriptId());if(!"TRANSCRIBE".equals(source.kind()))throw new IllegalStateException("原重评来源损坏");dependencies(job,List.of(source.jobId()));
        }else if(receipt instanceof ConfirmReceipt recovered){
            var original=decode(request,ConfirmCommand.class);var score=result(work,recovered.scoreId());
            if(!recovered.answerId().equals(work.answer())||!recovered.scoreId().equals(original.scoreId())||recovered.revision()!=original.expectedRevision()+1||!"SCORE".equals(score.kind()))throw new IllegalStateException("原核分命令回执损坏");
        }else if(receipt instanceof AdjustReceipt recovered){
            var original=decode(request,AdjustCommand.class);var value=manual(work,submissions.processingSource(work),recovered.manualScoreId());
            if(!recovered.answerId().equals(work.answer())||recovered.revision()!=original.expectedRevision()+1||value.revision()!=recovered.revision()
                ||value.recordedBy()!=actor||!value.score().equals(original.score())||!value.reason().equals(original.reason()))throw new IllegalStateException("原人工调分命令回执损坏");
        }
        return Optional.of(receipt);
    }
    private void recordCommand(int actor,PreparedSubmission.Work work,String action,String key,String hash,Object request,Object receipt){jdbc.update("INSERT INTO prepared_result_command(assessment_id,actor_id,answer_id,action,idempotency_key,request_sha256,receipt_json,request_json) VALUES (?,?,?,?,?,?,?,?)",work.assessment(),actor,work.answer(),action,key,hash,encode(receipt),encode(request));}
    private void audit(PreparedSubmission.Work work,int actor,String action,long revision){jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,original_student_id,original_attempt_id,resource_id) VALUES (?,?,?,?,?,?,?)",work.assessment(),actor,action,revision,work.student(),work.attempt(),work.answer());}
    private static BusinessException conflict(String message){return new BusinessException(409,message);}
}
