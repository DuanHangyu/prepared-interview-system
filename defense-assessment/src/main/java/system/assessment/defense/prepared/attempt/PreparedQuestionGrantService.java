package system.assessment.defense.prepared.attempt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.publication.PreparedPublication;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static system.assessment.defense.prepared.attempt.PreparedQuestionGrant.*;
import static system.assessment.defense.prepared.attempt.PreparedQuestionGrantRepository.*;

/** Internal authorization foundation. No public recording entry until terminal/upload/writer recovery are integrated. */
@Service
public class PreparedQuestionGrantService {
    private final ResourceAccessControlService access;
    private final PreparedStudentEligibilityRepository eligibility;
    private final PreparedAttemptStartRepository attempts;
    private final PreparedQuestionGrantRepository repository;
    private final Clock clock;
    @Autowired public PreparedQuestionGrantService(ResourceAccessControlService access,PreparedStudentEligibilityRepository eligibility,
            PreparedAttemptStartRepository attempts,PreparedQuestionGrantRepository repository){this(access,eligibility,attempts,repository,Clock.systemUTC());}
    public PreparedQuestionGrantService(ResourceAccessControlService access,PreparedStudentEligibilityRepository eligibility,
            PreparedAttemptStartRepository attempts,PreparedQuestionGrantRepository repository,Clock clock){
        this.access=access;this.eligibility=eligibility;this.attempts=attempts;this.repository=repository;this.clock=clock;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Grant start(int student,int assessment,String attemptId,Command command) {
        validateScope(student,assessment,attemptId);if(command==null)throw new IllegalArgumentException("开题命令无效");command.validate();
        access.requireAssignedStudent(student,assessment);
        // A question can only belong to a committed, already-frozen attempt. Share its immutable
        // parent, but keep this student's identity/assignment/anchor exclusive across replay keys.
        // READ COMMITTED avoids absent-receipt gap cycles between independent students.
        if(!attempts.lockForStudentStart(student,assessment))throw new IllegalStateException("原开考冻结状态待核验");
        eligibility.contextForShare(student,assessment);
        var attempt=active(student,assessment,attemptId,true);var snapshot=paper(assessment,attempt.paperVersionId(),true);
        var question=question(snapshot,command.ordinal());repository.verifyQuestionForShare(assessment,attempt.paperVersionId(),question);
        String hash=PreparedJobInput.digest("prepared-question-start-v1:"+attemptId+":"+command.ordinal()+":"+command.captureClientId());
        var receipt=repository.receipt(student,assessment,attemptId,command.idempotencyKey());
        var answers=repository.answers(student,assessment,attemptId,true);Answer answer;
        if(receipt.isPresent()) {
            if(!hash.equals(receipt.get().hash()))throw new BusinessException(409,"原开题命令不一致");
            answer=answers.stream().filter(a->a.id().equals(receipt.get().answer())).findFirst().orElseThrow(()->new IllegalStateException("原开题回执绑定无效"));
            if(answer.ordinal()!=command.ordinal()||!answer.client().equals(command.captureClientId()))throw new IllegalStateException("原开题回执绑定无效");
        } else {
            var existing=answers.stream().filter(a->a.ordinal()==command.ordinal()).findFirst();
            if(existing.isPresent()) {
                answer=existing.get();
                if(!answer.client().equals(command.captureClientId()))throw new BusinessException(409,"本题已由原客户端授权，不能自动抢占，请恢复原回答或联系教师");
            } else {
                if(!"IN_PROGRESS".equals(attempt.state()))throw new BusinessException(409,"原尝试当前不允许新题作答");
                if(command.ordinal()!=answers.size()+1)throw new BusinessException(409,"请按固定顺序完成当前题");
                int ordinal=1;
                for(var prior:answers) {
                    if(prior.ordinal()!=ordinal++||!Set.of("UPLOAD_PENDING","SAVED").contains(prior.state()))throw new BusinessException(409,"原回答尚未结束或需要核验，请保留已录内容");
                    verifyStored(prior,attempt,question(snapshot,prior.ordinal()));
                }
                Instant started=now();
                if(started.isBefore(Instant.parse(attempt.startedAt()))||answers.stream().anyMatch(prior->started.isBefore(prior.started())))
                    throw new BusinessException(503,"服务器时间暂不可核验，请保留原回答后重试");
                answer=repository.create(student,assessment,attemptId,attempt.paperVersionId(),question,command.captureClientId(),started);
            }
            repository.recordReceipt(student,assessment,attemptId,command.idempotencyKey(),hash,answer.id());
        }
        return project(attempt,answer,question,command.captureClientId());
    }
    @Transactional(readOnly=true)
    public Optional<Grant> current(int student,int assessment,String attemptId,String client) {
        validateScope(student,assessment,attemptId);PreparedJobInput.uuid(client);
        access.requireAssignedStudent(student,assessment);eligibility.context(student,assessment);
        var attempt=active(student,assessment,attemptId,false);var snapshot=paper(assessment,attempt.paperVersionId(),false);
        var answers=repository.answers(student,assessment,attemptId,false);
        if(answers.isEmpty())return Optional.empty();
        var answer=answers.getLast();var question=question(snapshot,answer.ordinal());repository.verifyQuestion(assessment,attempt.paperVersionId(),question,false);
        return Optional.of(project(attempt,answer,question,client));
    }
    /** Internal frozen descriptor, never serialize this record through a student controller. */
    public record AudioGrant(String paperVersionId,String questionId,String body,PreparedPublication.Audio audio) {}
    @Transactional(readOnly=true)
    public AudioGrant audioGrant(int student,int assessment,String attemptId,String answerId,String client) {
        validateScope(student,assessment,attemptId);PreparedJobInput.uuid(answerId);PreparedJobInput.uuid(client);
        access.requireAssignedStudent(student,assessment);eligibility.context(student,assessment);
        var attempt=active(student,assessment,attemptId,false);var snapshot=paper(assessment,attempt.paperVersionId(),false);
        var answer=repository.answers(student,assessment,attemptId,false).stream().filter(a->answerId.equals(a.id()))
                .findFirst().orElseThrow(()->new BusinessException(403,"无权限或回答不存在"));
        if(!client.equals(answer.client()))throw new BusinessException(409,"请恢复原授权客户端，播报不开放新作答或抢占");
        var question=question(snapshot,answer.ordinal());verifyStored(answer,attempt,question);
        repository.verifyQuestion(assessment,attempt.paperVersionId(),question,false);
        return new AudioGrant(attempt.paperVersionId(),question.questionId(),question.body(),question.audio());
    }
    private PreparedAttemptStart.Summary active(int student,int assessment,String id,boolean lock) {
        var current=lock?attempts.currentForUpdate(student,assessment):attempts.current(student,assessment);
        return current.filter(a->id.equals(a.id())).orElseThrow(()->new BusinessException(403,"无权限或尝试不存在"));
    }
    private PreparedPublication.Snapshot paper(int assessment,String version,boolean lock) {
        var paper=lock?attempts.boundPaperForShare(assessment,version):attempts.boundPaper(assessment,version);
        PreparedStudentPaperVerifier.verify(assessment,paper.content(),paper.sha256());return PreparedPublication.decode(paper.content());
    }
    private static PreparedPublication.Question question(PreparedPublication.Snapshot snapshot,int ordinal) {
        if(ordinal<1||ordinal>snapshot.questions().size())throw new BusinessException(409,"题序不属于原试卷");
        return snapshot.questions().get(ordinal-1);
    }
    private Grant project(PreparedAttemptStart.Summary attempt,Answer answer,PreparedPublication.Question question,String client) {
        verifyStored(answer,attempt,question);Instant now=now();
        boolean allowed="IN_PROGRESS".equals(attempt.state())&&"RECORDING".equals(answer.state())&&client.equals(answer.client())&&!now.isBefore(answer.started())&&now.isBefore(answer.deadline());
        return new Grant(attempt.assessmentId(),attempt.id(),attempt.paperVersionId(),answer.id(),answer.question(),answer.ordinal(),question.body(),answer.seconds(),answer.state(),
                answer.started().toString(),answer.deadline().toString(),now.toString(),allowed);
    }
    private static void verifyStored(Answer answer,PreparedAttemptStart.Summary attempt,PreparedPublication.Question question) {
        if(!attempt.paperVersionId().equals(answer.version())||!question.questionId().equals(answer.question())||question.ordinal()!=answer.ordinal()
                ||question.answerSeconds()!=answer.seconds()||answer.started().isBefore(Instant.parse(attempt.startedAt()))
                ||!answer.deadline().equals(answer.started().plusSeconds(question.answerSeconds())))
            throw new IllegalStateException("原回答授权绑定验证失败");
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private static void validateScope(int student,int assessment,String attempt) {
        if(student<1||assessment<1)throw new IllegalArgumentException("身份或考核标识无效");PreparedJobInput.uuid(attempt);
    }
}
