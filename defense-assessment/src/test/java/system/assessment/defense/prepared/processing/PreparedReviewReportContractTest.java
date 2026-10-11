package system.assessment.defense.prepared.processing;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Report DTO contract only. No authorization, SQL, real media, provider or QA claim.
 * Actual application contract tests; integration is validated separately. */
class PreparedReviewReportContractTest {
    static final String ATTEMPT="11111111-1111-4111-8111-111111111111",ANSWER="22222222-2222-4222-8222-222222222222",VERSION="a".repeat(64);
    PreparedReviewSummaryService.Summary summary(String state,boolean current,boolean visible){
        var grade=new PreparedReviewSummaryService.Grade(ANSWER,1,10,new BigDecimal("7.25"),"TEACHER",1,true);
        return new PreparedReviewSummaryService.Summary(7,21,8,"原学生","冻结主题",ATTEMPT,1,current,visible,state,
            VERSION,"2026-10-06T00:00:00Z",new BigDecimal("7.25"),10,6,List.of(grade));
    }
    PreparedStudentResultService.Answer answer(){
        return new PreparedStudentResultService.Answer(8,21,ATTEMPT,VERSION,ANSWER,1,"冻结原题",10,new BigDecimal("7.25"),"TEACHER",1,true,null,null);
    }
    @Test void currentHiddenAndHistoricalTeacherReportsKeepTheirActualManagerMetadata(){
        for(boolean current:List.of(true,false))for(boolean visible:List.of(true,false)){
            var summary=summary("READY",current,visible);var report=new PreparedReviewSummaryService.Report(1,summary,List.of(answer()));
            assertEquals(summary,report.summary());assertEquals(1,report.schemaVersion());assertEquals(List.of(answer()),report.answers());
            assertEquals(current,report.summary().current());assertEquals(visible,report.summary().showResult());assertEquals(7,report.summary().actorId());
        }
    }
    @Test void reportCannotInventCompletionOrAcceptAnUnsupportedSchema(){
        assertThrows(IllegalStateException.class,()->new PreparedReviewSummaryService.Report(2,summary("READY",true,false),List.of(answer())));
        assertThrows(IllegalStateException.class,()->new PreparedReviewSummaryService.Report(1,null,List.of(answer())));
        for(String state:List.of("PROCESSING","IN_PROGRESS","UPLOAD_PENDING","REQUIRES_REVIEW","CANCELLED","HIDDEN","BROKEN"))
            assertThrows(IllegalStateException.class,()->new PreparedReviewSummaryService.Report(1,summary(state,true,false),List.of(answer())));
    }
    @Test void reportRejectsEmptyOversizedOrDuplicatedAnswerRanges(){
        var summary=summary("READY",true,false);
        for(var rows:List.of(List.<PreparedStudentResultService.Answer>of(),List.of(answer(),answer()),Collections.nCopies(51,answer())))
            assertThrows(IllegalStateException.class,()->new PreparedReviewSummaryService.Report(1,summary,rows));
    }
    @Test void reportRequiresEachOriginalScopeAndEffectiveGradeToMatchTheSummary(){
        var s=summary("READY",true,false);var a=answer();
        var wrong=List.of(
            new PreparedStudentResultService.Answer(9,21,ATTEMPT,VERSION,ANSWER,1,a.body(),10,a.score(),"TEACHER",1,true,null,null),
            new PreparedStudentResultService.Answer(8,22,ATTEMPT,VERSION,ANSWER,1,a.body(),10,a.score(),"TEACHER",1,true,null,null),
            new PreparedStudentResultService.Answer(8,21,ANSWER,VERSION,ANSWER,1,a.body(),10,a.score(),"TEACHER",1,true,null,null),
            new PreparedStudentResultService.Answer(8,21,ATTEMPT,"b".repeat(64),ANSWER,1,a.body(),10,a.score(),"TEACHER",1,true,null,null),
            new PreparedStudentResultService.Answer(8,21,ATTEMPT,VERSION,ATTEMPT,1,a.body(),10,a.score(),"TEACHER",1,true,null,null),
            new PreparedStudentResultService.Answer(8,21,ATTEMPT,VERSION,ANSWER,2,a.body(),10,a.score(),"TEACHER",1,true,null,null),
            new PreparedStudentResultService.Answer(8,21,ATTEMPT,VERSION,ANSWER,1,a.body(),11,a.score(),"TEACHER",1,true,null,null),
            new PreparedStudentResultService.Answer(8,21,ATTEMPT,VERSION,ANSWER,1,a.body(),10,BigDecimal.ZERO,"TEACHER",1,true,null,null),
            new PreparedStudentResultService.Answer(8,21,ATTEMPT,VERSION,ANSWER,1,a.body(),10,null,"TEACHER",1,true,null,null),
            new PreparedStudentResultService.Answer(8,21,ATTEMPT,VERSION,ANSWER,1,a.body(),10,a.score(),"MODEL",1,true,null,null),
            new PreparedStudentResultService.Answer(8,21,ATTEMPT,VERSION,ANSWER,1,a.body(),10,a.score(),"TEACHER",2,true,null,null),
            new PreparedStudentResultService.Answer(8,21,ATTEMPT,VERSION,ANSWER,1,a.body(),10,a.score(),"TEACHER",1,false,null,null));
        for(var row:wrong)assertThrows(IllegalStateException.class,()->new PreparedReviewSummaryService.Report(1,s,List.of(row)));
    }
    @Test void reportCopiesTheAnswerListAndKeepsDecimalScaleSemanticallyEquivalent(){
        var original=answer();var a=new PreparedStudentResultService.Answer(original.studentId(),original.assessmentId(),original.attemptId(),original.resultVersion(),
            original.answerId(),original.ordinal(),original.body(),original.maxScore(),new BigDecimal("7.2500"),original.scoringSource(),original.version(),original.confirmed(),null,null);
        var rows=new ArrayList<>(List.of(a));var report=new PreparedReviewSummaryService.Report(1,summary("READY",true,false),rows);rows.clear();
        assertEquals(List.of(a),report.answers());assertThrows(UnsupportedOperationException.class,()->report.answers().clear());
    }
}
