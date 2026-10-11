package system.assessment.defense.prepared.processing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.PreparedSubmissionService;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.math.BigDecimal;
import java.util.*;

/** Authorized teacher current/historical result projection. One parent-locked read, bounded
 * to the frozen paper. No model/storage IO or legacy grades. Summary omits answer content;
 * full reports reuse verified effective heads; export audits authorization, not delivery. */
@Service
public class PreparedReviewSummaryService {
    public record Grade(String answerId,int ordinal,int maxScore,BigDecimal score,String scoringSource,int version,boolean confirmed) {}
    public record Summary(int actorId,int assessmentId,int studentId,String studentName,String theme,String attemptId,int attemptNo,
                          boolean current,boolean showResult,String state,String resultVersion,String completedAt,BigDecimal totalScore,
                          Integer totalMaxScore,Integer passScore,List<Grade> answers) {
        public Summary {answers=List.copyOf(answers);}
    }
    public record Report(int schemaVersion,Summary summary,List<PreparedStudentResultService.Answer> answers) {
        public Report {
            answers=List.copyOf(answers);
            if(schemaVersion!=1||summary==null||!"READY".equals(summary.state())||summary.answers().size()!=answers.size()||answers.isEmpty()||answers.size()>50)
                throw new IllegalStateException("原教师报告不完整");
            var ids=new HashSet<String>();
            for(int n=0;n<answers.size();n++) {
                var a=answers.get(n);var g=summary.answers().get(n);
                if(!ids.add(a.answerId())||a.studentId()!=summary.studentId()||a.assessmentId()!=summary.assessmentId()||!Objects.equals(a.attemptId(),summary.attemptId())
                    ||!Objects.equals(a.resultVersion(),summary.resultVersion())||!Objects.equals(a.answerId(),g.answerId())||a.ordinal()!=n+1||g.ordinal()!=a.ordinal()
                    ||a.maxScore()!=g.maxScore()||a.score()==null||g.score()==null||a.score().compareTo(g.score())!=0||!Objects.equals(a.scoringSource(),g.scoringSource())
                    ||a.version()!=g.version()||a.confirmed()!=g.confirmed())throw new IllegalStateException("原教师报告绑定无法核验");
            }
        }
    }
    private record Loaded(Summary summary,List<PreparedProcessingService.StudentGrade> grades) {}
    private record Student(int id,String name) {}
    private final PreparedSubmissionService submissions;
    private final PreparedProcessingService processing;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public PreparedReviewSummaryService(PreparedSubmissionService submissions,PreparedProcessingService processing,JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.submissions=submissions;this.processing=processing;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);
    }
    public Summary summary(int actor,int assessment,String attempt){
        validateScope(actor,assessment,attempt);
        return tx.execute(s->load(actor,assessment,attempt).summary());
    }
    public Report report(int actor,int assessment,String attempt,String resultVersion){
        validateScope(actor,assessment,attempt);PreparedProcessingCodec.sha(resultVersion);
        return tx.execute(s->reportWithin(actor,assessment,attempt,resultVersion,false));
    }
    public Report exportReport(int actor,int assessment,String attempt,String resultVersion){
        validateScope(actor,assessment,attempt);PreparedProcessingCodec.sha(resultVersion);
        return tx.execute(s->reportWithin(actor,assessment,attempt,resultVersion,true));
    }
    private Report reportWithin(int actor,int assessment,String attempt,String resultVersion,boolean exporting){
        var loaded=load(actor,assessment,attempt);var summary=loaded.summary();
        if(!"READY".equals(summary.state()))throw new BusinessException(409,"原结果尚未齐备，请刷新整场成绩并保留原回答");
        if(!resultVersion.equals(summary.resultVersion()))throw new BusinessException(409,"整场成绩版本已变化，请刷新后读取原尝试报告");
        var answers=loaded.grades().stream().map(value->new PreparedStudentResultService.Answer(summary.studentId(),assessment,attempt,resultVersion,
            value.answerId(),value.ordinal(),value.body(),value.maxScore(),value.score(),value.scoringSource(),value.version(),value.confirmed(),value.feedback(),
            value.sentences()==null?null:new PreparedStudentResultService.Transcript(value.sentences()))).toList();
        var report=new Report(1,summary,answers);
        if(exporting)jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,original_student_id,original_attempt_id,resource_sha256) VALUES (?,?,'TEACHER_REPORT_EXPORT_AUTHORIZED',?,?,?,?)",
            assessment,actor,report.schemaVersion(),summary.studentId(),attempt,resultVersion);
        return report;
    }
    private static void validateScope(int actor,int assessment,String attempt){
        if(actor<1||assessment<1)throw new IllegalArgumentException("管理身份或考核标识无效");
        PreparedJobInput.uuid(attempt);
    }
    private Loaded load(int actor,int assessment,String attempt){
            processing.reviewTheme(actor,assessment); // fresh role, manager scope, parent lock
            var students=jdbc.query("SELECT a.student_id,u.name FROM prepared_attempt a JOIN `user` u ON u.id=a.student_id WHERE a.assessment_id=? AND a.id=?",
                (rs,n)->new Student(rs.getInt("student_id"),rs.getString("name")),assessment,attempt);
            if(students.size()!=1)throw new BusinessException(403,"无权限或原尝试不存在");
            var student=students.getFirst();var originals=submissions.reviewSources(actor,student.id(),assessment,attempt);var scope=originals.attempt();var paper=originals.paper();
            var active=jdbc.queryForList("SELECT active_attempt_id FROM prepared_attempt_anchor WHERE assessment_id=? AND student_id=? AND record_id=? FOR UPDATE",String.class,assessment,student.id(),scope.recordId());
            if(active.size()!=1||active.getFirst()==null)throw new IllegalStateException("原尝试历史缺少有效锚点");
            boolean current=attempt.equals(active.getFirst()),showResult=Boolean.TRUE.equals(jdbc.queryForObject("SELECT show_result FROM assessment WHERE id=? AND deleted=0 FOR UPDATE",Boolean.class,assessment));
            var completed=jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=? FOR UPDATE",java.sql.Timestamp.class,attempt);
            if(!"SUBMITTED".equals(scope.state())){
                if(!Set.of("IN_PROGRESS","UPLOAD_PENDING","REQUIRES_REVIEW","CANCELLED").contains(scope.state())||completed!=null||!originals.sources().isEmpty())throw new IllegalStateException("原尝试与结果状态无法核验");
                return empty(actor,assessment,student,scope.attemptNo(),attempt,current,showResult,paper.rules().theme(),scope.state());
            }
            if(originals.sources().isEmpty()||originals.sources().size()>50||originals.sources().size()!=paper.questions().size())throw new IllegalStateException("原整场提交题数无法核验");
            var grades=originals.sources().stream().map(processing::studentGrade).toList(); // same verified effective head/provenance as student results
            if(grades.stream().anyMatch(Objects::isNull)){
                if(completed!=null)throw new IllegalStateException("首次完成锚点缺少有效原成绩");
                return empty(actor,assessment,student,scope.attemptNo(),attempt,current,showResult,paper.rules().theme(),"PROCESSING");
            }
            if(completed==null)throw new IllegalStateException("完整成绩缺少首次完成锚点");
            BigDecimal total=BigDecimal.ZERO;int max=0;var rows=new ArrayList<Grade>();
            for(var value:grades){total=total.add(value.score());max=Math.addExact(max,value.maxScore());rows.add(new Grade(value.answerId(),value.ordinal(),value.maxScore(),value.score(),value.scoringSource(),value.version(),value.confirmed()));}
            if(max!=paper.rules().totalScore())throw new IllegalStateException("原冻结试卷分值无法核验");
            String version=PreparedJobInput.digest(PreparedProcessingCodec.encode(Map.of("kind","prepared-manager-result-v1","assessmentId",assessment,"studentId",student.id(),
                "attemptId",attempt,"paperVersionId",scope.paperVersionId(),"current",current,"showResult",showResult,"grades",rows,"identities",grades.stream().map(PreparedProcessingService.StudentGrade::identity).toList())));
            return new Loaded(new Summary(actor,assessment,student.id(),student.name(),paper.rules().theme(),attempt,scope.attemptNo(),current,showResult,"READY",version,completed.toInstant().toString(),total.stripTrailingZeros(),max,paper.rules().passScore(),rows),grades);
    }
    private Loaded empty(int actor,int assessment,Student student,int attemptNo,String attempt,boolean current,boolean showResult,String theme,String state){
        return new Loaded(new Summary(actor,assessment,student.id(),student.name(),theme,attempt,attemptNo,current,showResult,state,null,null,null,null,null,List.of()),List.of());
    }
}
