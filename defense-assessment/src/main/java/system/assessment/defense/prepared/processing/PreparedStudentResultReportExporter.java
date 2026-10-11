package system.assessment.defense.prepared.processing;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Bounded private result export. Existing POI dependency is reused, never the legacy grade
 * parser. No cloud/model IO, user-supplied filenames, formulas, links or persistent temp files. */
@Component
public class PreparedStudentResultReportExporter {
    public static final String CONTENT_TYPE="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int CELL_CHARACTERS=16000, MAX_FILE_BYTES=32*1024*1024;
    private final Semaphore slots=new Semaphore(2);
    private record ExportScope(int studentId,int assessmentId,String theme,String attemptId,String resultVersion,String completedAt,
                               BigDecimal totalScore,Integer totalMaxScore,Integer passScore) {}
    private record WorkbookReport(ExportScope summary,List<PreparedStudentResultService.Answer> answers,
                                  PreparedReviewSummaryService.Summary review,String prefix) {}

    public void write(Supplier<PreparedStudentResultService.Report> reader,HttpServletResponse response)throws IOException {
        writeWithin(()->{
            var report=reader.get();var s=report.summary();
            return new WorkbookReport(new ExportScope(s.studentId(),s.assessmentId(),s.theme(),s.attemptId(),s.resultVersion(),s.completedAt(),s.totalScore(),s.totalMaxScore(),s.passScore()),report.answers(),null,"prepared-result");
        },response);
    }
    /** Only a neutral rendering projection is shared. Teacher readers retain their
     * own manager authorization; no student service call or public-result disguise. */
    public void writeReview(Supplier<PreparedReviewSummaryService.Report> reader,HttpServletResponse response)throws IOException {
        writeWithin(()->{
            var report=reader.get();var s=report.summary();
            return new WorkbookReport(new ExportScope(s.studentId(),s.assessmentId(),s.theme(),s.attemptId(),s.resultVersion(),s.completedAt(),s.totalScore(),s.totalMaxScore(),s.passScore()),report.answers(),s,"prepared-review");
        },response);
    }
    private void writeWithin(Supplier<WorkbookReport> reader,HttpServletResponse response)throws IOException {
        // Acquire BEFORE allocating a full result/workbook; retain through delivery. Busy
        // export must not consume an unbounded waiting thread or affect interview recording.
        if(!slots.tryAcquire())throw new BusinessException(503,"报告导出暂忙，请稍后重试；原回答和成绩不受影响");
        try {
            var report=reader.get();byte[] bytes=workbook(report);var summary=report.summary();
            response.setContentType(CONTENT_TYPE);
            response.setHeader("Content-Disposition","attachment; filename=\""+report.prefix()+"-"+summary.assessmentId()+"-"+summary.attemptId()+".xlsx\"");
            response.setHeader("Cache-Control","no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("X-Prepared-Result-Version",summary.resultVersion());
            response.setContentLength(bytes.length);response.getOutputStream().write(bytes);
        } finally {slots.release();}
    }
    private byte[] workbook(WorkbookReport report)throws IOException {
        var summary=report.summary();system.assessment.defense.prepared.jobs.PreparedJobInput.uuid(summary.attemptId());PreparedProcessingCodec.sha(summary.resultVersion());
        if(summary.studentId()<1||summary.assessmentId()<1)throw new IllegalStateException("原报告身份无效");
        try(var workbook=new XSSFWorkbook()) {
            var overview=workbook.createSheet("结果摘要");
            row(overview,"项目","值");row(overview,"考核主题",summary.theme());row(overview,"学生标识",Integer.toString(summary.studentId()));
            row(overview,"考核标识",Integer.toString(summary.assessmentId()));row(overview,"原尝试标识",summary.attemptId());row(overview,"结果读取版本",summary.resultVersion());
            row(overview,"首次完整结果完成",summary.completedAt());row(overview,"总分",summary.totalScore().toPlainString());
            row(overview,"总满分",Integer.toString(summary.totalMaxScore()));row(overview,"通过分数",Integer.toString(summary.passScore()));
            if(report.review()==null)row(overview,"说明","仅包含本次公开结果。技术故障不等于零分或缺考；暂无有效转写不表示未回答。后续核分或重评可能形成新版本。");
            else {
                var review=report.review();if(review.actorId()<1)throw new IllegalStateException("原管理身份无效");
                row(overview,"学生姓名",review.studentName()==null?"未登记姓名":review.studentName());row(overview,"管理操作者",Integer.toString(review.actorId()));
                row(overview,"尝试序号",Integer.toString(review.attemptNo()));row(overview,"尝试范围",review.current()?"当前尝试":"历史尝试，只读");
                row(overview,"学生结果设置",review.showResult()?"已开放":"尚未开放");
                row(overview,"说明","按本次管理权限读取该原尝试，不表示向学生公开。历史尝试只读；技术故障不等于零分或缺考，暂无有效转写不表示未回答。请妥善保管下载副本。");
            }
            var scores=workbook.createSheet("逐题结果");row(scores,"题号","题目","满分","得分","评分来源","评分版本","核分状态","评分反馈");
            var transcripts=workbook.createSheet("回答转写");row(transcripts,"题号","正文片段序号","原回答转写正文");
            for(var answer:report.answers()) {
                PreparedProcessingCodec.text(answer.body(),10000);
                if(answer.feedback()!=null)PreparedProcessingCodec.text(answer.feedback(),4000);
                row(scores,Integer.toString(answer.ordinal()),answer.body(),Integer.toString(answer.maxScore()),answer.score().toPlainString(),
                    "TEACHER".equals(answer.scoringSource())?"教师人工评分":"模型评分",Integer.toString(answer.version()),answer.confirmed()?"已核分":"尚未核分",answer.feedback()==null?"":answer.feedback());
                String content;
                if(answer.transcript()==null)content="暂无有效转写；不表示未回答，教师可依据原录音人工评分。";
                else {
                    var sentences=answer.transcript().sentences();if(sentences.isEmpty()||sentences.size()>10000)throw new IllegalStateException("原报告转写范围无效");
                    long characters=0,previous=0;
                    for(var sentence:sentences) {
                        PreparedProcessingCodec.text(sentence.text(),10000);characters+=sentence.text().length();
                        if(characters>60000||sentence.startMs()<previous||sentence.endMs()<=sentence.startMs()||sentence.endMs()>1800000)
                            throw new IllegalStateException("原报告转写内容无效");
                        previous=sentence.endMs();
                    }
                    content=sentences.stream().map(PreparedProcessing.Sentence::text).collect(Collectors.joining("\n"));
                }
                int part=0;
                for(int offset=0;offset<content.length();) {
                    int end=Math.min(offset+CELL_CHARACTERS,content.length());
                    if(end<content.length()&&Character.isHighSurrogate(content.charAt(end-1))&&Character.isLowSurrogate(content.charAt(end)))end--;
                    row(transcripts,Integer.toString(answer.ordinal()),Integer.toString(++part),content.substring(offset,end));offset=end;
                }
            }
            for(var sheet:workbook){sheet.createFreezePane(0,1);sheet.setColumnWidth(0,20*256);for(int c=1;c<(sheet==scores?8:sheet==transcripts?3:2);c++)sheet.setColumnWidth(c,36*256);}
            var output=new BoundedOutput();
            // Long legitimate repeated transcription can otherwise produce a ZIP
            // ratio rejected by POI's archive-bomb defense.
            // Keep those GLOBAL input defenses intact; only this generated report
            // uses non-compressing DEFLATE, within a fixed output/memory bound.
            try(var archive=new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(output)) {
                archive.setLevel(java.util.zip.Deflater.NO_COMPRESSION);workbook.write(archive);
            }
            return output.bytes();
        }
    }
    private static void row(Sheet sheet,String... values) {
        var row=sheet.createRow(sheet.getPhysicalNumberOfRows());
        for(int n=0;n<values.length;n++) {
            String value=values[n];if(value==null||value.length()>CELL_CHARACTERS)throw new IllegalStateException("原报告字段超出安全范围");
            // Explicit STRING cells, including text beginning with =,+,-,@. Never
            // infer formulas or hyperlinks from question/answer/provider content.
            row.createCell(n,CellType.STRING).setCellValue(value);
        }
    }
    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream data=new ByteArrayOutputStream(32768);
        @Override public void write(int value)throws IOException{check(1);data.write(value);}
        @Override public void write(byte[] values,int offset,int length)throws IOException{java.util.Objects.checkFromIndexSize(offset,length,values.length);check(length);data.write(values,offset,length);}
        private void check(int additional)throws IOException{if(additional>MAX_FILE_BYTES-data.size())throw new IOException("原报告文件超出安全大小，未生成下载");}
        byte[] bytes(){return data.toByteArray();}
    }
}
