package system.assessment.defense.infrastructure.common;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.json.JSONUtil;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import system.assessment.defense.application.dto.StudentExcelDTO;
import static org.junit.jupiter.api.Assertions.*;

/** Dependency security and existing account/Excel adapter regression.
 * No cloud, network, application boot or physical-device integration. */
class HutoolCompatibilitySafetyTest {
    public static class Bean {
        private String name;
        private BigDecimal amount;
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal value) { amount = value; }
    }

    @AfterEach void releaseRequestContext() { RequestContextHolder.resetRequestAttributes(); }

    @Test void finiteDecimalConversionKeepsExistingSupportedFormats() {
        var samples = Map.of("0", "0", "-0.25", "-0.25", "1234.56", "1234.56",
            "1,234.56", "1234.56", "9.0E+7", "90000000");
        samples.forEach((input, expected) ->
            assertEquals(0, NumberUtil.toBigDecimal(input).compareTo(new BigDecimal(expected))));
    }

    @Test void stringNaNIsRejectedWithAControlledExceptionInsteadOfStackOverflow() {
        assertThrows(IllegalArgumentException.class, () -> NumberUtil.toBigDecimal("NaN"));
    }

    @Test void numericNaNIsRejectedWithAControlledExceptionInsteadOfStackOverflow() {
        assertThrows(IllegalArgumentException.class, () -> NumberUtil.toBigDecimal(Double.NaN));
    }

    @Test void infinitiesAreRejectedWithAControlledExceptionInsteadOfStackOverflow() {
        for (double value : List.of(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
            assertThrows(IllegalArgumentException.class, () -> NumberUtil.toBigDecimal(value));
    }

    @Test void unicodePlainTextAndDecimalJsonRoundTripRemainCompatible() {
        var input = JSONUtil.toJsonStr(Map.of("name", "中文😀<script>纯文本</script>",
            "amount", new BigDecimal("7.2500")));
        var result = JSONUtil.toBean(input, Bean.class);
        assertEquals("中文😀<script>纯文本</script>", result.getName());
        assertEquals(0, result.getAmount().compareTo(new BigDecimal("7.25")));
        assertEquals(result.getName(), JSONUtil.toBean(JSONUtil.toJsonStr(result), Bean.class).getName());
    }

    @Test void beanCopyDoesNotAliasOrMutateTheOriginalAccountData() {
        var original = new Bean();
        original.setName("原账号😀"); original.setAmount(new BigDecimal("7.25"));
        var copy = BeanUtil.copyProperties(original, Bean.class);
        original.setName("更新原账号");
        assertNotSame(original, copy); assertEquals("原账号😀", copy.getName());
        assertEquals(new BigDecimal("7.25"), copy.getAmount());
    }

    @Test void existingStudentWorkbookAdapterPreservesAliasesLeadingZerosAndUnicode() throws Exception {
        var response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), response));
        var student = new StudentExcelDTO("学生😀", "00001234", "fixture-only-not-a-credential", "班级甲");
        ExcelUtils.exportExcel(List.of(student));
        byte[] bytes = response.getContentAsByteArray();
        assertTrue(bytes.length > 0);
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.getContentType());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("姓名", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("学号", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals(CellType.STRING, sheet.getRow(1).getCell(1).getCellType());
            assertEquals("00001234", sheet.getRow(1).getCell(1).getStringCellValue());
        }
        var file = new MockMultipartFile("file", "students.xlsx", response.getContentType(), bytes);
        var reread = ExcelUtils.readExcel(file, StudentExcelDTO.class);
        assertEquals(List.of(student), reread);
    }
}
