package system.assessment.defense.infrastructure.common;

import org.junit.jupiter.api.Test;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import system.assessment.defense.application.dto.StudentExcelDTO;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExcelUtilsBoundaryTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void malformedWorkbookIsReportedAsAStableBusinessError() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "students.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "not-an-excel-workbook".getBytes()
        );

        assertThatThrownBy(() -> ExcelUtils.readExcel(file, StudentExcelDTO.class))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(100);
    }

    @Test
    void importParsingStopsAfterTheBusinessLimitSentinelRow() throws IOException {
        byte[] workbookBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("students");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("姓名");
            header.createCell(1).setCellValue("学号");
            header.createCell(2).setCellValue("密码");
            header.createCell(3).setCellValue("班级");
            for (int index = 1; index <= 1_002; index++) {
                Row row = sheet.createRow(index);
                row.createCell(0).setCellValue("Student " + index);
                row.createCell(1).setCellValue("account-" + index);
                row.createCell(2).setCellValue("password-" + index);
                row.createCell(3).setCellValue("class-1");
            }
            workbook.write(output);
            workbookBytes = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "students.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                workbookBytes);

        List<StudentExcelDTO> students = ExcelUtils.readExcel(file, StudentExcelDTO.class);

        assertThat(students).hasSize(1_001);
        assertThat(students.getLast().getAccount()).isEqualTo("account-1001");
    }

    @Test
    void xlsxParsingTightensPoisArchiveExpansionLimit() {
        assertThatThrownBy(() -> ExcelUtils.readExcel(new MockMultipartFile(
                                "file",
                                "invalid.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "invalid".getBytes()),
                        StudentExcelDTO.class))
                .isInstanceOf(BusinessException.class);

        assertThat(ZipSecureFile.getMaxEntrySize()).isLessThanOrEqualTo(16L * 1024 * 1024);
        assertThat(ZipSecureFile.getMaxTextSize()).isLessThanOrEqualTo(10L * 1024 * 1024);
        assertThat(ZipSecureFile.getMaxFileCount()).isLessThanOrEqualTo(1_000);
    }

    @Test
    void templateExportReportsResponseWriteFailures() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenThrow(new IOException("client disconnected"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest(), response));

        assertThatThrownBy(() -> ExcelUtils.exportTemplate(
                List.of(StudentExcelDTO.empty()), "学生导入模版"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("client disconnected");
    }
}
