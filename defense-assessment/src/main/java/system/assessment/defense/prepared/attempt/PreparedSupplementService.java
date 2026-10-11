package system.assessment.defense.prepared.attempt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.publication.PreparedPublication;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static system.assessment.defense.prepared.attempt.PreparedSupplement.*;

/** Current manager + current assigned student + original frozen end proof, with no cloud/model IO. */
@Service
public class PreparedSupplementService {
    private final ResourceAccessControlService access;private final PreparedAttemptStartRepository attempts;private final PreparedAnswerUploadService uploads;
    private final PreparedAnswerEndRepository endings;private final PreparedSupplementStore grants;private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;
    @Autowired public PreparedSupplementService(ResourceAccessControlService access,PreparedAttemptStartRepository attempts,PreparedAnswerUploadService uploads,
            PreparedAnswerEndRepository endings,JdbcTemplate jdbc,PlatformTransactionManager manager){this(access,attempts,uploads,endings,jdbc,manager,Clock.systemUTC());}
    public PreparedSupplementService(ResourceAccessControlService access,PreparedAttemptStartRepository attempts,PreparedAnswerUploadService uploads,
            PreparedAnswerEndRepository endings,JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock){this.access=access;this.attempts=attempts;this.uploads=uploads;this.endings=endings;this.jdbc=jdbc;this.grants=endings.supplements();this.tx=new TransactionTemplate(manager);this.clock=clock;}
    public View status(int actor,int student,int assessment,String attempt,String answer){return tx.execute(s->{
        var bound=authorize(actor,student,assessment,attempt,answer);var latest=grants.latest(student,assessment,attempt,answer).map(row->grants.verify(row,bound));var now=now();
        String issue=eligibility(student,assessment,bound,now);String phase="SAVED".equals(bound.answer().state())?"COMPLETE":bound.attempt().supplementalUploadDeadline()==null?"NOT_ELIGIBLE":now.isBefore(Instant.parse(bound.attempt().supplementalUploadDeadline()))?"AUTOMATIC_WINDOW":latest.isPresent()?(now.isBefore(Instant.parse(latest.orElseThrow().uploadDeadline()))?"APPROVED":"EXPIRED"):"AWAITING_APPROVAL";
        return new View(actor,student,assessment,attempt,answer,latest.map(Receipt::approvalRevision).orElse(0L),bound.attempt().revision(),bound.answer().state(),bound.end().map(PreparedAnswerEndRepository.Head::hash).orElse(null),bound.answer().deadline().toString(),bound.attempt().supplementalUploadDeadline(),now.toString(),phase,issue==null,issue,latest.orElse(null));
    });}
    public Receipt approve(int actor,int student,int assessment,String attempt,String answer,Command command){
        if(command==null)throw new IllegalArgumentException("原补传审批命令缺失");
        return tx.execute(s->{
            var bound=authorize(actor,student,assessment,attempt,answer);var old=grants.command(actor,assessment,command.idempotencyKey());
            String request=PreparedSupplementStore.requestHash(actor,student,assessment,attempt,answer,command);
            if(old.isPresent()){
                var original=old.orElseThrow();var receipt=original.receipt();
                if(receipt.studentId()!=student||!receipt.attemptId().equals(attempt)||!receipt.answerId().equals(answer))throw conflict("相同审批标识不能更换原范围或内容");
                var recovered=grants.verify(original,bound);
                if(!request.equals(original.requestSha()))throw conflict("相同审批标识不能更换原范围或内容");return recovered;
            }
            var latest=grants.latest(student,assessment,attempt,answer).map(row->grants.verify(row,bound));long revision=latest.map(Receipt::approvalRevision).orElse(0L);
            if(command.expectedRevision()!=revision||command.expectedAttemptRevision()!=bound.attempt().revision())throw conflict("审批或尝试版本已变化，请先查询原审批状态");
            Instant now=now();String issue=eligibility(student,assessment,bound,now);if(issue!=null)throw conflict(issue);
            if(!command.manifestSha256().equals(bound.end().orElseThrow().hash()))throw conflict("审批不能更换原录音清单");
            Instant deadline=deadline(command.uploadDeadline());if(!deadline.isAfter(now)||latest.isPresent()&&!deadline.isAfter(Instant.parse(latest.orElseThrow().uploadDeadline())))throw conflict("补传截止必须晚于当前时间及已批准的截止");
            var original=bound.attempt();var receipt=new Receipt(UUID.randomUUID().toString(),actor,student,assessment,attempt,answer,original.paperVersionId(),revision+1,original.revision()+1,
                command.manifestSha256(),bound.answer().deadline().toString(),original.answeringEndedAt(),original.supplementalUploadDeadline(),now.toString(),command.uploadDeadline(),command.reason(),command.idempotencyKey());
            if(jdbc.update("UPDATE prepared_attempt SET state='REQUIRES_REVIEW',requires_supplement_review=1,revision=revision+1 WHERE assessment_id=? AND student_id=? AND id=? AND revision=? AND state IN ('UPLOAD_PENDING','REQUIRES_REVIEW')",assessment,student,attempt,original.revision())!=1)throw conflict("原尝试版本已变化");
            grants.create(receipt,bound.end().orElseThrow().revision(),command);
            jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,original_student_id,original_attempt_id,resource_id) VALUES (?,?,'SUPPLEMENT_APPROVED',?,?,?,?)",assessment,actor,receipt.approvalRevision(),student,attempt,answer);return receipt;
        });
    }
    private PreparedAnswerUploadService.Bound authorize(int actor,int student,int assessment,String attempt,String answer){
        if(actor<1||student<1||assessment<1)throw new IllegalArgumentException("审批身份或考核无效");PreparedJobInput.uuid(attempt);PreparedJobInput.uuid(answer);
        access.requireAssessmentManager(actor,assessment);attempts.lockAssessment(assessment);
        if(!attempts.managerReadAllowed(actor,student,assessment,attempt))throw new BusinessException(403,"无权限或原尝试不存在");
        // The separate strict student/write guard rejects historical anchors and revoked student
        // ownership. The historical read-only helper is deliberately not used as a write guard.
        return uploads.authorize(student,assessment,attempt,answer);
    }
    private String eligibility(int student,int assessment,PreparedAnswerUploadService.Bound bound,Instant now){
        var attempt=bound.attempt();var end=bound.end();
        if(attempt.answeringEndedAt()==null||attempt.supplementalUploadDeadline()==null)return "全部题目尚未结束，不能审批超期补传";
        Instant ended=Instant.parse(attempt.answeringEndedAt()),automatic=Instant.parse(attempt.supplementalUploadDeadline());
        if(!automatic.equals(ended.plusSeconds(86400)))throw new IllegalStateException("原自动补传期限无法核验");
        if(now.isBefore(automatic))return "原自动补传窗口尚未结束，无需审批延期";
        if(!"UPLOAD_PENDING".equals(attempt.state())&&!("REQUIRES_REVIEW".equals(attempt.state())&&endings.supplementReviewOnly(student,assessment,attempt.id())))return "原尝试有其他核验问题，不能用补传审批解除";
        if(end.isEmpty()||!end.orElseThrow().reasons().isEmpty()||!"UPLOAD_PENDING".equals(bound.answer().state()))return "本题未有完整原清单、已保存或有采集争议，需另行人工核验";
        var paper=PreparedPublication.decode(attempts.boundPaperForUpdate(assessment,attempt.paperVersionId()).content());
        var all=new PreparedQuestionGrantRepository(jdbc).answers(student,assessment,attempt.id(),true);var heads=endings.heads(student,assessment,attempt.id());
        if(all.size()!=paper.questions().size()||heads.size()!=all.size())return "全部原回答的结束依据尚未齐备，需人工核验";
        for(var original:all){var proof=uploads.authorize(student,assessment,attempt.id(),original.id()).end().orElseThrow();if(!proof.reasons().isEmpty())return "原尝试有采集争议，补传审批不能伪报完整";}
        if(!heads.stream().map(PreparedAnswerEndRepository.Head::ended).max(Instant::compareTo).orElseThrow().equals(ended))throw new IllegalStateException("原整场作答结束依据无法核验");
        return null;
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private static BusinessException conflict(String message){return new BusinessException(409,message);}
}
