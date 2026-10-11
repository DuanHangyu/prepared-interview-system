package system.assessment.defense.prepared.processing;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class PreparedStudentResultReportExporterTest {
    static final String ATTEMPT="11111111-1111-4111-8111-111111111111", ANSWER="22222222-2222-4222-8222-222222222222", VERSION="a".repeat(64);
    PreparedStudentResultService.Report report(String body, String transcription) {
        var grade=new PreparedStudentResultService.Grade(ANSWER,1,10,new BigDecimal("7.25"),"TEACHER",1,true);
        var summary=new PreparedStudentResultService.Summary(8,21,"=HYPERLINK(\"https://untrusted.invalid\")",true,"READY",ATTEMPT,VERSION,
            "2026-10-05T00:00:00Z",new BigDecimal("7.25"),10,6,List.of(grade));
        var transcript=transcription==null?null:new PreparedStudentResultService.Transcript(List.of(new PreparedProcessing.Sentence(0,1000,transcription)));
        return new PreparedStudentResultService.Report(1,summary,List.of(new PreparedStudentResultService.Answer(8,21,ATTEMPT,VERSION,ANSWER,1,
            body,10,grade.score(),"TEACHER",1,true,null,transcript)));
    }
    @Test void completeWorkbookUsesOnlyLiteralStringsAndExactDecimalValues() throws Exception {
        var response=new MockHttpServletResponse();var value=report("+SUM(1,2)","@原回答\n<script>不会执行</script>");
        new PreparedStudentResultReportExporter().write(()->value,response);
        assertThat(response.getContentType()).isEqualTo(PreparedStudentResultReportExporter.CONTENT_TYPE);
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"prepared-result-21-"+ATTEMPT+".xlsx\"");
        assertThat(response.getHeader("X-Prepared-Result-Version")).isEqualTo(VERSION);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getContentLength()).isEqualTo(response.getContentAsByteArray().length);
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
            assertThat(workbook.getSheet("逐题结果").getRow(1).getCell(1).getStringCellValue()).isEqualTo("+SUM(1,2)");
            assertThat(workbook.getSheet("逐题结果").getRow(1).getCell(3).getStringCellValue()).isEqualTo("7.25");
            assertThat(workbook.getSheet("回答转写").getRow(1).getCell(2).getStringCellValue()).isEqualTo("@原回答\n<script>不会执行</script>");
            for(var sheet:workbook)for(var row:sheet)for(var cell:row) {
                assertThat(cell.getCellType()).isEqualTo(CellType.STRING);assertThat(cell.getHyperlink()).isNull();
            }
        }
    }
    @Test void longOriginalTranscriptionIsPreservedAcrossCellsWithoutSplittingSupplementaryCharacters() throws Exception {
        double protection=org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio();
        var sentences=List.of(new PreparedProcessing.Sentence(0,1000,"甲".repeat(9999)),new PreparedProcessing.Sentence(1000,2000,"乙".repeat(5998)+"😀"),
            new PreparedProcessing.Sentence(2000,3000,"丙".repeat(10000)),new PreparedProcessing.Sentence(3000,4000,"丁".repeat(10000)),
            new PreparedProcessing.Sentence(4000,5000,"戊".repeat(10000)),new PreparedProcessing.Sentence(5000,6000,"己".repeat(10000)));
        var base=report("原题",null);var a=base.answers().getFirst();
        var value=new PreparedStudentResultService.Report(1,base.summary(),List.of(new PreparedStudentResultService.Answer(a.studentId(),a.assessmentId(),a.attemptId(),a.resultVersion(),
            a.answerId(),a.ordinal(),a.body(),a.maxScore(),a.score(),a.scoringSource(),a.version(),a.confirmed(),null,new PreparedStudentResultService.Transcript(sentences))));
        var response=new MockHttpServletResponse();new PreparedStudentResultReportExporter().write(()->value,response);
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet=workbook.getSheet("回答转写");var reconstructed=new StringBuilder();
            assertThat(sheet.getLastRowNum()).isGreaterThan(1);
            for(int n=1;n<=sheet.getLastRowNum();n++) {
                String part=sheet.getRow(n).getCell(2).getStringCellValue();assertThat(part.length()).isLessThanOrEqualTo(16000);
                assertThat(Character.isHighSurrogate(part.charAt(part.length()-1))).isFalse();reconstructed.append(part);
            }
            assertThat(reconstructed.toString()).isEqualTo(sentences.stream().map(PreparedProcessing.Sentence::text).collect(java.util.stream.Collectors.joining("\n")));
        }
        assertThat(org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio()).isEqualTo(protection).isGreaterThanOrEqualTo(0.01);
    }
    @Test void absentTranscriptionIsHonestAndDoesNotBecomeAnEmptyAnswerOrAutomaticZero() throws Exception {
        var response=new MockHttpServletResponse();new PreparedStudentResultReportExporter().write(()->report("原题",null),response);
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(workbook.getSheet("回答转写").getRow(1).getCell(2).getStringCellValue()).contains("暂无有效转写","不表示未回答");
            assertThat(workbook.getSheet("逐题结果").getRow(1).getCell(3).getStringCellValue()).isEqualTo("7.25");
        }
    }
    @Test void rejectedAuthorizationDoesNotCommitHeadersOrProduceADownloadAndReleasesCapacity() throws Exception {
        var exporter=new PreparedStudentResultReportExporter();var response=new MockHttpServletResponse();
        assertThatThrownBy(()->exporter.write(()->{throw new BusinessException(403,"结果未开放");},response)).isInstanceOf(BusinessException.class);
        assertThat(response.getContentAsByteArray()).isEmpty();assertThat(response.getContentType()).isNull();
        exporter.write(()->report("原题",null),new MockHttpServletResponse());
    }
    @Test void responseFailureReleasesCapacityWithoutChangingOriginalResults() throws Exception {
        var exporter=new PreparedStudentResultReportExporter();var response=new MockHttpServletResponse(){
            @Override public jakarta.servlet.ServletOutputStream getOutputStream(){return new jakarta.servlet.ServletOutputStream(){
                public boolean isReady(){return true;}public void setWriteListener(jakarta.servlet.WriteListener listener){}
                public void write(int value)throws IOException{throw new IOException("synthetic-client-disconnected");}
            };}
        };
        assertThatThrownBy(()->exporter.write(()->report("原题",null),response)).isInstanceOf(IOException.class);
        exporter.write(()->report("原题",null),new MockHttpServletResponse());
    }
    @Test void busyExportsRejectWithoutWaitingOrReadingAnotherResultAndKeepSlotsUntilDeliveryEnds() throws Exception {
        var exporter=new PreparedStudentResultReportExporter();var pool=Executors.newFixedThreadPool(2);
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var reads=new AtomicInteger();
        try {
            Callable<Void> task=()->{exporter.write(()->{reads.incrementAndGet();entered.countDown();try{assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return report("原题",null);},new MockHttpServletResponse());return null;};
            var first=pool.submit(task);var second=pool.submit(task);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->exporter.write(()->{reads.incrementAndGet();return report("不应读取",null);},new MockHttpServletResponse()))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
            assertThat(reads.get()).isEqualTo(2);release.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
            exporter.write(()->report("原题",null),new MockHttpServletResponse());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void mixedResultVersionsAreRejectedBeforeAnyWorkbookBytes() throws Exception {
        var base=report("原题",null);var a=base.answers().getFirst();
        var wrong=new PreparedStudentResultService.Answer(a.studentId()+1,a.assessmentId(),a.attemptId(),a.resultVersion(),a.answerId(),a.ordinal(),a.body(),a.maxScore(),a.score(),a.scoringSource(),a.version(),a.confirmed(),null,null);
        var response=new MockHttpServletResponse();
        assertThatThrownBy(()->new PreparedStudentResultReportExporter().write(()->new PreparedStudentResultService.Report(1,base.summary(),List.of(wrong)),response))
            .isInstanceOf(IllegalStateException.class);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }
}
