package system.assessment.defense.prepared.attempt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.publication.PreparedPublication;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static system.assessment.defense.prepared.attempt.PreparedAnswerEnd.*;
import static system.assessment.defense.prepared.attempt.PreparedAnswerEndRepository.*;

/** Real declaration transaction, not an upload or final submission. Public student pipeline remains unexposed until integrated. */
@Service
public class PreparedAnswerEndService {
    private final ResourceAccessControlService access;private final PreparedStudentEligibilityRepository eligibility;
    private final PreparedAttemptStartRepository attempts;private final PreparedQuestionGrantRepository answers;private final PreparedAnswerEndRepository repository;private final PreparedAnswerUploadRepository uploads;private final Clock clock;
    @Autowired public PreparedAnswerEndService(ResourceAccessControlService access,PreparedStudentEligibilityRepository eligibility,PreparedAttemptStartRepository attempts,
            PreparedQuestionGrantRepository answers,PreparedAnswerEndRepository repository,PreparedAnswerUploadRepository uploads){this(access,eligibility,attempts,answers,repository,uploads,Clock.systemUTC());}
    public PreparedAnswerEndService(ResourceAccessControlService access,PreparedStudentEligibilityRepository eligibility,PreparedAttemptStartRepository attempts,
            PreparedQuestionGrantRepository answers,PreparedAnswerEndRepository repository,PreparedAnswerUploadRepository uploads,Clock clock){this.access=access;this.eligibility=eligibility;this.attempts=attempts;this.answers=answers;this.repository=repository;this.uploads=uploads;this.clock=clock;}
    @Transactional
    public Summary end(int student,int assessment,String attemptId,String answerId,Command command) {
        if(student<1||assessment<1||command==null)throw new IllegalArgumentException("结束命令无效");PreparedJobInput.uuid(attemptId);PreparedJobInput.uuid(answerId);command.validate();
        // Strict shape/resource bounds before parent locking; actual per-question duration is inspected after ownership is known.
        inspect(command.manifest(),1800);
        access.requireAssignedStudent(student,assessment);attempts.lockAssessment(assessment);eligibility.contextForUpdate(student,assessment);
        var attempt=attempts.currentForUpdate(student,assessment).filter(a->attemptId.equals(a.id())).orElseThrow(()->new BusinessException(403,"无权限或尝试不存在"));
        var paper=attempts.boundPaperForUpdate(assessment,attempt.paperVersionId());PreparedStudentPaperVerifier.verify(assessment,paper.content(),paper.sha256());var snapshot=PreparedPublication.decode(paper.content());
        var all=answers.answers(student,assessment,attemptId,true);var answer=all.stream().filter(a->answerId.equals(a.id())).findFirst().orElseThrow(()->new BusinessException(403,"无权限或回答不存在"));
        if(!command.captureClientId().equals(answer.client()))throw new BusinessException(409,"请从原授权客户端恢复原回答，不能自动抢占");
        if(answer.ordinal()<1||answer.ordinal()>snapshot.questions().size())throw new IllegalStateException("原回答题序绑定无效");var question=snapshot.questions().get(answer.ordinal()-1);
        answers.verifyQuestion(assessment,attempt.paperVersionId(),question,true);
        if(!answer.version().equals(attempt.paperVersionId())||!question.questionId().equals(answer.question())||answer.seconds()!=question.answerSeconds()
                ||answer.started().isBefore(Instant.parse(attempt.startedAt()))||!answer.deadline().equals(answer.started().plusSeconds(answer.seconds())))throw new IllegalStateException("原回答授权无法核验");
        var proof=inspect(command.manifest(),answer.seconds());
        PreparedAnswerUploadService.verifyReservedManifest(command.manifest(),uploads.rows(student,assessment,attemptId,answerId));
        String hash=PreparedJobInput.digest("prepared-answer-end-v1:"+answerId+":"+command.captureClientId()+":"+proof.sha256());
        var receipt=repository.receipt(student,assessment,attemptId,command.idempotencyKey());
        if(receipt.isPresent()&&(!receipt.get().hash().equals(hash)||!receipt.get().answer().equals(answerId)))throw new BusinessException(409,"原结束命令不一致");
        var original=repository.head(student,assessment,attemptId,answerId);Head head;
        if(original.isPresent()) {
            head=original.get();verifyHead(head,answer);
            if(!head.hash().equals(proof.sha256()))throw new BusinessException(409,"原回答清单已固定，不能删除或重答");
        } else {
            if(receipt.isPresent())throw new IllegalStateException("原结束回执绑定无效");
            if(!"IN_PROGRESS".equals(attempt.state())||!"RECORDING".equals(answer.state()))throw new BusinessException(409,"原回答当前不允许新的结束命令");
            Instant accepted=now();if(accepted.isBefore(answer.started()))throw new BusinessException(503,"服务器时间暂不可核验，请保留原回答");
            // Do not trust local wall timestamps or move a delayed declaration beyond its original per-question grant.
            Instant ended=accepted.isBefore(answer.deadline())?accepted:answer.deadline();
            head=repository.create(student,assessment,attemptId,answerId,proof,ended,accepted);
        }
        if(receipt.isEmpty())repository.receipt(student,assessment,attemptId,answerId,command.idempotencyKey(),hash);
        var heads=repository.heads(student,assessment,attemptId);
        if(heads.size()==snapshot.questions().size()&&all.size()==snapshot.questions().size()) {
            Instant ended=heads.stream().map(Head::ended).max(Instant::compareTo).orElseThrow();boolean review=false;
            for(var closed:heads){var bound=all.stream().filter(a->a.id().equals(closed.answer())).findFirst().orElseThrow(()->new IllegalStateException("原清单回答绑定无效"));verifyHead(closed,bound);review|=!closed.reasons().isEmpty();}
            repository.finishAnswering(student,assessment,attemptId,ended,review);
        }
        repository.expirePending(student,assessment,attemptId,now());var current=attempts.get(student,assessment,attemptId);
        return new Summary(answerId,head.hash(),head.state(),head.revision(),head.ended().toString(),head.accepted().toString(),head.reasons(),false,current.state(),current.revision(),current.answeringEndedAt(),current.supplementalUploadDeadline());
    }
    static void verifyHead(Head head,PreparedQuestionGrantRepository.Answer answer) {
        try{
            var proof=inspect(decode(head.manifest()),answer.seconds());
            Instant expected=head.accepted().isBefore(answer.deadline())?head.accepted():answer.deadline();
            if(!proof.sha256().equals(head.hash())||proof.byteSize()!=head.bytes()||!proof.reviewReasons().equals(head.reasons())
                    ||!head.state().equals(head.reasons().isEmpty()?"UPLOAD_PENDING":"REQUIRES_REVIEW")||!head.answer().equals(answer.id())
                    ||head.accepted().isBefore(answer.started())||!head.ended().equals(expected))throw new IllegalStateException();
        }catch(RuntimeException failure){throw new IllegalStateException("原录音结束记录完整性验证失败");}
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
}
