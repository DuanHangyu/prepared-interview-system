package system.assessment.defense.prepared.attempt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.publication.PreparedPublication;
import java.time.Instant;
import java.util.List;

/** Manager-only read projection of the immutable end declaration. Client metadata is not
 * verified audio, cloud receipt, timing authenticity or authorization to continue/re-answer.
 * Reuses original permissions, snapshots and proof checks; performs no external IO or writes. */
@Service
public class PreparedAnswerEvidenceService {
    public record QuestionEvidence(String questionId,int ordinal,String body,int answerSeconds,int maxScore,String rubric) {}
    public record Declaration(String manifestSha256,String state,long revision,String effectiveEndedAt,String acceptedAt,
                              List<String> reviewReasons,long byteSize,int chunkCount,int segmentCount) {
        public Declaration { reviewReasons=List.copyOf(reviewReasons); }
    }
    public record SegmentEvidence(String id,int ordinal,String mimeType,String state,String interruption,
                                  long declaredStartedAt,long declaredEndedAt,int chunkCount,long byteSize) {}
    public record Evidence(int actorId,int assessmentId,int studentId,String attemptId,String answerId,boolean current,
                           String attemptState,long attemptRevision,String answerState,long answerRevision,
                           QuestionEvidence question,String startedAt,String answerDeadline,Declaration declaration,
                           List<SegmentEvidence> segments,Integer nextSegmentOrdinal) {
        public Evidence { segments=List.copyOf(segments); }
    }
    private record Scope(int student,boolean current) {}
    private final ResourceAccessControlService access;
    private final PreparedAttemptStartRepository attempts;
    private final PreparedQuestionGrantRepository answers;
    private final PreparedAnswerEndRepository endings;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PreparedAnswerEvidenceService(ResourceAccessControlService access,PreparedAttemptStartRepository attempts,
            PreparedQuestionGrantRepository answers,PreparedAnswerEndRepository endings,JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.access=access;this.attempts=attempts;this.answers=answers;this.endings=endings;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);
    }

    public Evidence read(int actor,int assessment,String attemptId,String answerId,int after,int size) {
        if(actor<1||assessment<1||after<0||after>1000||size<1||size>50)throw new IllegalArgumentException("原回答证据范围或页大小无效");
        PreparedJobInput.uuid(attemptId);PreparedJobInput.uuid(answerId);
        return tx.execute(status->{
            access.requireAssessmentManager(actor,assessment);attempts.lockAssessment(assessment);
            var scopes=jdbc.query("""
                SELECT a.student_id,(h.active_attempt_id=a.id) is_current FROM prepared_attempt a
                JOIN prepared_attempt_anchor h ON h.assessment_id=a.assessment_id AND h.student_id=a.student_id AND h.record_id=a.record_id
                JOIN student_assessment_record r ON r.id=a.record_id AND r.assessment_id=a.assessment_id AND r.student_id=a.student_id
                WHERE a.assessment_id=? AND a.id=? FOR UPDATE
                """,(rs,n)->new Scope(rs.getInt("student_id"),rs.getBoolean("is_current")),assessment,attemptId);
            if(scopes.size()!=1)throw forbidden();var scope=scopes.getFirst();int student=scope.student();
            // Fresh SQL authorization also protects historical reads after invitation/role changes.
            if(!attempts.managerReadAllowed(actor,student,assessment,attemptId))throw forbidden();
            var attempt=attempts.get(student,assessment,attemptId);
            var paper=attempts.boundPaperForUpdate(assessment,attempt.paperVersionId());
            PreparedStudentPaperVerifier.verify(assessment,paper.content(),paper.sha256());
            var snapshot=PreparedPublication.decode(paper.content());
            var answer=answers.answers(student,assessment,attemptId,true).stream().filter(a->answerId.equals(a.id())).findFirst().orElseThrow(PreparedAnswerEvidenceService::forbidden);
            if(answer.ordinal()<1||answer.ordinal()>snapshot.questions().size())throw damaged();
            var question=snapshot.questions().get(answer.ordinal()-1);answers.verifyQuestion(assessment,attempt.paperVersionId(),question,true);
            if(!answer.version().equals(attempt.paperVersionId())||!answer.question().equals(question.questionId())||answer.seconds()!=question.answerSeconds()
                    ||answer.started().isBefore(Instant.parse(attempt.startedAt()))||!answer.deadline().equals(answer.started().plusSeconds(answer.seconds())))throw damaged();
            long revision=jdbc.queryForObject("SELECT revision FROM prepared_answer WHERE assessment_id=? AND student_id=? AND attempt_id=? AND id=?",Long.class,assessment,student,attemptId,answerId);
            var original=endings.head(student,assessment,attemptId,answerId);
            var frozen=new QuestionEvidence(question.questionId(),question.ordinal(),question.body(),question.answerSeconds(),question.maxScore(),question.rubric());
            if(original.isEmpty()){
                if(!"RECORDING".equals(answer.state()))throw damaged();
                if(after!=0)throw cursorConflict();
                return new Evidence(actor,assessment,student,attemptId,answerId,scope.current(),attempt.state(),attempt.revision(),answer.state(),revision,
                    frozen,answer.started().toString(),answer.deadline().toString(),null,List.of(),null);
            }
            var head=original.get();PreparedAnswerEndService.verifyHead(head,answer);
            if(head.revision()<1||head.revision()>revision||"RECORDING".equals(answer.state())
                    ||(!head.reasons().isEmpty()&&!"REQUIRES_REVIEW".equals(answer.state())))throw damaged();
            var segments=PreparedAnswerEnd.decode(head.manifest()).segments();
            if(after>segments.size())throw cursorConflict();
            int end=Math.min(segments.size(),after+size);
            var shown=segments.subList(after,end).stream().map(s->new SegmentEvidence(s.id(),s.ordinal(),s.mimeType(),s.state(),s.interruption(),
                s.startedAt(),s.endedAt(),s.chunkCount(),s.byteSize())).toList();
            var declaration=new Declaration(head.hash(),head.state(),head.revision(),head.ended().toString(),head.accepted().toString(),head.reasons(),head.bytes(),
                segments.stream().mapToInt(PreparedAnswerEnd.Segment::chunkCount).sum(),segments.size());
            return new Evidence(actor,assessment,student,attemptId,answerId,scope.current(),attempt.state(),attempt.revision(),answer.state(),revision,
                frozen,answer.started().toString(),answer.deadline().toString(),declaration,shown,end<segments.size()?end:null);
        });
    }
    private static BusinessException forbidden(){return new BusinessException(403,"无权限或原回答证据不存在");}
    private static BusinessException cursorConflict(){return new BusinessException(409,"分段游标不属于原结束声明，请重新读取");}
    private static IllegalStateException damaged(){return new IllegalStateException("原回答证据与授权绑定无法核验");}
}
