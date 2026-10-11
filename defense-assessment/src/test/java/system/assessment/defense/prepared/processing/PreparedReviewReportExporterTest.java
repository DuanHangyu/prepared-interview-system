package system.assessment.defense.prepared.processing;

import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.springframework.mock.web.MockHttpServletResponse;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PreparedReviewReportExporterTest {
    PreparedReviewSummaryService.Report report(boolean current,boolean visible){
        var f=new PreparedReviewReportContractTest();return new PreparedReviewSummaryService.Report(1,f.summary("READY",current,visible),List.of(f.answer()));
    }
    @Test void workbookKeepsActualTeacherScopeAndNeverClaimsHiddenHistoryIsCurrentPublicResults()throws Exception{
        for(boolean current:List.of(true,false))for(boolean visible:List.of(true,false)){
            var response=new MockHttpServletResponse();new PreparedStudentResultReportExporter().writeReview(()->report(current,visible),response);
            assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"prepared-review-21-"+PreparedReviewReportContractTest.ATTEMPT+".xlsx\"");
            assertThat(response.getHeader("X-Prepared-Result-Version")).isEqualTo(PreparedReviewReportContractTest.VERSION);
            assertThat(response.getContentLength()).isEqualTo(response.getContentAsByteArray().length);
            try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))){
                var values=new java.util.HashMap<String,String>();
                for(var row:workbook.getSheet("结果摘要"))values.put(row.getCell(0).getStringCellValue(),row.getCell(1).getStringCellValue());
                assertThat(values).containsEntry("学生姓名","原学生").containsEntry("管理操作者","7").containsEntry("尝试序号","1")
                    .containsEntry("尝试范围",current?"当前尝试":"历史尝试，只读").containsEntry("学生结果设置",visible?"已开放":"尚未开放");
                assertThat(values.get("说明")).contains("管理权限","不表示向学生公开").doesNotContain("仅包含本次公开结果");
                for(var sheet:workbook)for(var row:sheet)for(var cell:row){assertThat(cell.getCellType()).isEqualTo(CellType.STRING);assertThat(cell.getHyperlink()).isNull();}
            }
        }
    }
    @Test void studentsAndTeachersShareOneBoundedCapacityAndBusyRequestsDoNotLoadAnotherReport()throws Exception{
        var exporter=new PreparedStudentResultReportExporter();var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        var reads=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try{
            Runnable wait=()->{reads.incrementAndGet();entered.countDown();try{assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}};
            var student=pool.submit(()->{exporter.write(()->{wait.run();return new PreparedStudentResultReportExporterTest().report("原题",null);},new MockHttpServletResponse());return null;});
            var teacher=pool.submit(()->{exporter.writeReview(()->{wait.run();return report(false,false);},new MockHttpServletResponse());return null;});
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->exporter.writeReview(()->{reads.incrementAndGet();return report(true,true);},new MockHttpServletResponse()))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
            assertThatThrownBy(()->exporter.write(()->{reads.incrementAndGet();return new PreparedStudentResultReportExporterTest().report("原题",null);},new MockHttpServletResponse()))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
            assertThat(reads.get()).isEqualTo(2);release.countDown();student.get(10,TimeUnit.SECONDS);teacher.get(10,TimeUnit.SECONDS);
            exporter.writeReview(()->report(false,false),new MockHttpServletResponse());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void teacherAuthorizationFailureDoesNotCommitHeadersAndReleasesTheSharedSlot()throws Exception{
        var exporter=new PreparedStudentResultReportExporter();var response=new MockHttpServletResponse();
        assertThatThrownBy(()->exporter.writeReview(()->{throw new BusinessException(403,"拒绝");},response)).isInstanceOf(BusinessException.class);
        assertThat(response.getContentAsByteArray()).isEmpty();assertThat(response.getContentType()).isNull();
        exporter.write(()->new PreparedStudentResultReportExporterTest().report("原题",null),new MockHttpServletResponse());
    }
    /** Corrupt internal projections, not an alternative authorization or real media fixture. */
    PreparedReviewSummaryService.Report scoped(int actor,int student,int assessment,String name,String theme,String attempt,String version,String completed){
        var base=report(false,false);var s=base.summary();var a=base.answers().getFirst();
        var summary=new PreparedReviewSummaryService.Summary(actor,assessment,student,name,theme,attempt,s.attemptNo(),false,false,"READY",version,completed,
            s.totalScore(),s.totalMaxScore(),s.passScore(),s.answers());
        var answer=new PreparedStudentResultService.Answer(student,assessment,attempt,version,a.answerId(),1,a.body(),a.maxScore(),a.score(),a.scoringSource(),a.version(),a.confirmed(),null,null);
        return new PreparedReviewSummaryService.Report(1,summary,List.of(answer));
    }
    PreparedReviewSummaryService.Report content(String body,String feedback,List<PreparedProcessing.Sentence> sentences){
        var base=report(false,false);var a=base.answers().getFirst();
        return new PreparedReviewSummaryService.Report(1,base.summary(),List.of(new PreparedStudentResultService.Answer(a.studentId(),a.assessmentId(),a.attemptId(),a.resultVersion(),
            a.answerId(),a.ordinal(),body,a.maxScore(),a.score(),a.scoringSource(),a.version(),a.confirmed(),feedback,sentences==null?null:new PreparedStudentResultService.Transcript(sentences))));
    }
    void rejectsBeforeDelivery(PreparedReviewSummaryService.Report value)throws Exception{
        var exporter=new PreparedStudentResultReportExporter();var response=new MockHttpServletResponse();
        assertThatThrownBy(()->exporter.writeReview(()->value,response)).isInstanceOf(RuntimeException.class);
        assertThat(response.getContentType()).isNull();assertThat(response.getContentAsByteArray()).isEmpty();assertThat(response.getHeader("Content-Disposition")).isNull();
        // Repeated failures must not consume either shared slot or poison the next valid file.
        exporter.writeReview(()->report(false,false),new MockHttpServletResponse());
        exporter.write(()->new PreparedStudentResultReportExporterTest().report("原题",null),new MockHttpServletResponse());
    }
    @Test void corruptOriginalIdentityAndUnsafeOverviewFieldsCannotProduceDownloadHeaders()throws Exception{
        var s=report(false,false).summary();
        for(var value:List.of(
            scoped(0,8,21,s.studentName(),s.theme(),s.attemptId(),s.resultVersion(),s.completedAt()),
            scoped(7,0,21,s.studentName(),s.theme(),s.attemptId(),s.resultVersion(),s.completedAt()),
            scoped(7,8,0,s.studentName(),s.theme(),s.attemptId(),s.resultVersion(),s.completedAt()),
            scoped(7,8,21,s.studentName(),s.theme(),"bad",s.resultVersion(),s.completedAt()),
            scoped(7,8,21,s.studentName(),s.theme(),s.attemptId(),"bad",s.completedAt()),
            scoped(7,8,21,s.studentName(),null,s.attemptId(),s.resultVersion(),s.completedAt()),
            scoped(7,8,21,s.studentName(),"题".repeat(16001),s.attemptId(),s.resultVersion(),s.completedAt()),
            scoped(7,8,21,"名".repeat(16001),s.theme(),s.attemptId(),s.resultVersion(),s.completedAt()),
            scoped(7,8,21,s.studentName(),s.theme(),s.attemptId(),s.resultVersion(),null)))rejectsBeforeDelivery(value);
    }
    @Test void missingRegisteredNameHasAnHonestLabelAndLiteralFeedbackRemainsComplete()throws Exception{
        var s=report(false,false).summary();var response=new MockHttpServletResponse();
        new PreparedStudentResultReportExporter().writeReview(()->scoped(7,8,21,null,s.theme(),s.attemptId(),s.resultVersion(),s.completedAt()),response);
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))){
            var fields=new java.util.HashMap<String,String>();
            for(var row:workbook.getSheet("结果摘要"))fields.put(row.getCell(0).getStringCellValue(),row.getCell(1).getStringCellValue());
            assertThat(fields).containsEntry("学生姓名","未登记姓名");
        }
        var feedback="=HYPERLINK(\"https://untrusted.invalid\")\n仅作为原反馈文字";
        var second=new MockHttpServletResponse();new PreparedStudentResultReportExporter().writeReview(()->content("原题",feedback,null),second);
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(second.getContentAsByteArray()))){
            var cell=workbook.getSheet("逐题结果").getRow(1).getCell(7);assertThat(cell.getStringCellValue()).isEqualTo(feedback);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);assertThat(cell.getHyperlink()).isNull();
        }
    }
    @Test void emptyOrOversizedTranscriptionAndMalformedTextAreNotExportedAsSuccessfulReports()throws Exception{
        rejectsBeforeDelivery(content("原题",null,List.of()));
        rejectsBeforeDelivery(content("原题",null,java.util.Collections.nCopies(10001,new PreparedProcessing.Sentence(0,1,"原句"))));
        for(String text:java.util.Arrays.asList(null,""," ","字".repeat(10001),"\u0000","\uD800")){
            rejectsBeforeDelivery(content(text,null,null));
            rejectsBeforeDelivery(content("原题",null,List.of(new PreparedProcessing.Sentence(0,1,text))));
        }
        for(String feedback:List.of(" ","字".repeat(4001),"\u0000"))rejectsBeforeDelivery(content("原题",feedback,null));
    }
    @Test void chronologyAndCombinedContentLimitsFailBeforeAnyResponseBytes()throws Exception{
        for(var sentences:List.of(
            List.of(new PreparedProcessing.Sentence(-1,1,"原句")),
            List.of(new PreparedProcessing.Sentence(0,0,"原句")),
            List.of(new PreparedProcessing.Sentence(0,1800001,"原句")),
            List.of(new PreparedProcessing.Sentence(0,2,"前句"),new PreparedProcessing.Sentence(1,3,"后句")),
            java.util.stream.IntStream.range(0,7).mapToObj(n->new PreparedProcessing.Sentence(n,n+1,"字".repeat(10000))).toList()))
            rejectsBeforeDelivery(content("原题",null,sentences));
    }
}
