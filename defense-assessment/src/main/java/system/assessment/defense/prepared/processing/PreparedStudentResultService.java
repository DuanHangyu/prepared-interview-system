package system.assessment.defense.prepared.processing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.publication.PreparedPublication;
import java.math.BigDecimal;
import java.util.*;

/** Read-only current student's results. No writes, paid calls, storage IO, legacy grade parsing,
 * manager impersonation or cached authorization. Parent locks fence visibility and head changes. */
@Service
public class PreparedStudentResultService {
    public record Grade(String answerId,int ordinal,int maxScore,BigDecimal score,String scoringSource,int version,boolean confirmed) {}
    public record Summary(int studentId,int assessmentId,String theme,boolean prepared,String state,String attemptId,String resultVersion,
                          String completedAt,BigDecimal totalScore,Integer totalMaxScore,Integer passScore,List<Grade> answers){public Summary{answers=List.copyOf(answers);}}
    public record Transcript(List<PreparedProcessing.Sentence> sentences){public Transcript{sentences=List.copyOf(sentences);}}
    public record Answer(int studentId,int assessmentId,String attemptId,String resultVersion,String answerId,int ordinal,String body,int maxScore,
                         BigDecimal score,String scoringSource,int version,boolean confirmed,String feedback,Transcript transcript) {}
    public record Report(int schemaVersion,Summary summary,List<Answer> answers) {
        public Report {
            answers=List.copyOf(answers);
            if(schemaVersion!=1||summary==null||!"READY".equals(summary.state())||summary.answers().size()!=answers.size()||answers.isEmpty()||answers.size()>50)
                throw new IllegalStateException("原结果报告不完整");
            for(int n=0;n<answers.size();n++) {
                var a=answers.get(n);var g=summary.answers().get(n);
                if(a.studentId()!=summary.studentId()||a.assessmentId()!=summary.assessmentId()||!Objects.equals(a.attemptId(),summary.attemptId())
                    ||!Objects.equals(a.resultVersion(),summary.resultVersion())||!Objects.equals(a.answerId(),g.answerId())||a.ordinal()!=n+1||g.ordinal()!=a.ordinal()
                    ||a.maxScore()!=g.maxScore()||a.score()==null||g.score()==null||a.score().compareTo(g.score())!=0||!Objects.equals(a.scoringSource(),g.scoringSource())
                    ||a.version()!=g.version()||a.confirmed()!=g.confirmed())throw new IllegalStateException("原结果报告绑定无法核验");
            }
        }
    }
    private record Loaded(Summary summary,List<PreparedProcessingService.StudentGrade> grades) {}
    private final PreparedSubmissionService submissions;private final PreparedProcessingService processing;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public PreparedStudentResultService(PreparedSubmissionService submissions,PreparedProcessingService processing,JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.submissions=submissions;this.processing=processing;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);
    }
    public Summary summary(int student,int assessment){return tx.execute(s->load(student,assessment).summary());}
    /** One parent-locked read, not a collection of separately fetched/mixed answer versions. */
    public Report report(int student,int assessment,String resultVersion){
        PreparedProcessingCodec.sha(resultVersion);
        return tx.execute(s->reportWithin(student,assessment,resultVersion,false));
    }
    /** Audit authorization, not proof that a client actually received the downloaded file. */
    public Report exportReport(int student,int assessment,String resultVersion){
        PreparedProcessingCodec.sha(resultVersion);
        return tx.execute(s->reportWithin(student,assessment,resultVersion,true));
    }
    private Report reportWithin(int student,int assessment,String resultVersion,boolean exporting){
        var loaded=load(student,assessment);var summary=loaded.summary();
        if("HIDDEN".equals(summary.state()))throw new BusinessException(403,"教师尚未开放结果");
        if(!"READY".equals(summary.state()))throw new BusinessException(409,"原结果尚不可查看，请刷新结果并保留原回答");
        if(!resultVersion.equals(summary.resultVersion()))throw new BusinessException(409,"结果版本已变化，请刷新整场结果后读取报告");
        var answers=loaded.grades().stream().map(value->new Answer(student,assessment,summary.attemptId(),resultVersion,value.answerId(),value.ordinal(),value.body(),value.maxScore(),
            value.score(),value.scoringSource(),value.version(),value.confirmed(),value.feedback(),value.sentences()==null?null:new Transcript(value.sentences()))).toList();
        var report=new Report(1,summary,answers);
        if(exporting)jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version,original_student_id,original_attempt_id,resource_sha256) VALUES (?,?,'STUDENT_REPORT_EXPORT_AUTHORIZED',?,?,?,?)",
            assessment,student,report.schemaVersion(),student,summary.attemptId(),summary.resultVersion());
        return report;
    }
    public Answer answer(int student,int assessment,String answer,String resultVersion){
        PreparedJobInput.uuid(answer);PreparedProcessingCodec.sha(resultVersion);
        return tx.execute(s->{var current=load(student,assessment);var summary=current.summary();
            if("HIDDEN".equals(summary.state()))throw new BusinessException(403,"教师尚未开放结果");
            if(!"READY".equals(summary.state()))throw new BusinessException(409,"原结果尚不可查看，请刷新结果并保留原回答");
            if(!resultVersion.equals(summary.resultVersion()))throw new BusinessException(409,"结果版本已变化，请刷新整场结果后查看本题");
            var value=current.grades().stream().filter(g->g.answerId().equals(answer)).findFirst().orElseThrow(()->new BusinessException(403,"无权限或原回答不存在"));
            return new Answer(student,assessment,summary.attemptId(),resultVersion,value.answerId(),value.ordinal(),value.body(),value.maxScore(),value.score(),value.scoringSource(),
                value.version(),value.confirmed(),value.feedback(),value.sentences()==null?null:new Transcript(value.sentences()));
        });
    }
    private Loaded load(int student,int assessment){
        if(student<1||assessment<1)throw new IllegalArgumentException("身份或考核标识无效");
        var attempts=new PreparedAttemptStartRepository(jdbc);attempts.lockAssessment(assessment);
        var eligibility=new PreparedStudentEligibilityRepository(jdbc).contextForUpdate(student,assessment); // fresh role, assignment and parent scope
        var current=attempts.currentForUpdate(student,assessment);
        if(current.isEmpty()){
            if(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_attempt WHERE assessment_id=? AND student_id=?",Integer.class,assessment,student)!=0
                ||jdbc.queryForObject("SELECT COUNT(*) FROM prepared_attempt_anchor WHERE assessment_id=? AND student_id=?",Integer.class,assessment,student)!=0)throw new IllegalStateException("原预置尝试锚点无法核验");
            boolean prepared=jdbc.queryForObject("SELECT COUNT(*) FROM prepared_paper_draft WHERE assessment_id=?",Integer.class,assessment)>0;
            return empty(student,assessment,eligibility.theme(),prepared,prepared?"NOT_STARTED":"LEGACY",null);
        }
        var attempt=current.orElseThrow();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM student_assessment_record WHERE id=? AND assessment_id=? AND student_id=?",Integer.class,attempt.recordId(),assessment,student)!=1)throw new IllegalStateException("原考核记录与学生绑定无法核验");
        var completed=jdbc.queryForObject("SELECT first_results_completed_at FROM prepared_attempt WHERE id=? FOR UPDATE",java.sql.Timestamp.class,attempt.id());
        if(!"SUBMITTED".equals(attempt.state())){
            if(!Set.of("IN_PROGRESS","UPLOAD_PENDING","REQUIRES_REVIEW","CANCELLED").contains(attempt.state())||completed!=null
                ||jdbc.queryForObject("SELECT COUNT(*) FROM prepared_submission WHERE attempt_id=?",Integer.class,attempt.id())!=0)throw new IllegalStateException("原尝试与提交状态无法核验");
            return empty(student,assessment,eligibility.theme(),true,attempt.state(),attempt.id());
        }
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT show_result FROM assessment WHERE id=? AND deleted=0 FOR UPDATE",Boolean.class,assessment)))return empty(student,assessment,eligibility.theme(),true,"HIDDEN",attempt.id());
        var originals=submissions.currentSubmittedSources(student,assessment,attempt.id());
        var grades=originals.stream().map(processing::studentGrade).toList();
        if(grades.stream().anyMatch(Objects::isNull)){
            if(completed!=null)throw new IllegalStateException("首次完整结果锚点缺少有效原成绩");
            return empty(student,assessment,eligibility.theme(),true,"PROCESSING",attempt.id());
        }
        if(completed==null)throw new IllegalStateException("有效整场结果缺少首次完成锚点");
        var paper=attempts.boundPaperForUpdate(assessment,attempt.paperVersionId());
        PreparedStudentPaperVerifier.verify(assessment,paper.content(),paper.sha256());var snapshot=PreparedPublication.decode(paper.content());
        BigDecimal total=BigDecimal.ZERO;int max=0;var rows=new ArrayList<Grade>();
        for(var value:grades){total=total.add(value.score());max=Math.addExact(max,value.maxScore());rows.add(new Grade(value.answerId(),value.ordinal(),value.maxScore(),value.score(),value.scoringSource(),value.version(),value.confirmed()));}
        if(max!=snapshot.rules().totalScore()||rows.size()!=snapshot.questions().size())throw new IllegalStateException("整场原分值或题数无法核验");
        String version=PreparedJobInput.digest(PreparedProcessingCodec.encode(Map.of("kind","prepared-student-result-v1","studentId",student,"assessmentId",assessment,"attemptId",attempt.id(),"paperSha256",paper.sha256(),"grades",rows,"resultIdentities",grades.stream().map(PreparedProcessingService.StudentGrade::identity).toList())));
        return new Loaded(new Summary(student,assessment,eligibility.theme(),true,"READY",attempt.id(),version,completed.toInstant().toString(),total.stripTrailingZeros(),max,snapshot.rules().passScore(),rows),grades);
    }
    private Loaded empty(int student,int assessment,String theme,boolean prepared,String state,String attempt){
        return new Loaded(new Summary(student,assessment,theme,prepared,state,attempt,null,null,null,null,null,List.of()),List.of());
    }
}
