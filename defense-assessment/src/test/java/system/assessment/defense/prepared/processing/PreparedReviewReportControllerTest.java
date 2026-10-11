package system.assessment.defense.prepared.processing;

import cn.dev33.satoken.stp.StpUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import system.assessment.defense.infrastructure.config.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Routing/response contract doubles, not live authorization evidence. */
class PreparedReviewReportControllerTest {
    static final String ATTEMPT=PreparedReviewReportContractTest.ATTEMPT,VERSION=PreparedReviewReportContractTest.VERSION;
    PreparedReviewSummaryService.Report report(){
        var f=new PreparedReviewReportContractTest();return new PreparedReviewSummaryService.Report(1,f.summary("READY",false,false),List.of(f.answer()));
    }
    @Test void teacherReportUsesOnlySessionActorAndExactOriginalAttemptAndVersion()throws Exception{
        var service=mock(PreparedReviewSummaryService.class);when(service.report(7,21,ATTEMPT,VERSION)).thenReturn(report());
        var mvc=MockMvcBuilders.standaloneSetup(new PreparedReviewSummaryController(service,new PreparedStudentResultReportExporter()))
            .setControllerAdvice(new GlobalResponse(),new GlobalException()).addFilters(new NoStoreResponseFilter()).build();
        try(var auth=mockStatic(StpUtil.class)){
            auth.when(StpUtil::getLoginIdAsInt).thenReturn(7);
            mvc.perform(get("/backend/prepared-reviews/21/attempts/"+ATTEMPT+"/report").param("resultVersion",VERSION).param("actorId","9999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.actorId").value(7))
                .andExpect(jsonPath("$.data.summary.current").value(false)).andExpect(jsonPath("$.data.summary.showResult").value(false))
                .andExpect(jsonPath("$.data.answers.length()").value(1)).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
        }
        verify(service).report(7,21,ATTEMPT,VERSION);verifyNoMoreInteractions(service);
    }
    @Test void teacherExportIsSafeBinaryAndDeniedReadsNeverProduceDownloadHeaders()throws Exception{
        var service=mock(PreparedReviewSummaryService.class);when(service.exportReport(7,21,ATTEMPT,VERSION)).thenReturn(report());
        var mvc=MockMvcBuilders.standaloneSetup(new PreparedReviewSummaryController(service,new PreparedStudentResultReportExporter()))
            .setControllerAdvice(new GlobalResponse(),new GlobalException()).addFilters(new NoStoreResponseFilter()).build();
        try(var auth=mockStatic(StpUtil.class)){
            auth.when(StpUtil::getLoginIdAsInt).thenReturn(7);
            var response=mvc.perform(get("/backend/prepared-reviews/21/attempts/"+ATTEMPT+"/export.xlsx").param("resultVersion",VERSION))
                .andExpect(status().isOk()).andExpect(content().contentType(PreparedStudentResultReportExporter.CONTENT_TYPE)).andReturn().getResponse();
            try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))){
                assertThat(workbook.getSheet("逐题结果").getRow(1).getCell(3).getStringCellValue()).isEqualTo("7.25");
            }
            when(service.exportReport(7,21,ATTEMPT,VERSION)).thenThrow(new BusinessException(403,"管理权限已变化"));
            mvc.perform(get("/backend/prepared-reviews/21/attempts/"+ATTEMPT+"/export.xlsx").param("resultVersion",VERSION))
                .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(403)).andExpect(header().doesNotExist("Content-Disposition"));
        }
    }
}
