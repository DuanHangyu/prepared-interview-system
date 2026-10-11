package system.assessment.defense.prepared.processing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.util.*;

/** Teacher-only bounded projections. Never expose original task payloads/storage keys or invent
 * a zero grade from a queued/failed result. No model/storage IO occurs in these transactions. */
@Service
public class PreparedReviewService {
    public record Row(int recordId,int studentId,String studentName,String attemptId,String state){}
    public record Roster(int actorId,int assessmentId,String theme,List<Row> rows,Integer nextRecordId){public Roster{rows=List.copyOf(rows);}}
    public record Attempt(String id,int attemptNo,String state,String startedAt,String submittedAt,boolean current){}
    public record Attempts(int actorId,int assessmentId,int studentId,int recordId,String activeAttemptId,List<Attempt> attempts,Integer nextAttemptNo){public Attempts{attempts=List.copyOf(attempts);}}
    public record Task(String state,String errorCode){}
    public record AnswerRow(String answerId,int ordinal,String state,Task transcription,Task scoring){}
    public record Interview(int actorId,int assessmentId,int studentId,String studentName,String attemptId,int attemptNo,String state,
                            boolean submitted,String submittedAt,List<AnswerRow> answers,boolean current){public Interview{answers=List.copyOf(answers);}}
    public record History(String id,String kind,int version,String completedAt,String provider,String model,PreparedProcessing.Score score){}
    public record Answer(int actorId,String answerId,long revision,boolean confirmed,system.assessment.defense.prepared.publication.PreparedPublication.Question question,
                         String transcriptId,PreparedProcessing.Transcript transcript,String scoreId,PreparedProcessing.Score score,List<History> history,String nextResultId,PreparedProcessing.ManualScore manualScore){public Answer{history=List.copyOf(history);}}
    private final PreparedProcessingService processing;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private record InterviewRow(int studentId,String studentName,int attemptNo,String state,String submittedAt,boolean current){}
    private record AttemptScope(int recordId,String activeAttemptId){}
    public PreparedReviewService(PreparedProcessingService processing,JdbcTemplate jdbc,PlatformTransactionManager manager){this.processing=processing;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);}
    public Roster roster(int actor,int assessment,int after,int limit){
        if(after<0||limit<1||limit>50)throw new IllegalArgumentException("评阅列表游标或页大小无效");
        return tx.execute(s->{String theme=processing.reviewTheme(actor,assessment);
            var all=jdbc.query("""
                SELECT r.id,r.student_id,u.name,a.id attempt_id,a.state FROM student_assessment_record r
                JOIN `user` u ON u.id=r.student_id LEFT JOIN prepared_attempt_anchor h ON h.assessment_id=r.assessment_id AND h.student_id=r.student_id AND h.record_id=r.id
                LEFT JOIN prepared_attempt a ON a.id=h.active_attempt_id AND a.assessment_id=r.assessment_id AND a.student_id=r.student_id
                WHERE r.assessment_id=? AND r.id>? ORDER BY r.id LIMIT ?
                """,(rs,n)->new Row(rs.getInt("id"),rs.getInt("student_id"),rs.getString("name"),rs.getString("attempt_id"),rs.getString("state")==null?"NO_PREPARED_ATTEMPT":rs.getString("state")),assessment,after,limit+1);
            var shown=all.stream().limit(limit).toList();return new Roster(actor,assessment,theme,shown,all.size()>limit?shown.getLast().recordId():null);
        });
    }
    public Interview interview(int actor,int assessment,String attempt){
        PreparedJobInput.uuid(attempt);return tx.execute(s->{processing.reviewTheme(actor,assessment);
            var rows=jdbc.query("""
                SELECT a.*,u.name,s.submitted_at,(h.active_attempt_id=a.id) is_current FROM prepared_attempt a JOIN prepared_attempt_anchor h
                ON h.assessment_id=a.assessment_id AND h.student_id=a.student_id AND h.record_id=a.record_id
                JOIN `user` u ON u.id=a.student_id LEFT JOIN prepared_submission s ON s.assessment_id=a.assessment_id AND s.student_id=a.student_id AND s.attempt_id=a.id
                WHERE a.assessment_id=? AND a.id=?
                """,(rs,n)->{var submitted=rs.getTimestamp("submitted_at");return new InterviewRow(rs.getInt("student_id"),rs.getString("name"),rs.getInt("attempt_no"),rs.getString("state"),submitted==null?null:submitted.toInstant().toString(),rs.getBoolean("is_current"));},assessment,attempt);
            if(rows.size()!=1)throw new BusinessException(403,"无权限或原有效尝试不存在");var row=rows.getFirst();int student=row.studentId();
            var answers=jdbc.query("""
                SELECT a.id,a.ordinal,a.state,b.transcribe_job_id,b.score_job_id FROM prepared_answer a
                LEFT JOIN prepared_submission_answer b ON b.assessment_id=a.assessment_id AND b.student_id=a.student_id AND b.attempt_id=a.attempt_id AND b.answer_id=a.id
                WHERE a.assessment_id=? AND a.student_id=? AND a.attempt_id=? ORDER BY a.ordinal
                """,(rs,n)->new AnswerRow(rs.getString("id"),rs.getInt("ordinal"),rs.getString("state"),task(rs.getString("transcribe_job_id")),currentScore(rs.getString("id"),rs.getString("score_job_id"))),assessment,student,attempt);
            boolean submitted=row.submittedAt()!=null;
            if(submitted){if(answers.isEmpty()||!"SUBMITTED".equals(row.state()))throw new IllegalStateException("原提交状态与回答绑定无法核验");processing.reviewPage(actor,assessment,attempt,answers.getFirst().answerId(),null,1);}
            else if("SUBMITTED".equals(row.state()))throw new IllegalStateException("原提交状态缺少唯一回执");
            return new Interview(actor,assessment,student,row.studentName(),attempt,row.attemptNo(),row.state(),submitted,row.submittedAt(),answers,row.current());
        });
    }
    public Attempts attempts(int actor,int assessment,int student,int after,int limit){
        if(actor<1||assessment<1||student<1||after<0||limit<1||limit>20)throw new IllegalArgumentException("尝试历史范围、游标或页大小无效");
        return tx.execute(s->{processing.reviewTheme(actor,assessment);
            var scopes=jdbc.query("""
                SELECT r.id,h.active_attempt_id FROM student_assessment_record r LEFT JOIN prepared_attempt_anchor h
                ON h.assessment_id=r.assessment_id AND h.student_id=r.student_id AND h.record_id=r.id
                WHERE r.assessment_id=? AND r.student_id=?
                """,(rs,n)->new AttemptScope(rs.getInt("id"),rs.getString("active_attempt_id")),assessment,student);
            if(scopes.size()!=1)throw new BusinessException(403,"无权限或原学生考核记录不存在");var scope=scopes.getFirst();
            if(scope.activeAttemptId()==null){
                var existing=jdbc.queryForList("SELECT id FROM prepared_attempt WHERE assessment_id=? AND student_id=? AND record_id=? LIMIT 1",String.class,assessment,student,scope.recordId());
                if(!existing.isEmpty())throw new IllegalStateException("原尝试历史缺少有效归属锚点");
            }else if(jdbc.queryForList("SELECT id FROM prepared_attempt WHERE id=? AND assessment_id=? AND student_id=? AND record_id=?",String.class,scope.activeAttemptId(),assessment,student,scope.recordId()).size()!=1)
                throw new IllegalStateException("原当前尝试与考核记录无法核验");
            if(after>0&&jdbc.queryForList("SELECT id FROM prepared_attempt WHERE assessment_id=? AND student_id=? AND record_id=? AND attempt_no=?",String.class,assessment,student,scope.recordId(),after).size()!=1)
                throw new BusinessException(409,"尝试历史游标不属于原学生考核记录");
            var all=jdbc.query("""
                SELECT a.id,a.attempt_no,a.state,a.started_at,p.submitted_at FROM prepared_attempt a
                LEFT JOIN prepared_submission p ON p.attempt_id=a.id AND p.assessment_id=a.assessment_id AND p.student_id=a.student_id
                WHERE a.assessment_id=? AND a.student_id=? AND a.record_id=? AND (?=0 OR a.attempt_no<?)
                ORDER BY a.attempt_no DESC LIMIT ?
                """,(rs,n)->{var submitted=rs.getTimestamp("submitted_at");String id=rs.getString("id");return new Attempt(id,rs.getInt("attempt_no"),rs.getString("state"),rs.getTimestamp("started_at").toInstant().toString(),submitted==null?null:submitted.toInstant().toString(),id.equals(scope.activeAttemptId()));},assessment,student,scope.recordId(),after,after,limit+1);
            var shown=all.stream().limit(limit).toList();return new Attempts(actor,assessment,student,scope.recordId(),scope.activeAttemptId(),shown,all.size()>limit?shown.getLast().attemptNo():null);
        });
    }
    public Answer answer(int actor,int assessment,String attempt,String answer,String after,int limit){
        var page=processing.reviewPage(actor,assessment,attempt,answer,after,limit);var t=page.transcript();var v=page.score();
        var transcript=t==null?null:PreparedProcessingCodec.decode(t.content(),PreparedProcessing.Transcript.class);var score=v==null?null:PreparedProcessingCodec.decode(v.content(),PreparedProcessing.Score.class);
        var history=page.history().stream().map(r->new History(r.id(),r.kind(),r.version(),r.completedAt(),r.provider(),r.model(),"SCORE".equals(r.kind())?PreparedProcessingCodec.decode(r.content(),PreparedProcessing.Score.class):null)).toList();
        return new Answer(actor,answer,page.revision(),page.confirmed(),page.question(),t==null?null:t.id(),transcript,v==null?null:v.id(),score,history,page.nextResultId(),page.manualScore());
    }
    public PreparedProcessingService.RegradeReceipt regrade(int actor,int assessment,String attempt,String answer,PreparedProcessing.RegradeCommand command){return processing.regrade(actor,assessment,attempt,answer,command);}
    public PreparedProcessingService.ConfirmReceipt confirm(int actor,int assessment,String attempt,String answer,PreparedProcessing.ConfirmCommand command){return processing.confirm(actor,assessment,attempt,answer,command);}
    public PreparedProcessingService.AdjustReceipt adjust(int actor,int assessment,String attempt,String answer,PreparedProcessing.AdjustCommand command){return processing.adjust(actor,assessment,attempt,answer,command);}
    public PreparedProcessingService.ManualPage manualHistory(int actor,int assessment,String attempt,String answer,String after,int limit){return processing.manualHistory(actor,assessment,attempt,answer,after,limit);}
    private Task currentScore(String answer,String initial){var requests=jdbc.queryForList("SELECT job_id FROM prepared_score_request WHERE answer_id=? ORDER BY requested_revision DESC LIMIT 1",String.class,answer);return task(requests.isEmpty()?initial:requests.getFirst());}
    private Task task(String id){if(id==null)return new Task("NOT_QUEUED",null);return jdbc.query("SELECT state,error_code FROM prepared_job WHERE id=?",(rs,n)->new Task(rs.getString("state"),rs.getString("error_code")),id).stream().findFirst().orElseThrow(()->new IllegalStateException("原处理任务缺失"));}
}
