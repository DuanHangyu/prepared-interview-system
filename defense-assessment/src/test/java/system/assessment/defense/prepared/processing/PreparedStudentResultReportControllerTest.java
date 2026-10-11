package system.assessment.defense.prepared.processing;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import system.assessment.defense.infrastructure.config.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayInputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PreparedStudentResultReportControllerTest {
    @Test void fullReportUsesOnlyAuthenticatedStudentIdentityAndTheOriginalResultVersion()throws Exception {
        var service=mock(PreparedStudentResultService.class);var value=new PreparedStudentResultReportExporterTest().report("原题",null);
        when(service.report(8,21,PreparedStudentResultReportExporterTest.VERSION)).thenReturn(value);
        var mvc=MockMvcBuilders.standaloneSetup(new PreparedStudentResultController(service)).setControllerAdvice(new GlobalResponse(),new GlobalException()).addFilters(new NoStoreResponseFilter()).build();
        try(var auth=mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsInt).thenReturn(8);
            mvc.perform(get("/backend/prepared-results/21/report").param("resultVersion",PreparedStudentResultReportExporterTest.VERSION).param("studentId","9999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.studentId").value(8))
                .andExpect(jsonPath("$.data.summary.resultVersion").value(PreparedStudentResultReportExporterTest.VERSION))
                .andExpect(jsonPath("$.data.answers.length()").value(1)).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
        }
        verify(service).report(8,21,PreparedStudentResultReportExporterTest.VERSION);verifyNoMoreInteractions(service);
    }
    @Test void exportIsOneRealWorkbookNotWrappedJsonAndErrorsRemainSafeJson()throws Exception {
        var service=mock(PreparedStudentResultService.class);var value=new PreparedStudentResultReportExporterTest().report("原题",null);
        when(service.exportReport(8,21,PreparedStudentResultReportExporterTest.VERSION)).thenReturn(value);
        var mvc=MockMvcBuilders.standaloneSetup(new PreparedStudentResultController(service)).setControllerAdvice(new GlobalResponse(),new GlobalException()).addFilters(new NoStoreResponseFilter()).build();
        try(var auth=mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsInt).thenReturn(8);
            var response=mvc.perform(get("/backend/prepared-results/21/export.xlsx").param("resultVersion",PreparedStudentResultReportExporterTest.VERSION))
                .andExpect(status().isOk()).andExpect(content().contentType(PreparedStudentResultReportExporter.CONTENT_TYPE)).andReturn().getResponse();
            try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
                assertThat(workbook.getSheet("逐题结果").getRow(1).getCell(3).getStringCellValue()).isEqualTo("7.25");
            }
            when(service.exportReport(8,21,PreparedStudentResultReportExporterTest.VERSION)).thenThrow(new BusinessException(403,"教师尚未开放结果"));
            mvc.perform(get("/backend/prepared-results/21/export.xlsx").param("resultVersion",PreparedStudentResultReportExporterTest.VERSION))
                .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(403)).andExpect(header().doesNotExist("Content-Disposition"));
        }
    }
}
