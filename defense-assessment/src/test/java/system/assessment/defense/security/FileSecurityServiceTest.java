package system.assessment.defense.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import system.assessment.defense.application.service.FileSecurityService;
import system.assessment.defense.infrastructure.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSecurityServiceTest {

    private final FileSecurityService fileSecurityService = new FileSecurityService();

    @Test
    void validPdfRequiresMatchingNameMimeAndMagicBytes() {
        MockMultipartFile valid = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "%PDF-1.7\nbody".getBytes());

        assertThatCode(() -> fileSecurityService.requireSafePdf(valid))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDisguisedOrUnsupportedFiles() {
        MockMultipartFile wrongExtension = new MockMultipartFile(
                "file", "report.html", "application/pdf", "%PDF-1.7".getBytes());
        MockMultipartFile wrongMime = new MockMultipartFile(
                "file", "report.pdf", "text/html", "%PDF-1.7".getBytes());
        MockMultipartFile wrongMagic = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "<script>alert(1)</script>".getBytes());

        assertThatThrownBy(() -> fileSecurityService.requireSafePdf(wrongExtension))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fileSecurityService.requireSafePdf(wrongMime))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fileSecurityService.requireSafePdf(wrongMagic))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsEmptyAndOversizedPdf() {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);
        MockMultipartFile oversized = new MockMultipartFile(
                "file", "large.pdf", "application/pdf", new byte[FileSecurityService.MAX_PDF_BYTES + 1]);

        assertThatThrownBy(() -> fileSecurityService.requireSafePdf(empty))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fileSecurityService.requireSafePdf(oversized))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsOversizedClientFilenamesBeforeUpload() {
        MockMultipartFile pdf = new MockMultipartFile(
                "file", "x".repeat(252) + ".pdf", "application/pdf", "%PDF-1.7".getBytes());
        MockMultipartFile xlsx = new MockMultipartFile(
                "file", "x".repeat(251) + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{'P', 'K', 3, 4});

        assertThatThrownBy(() -> fileSecurityService.requireSafePdf(pdf))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fileSecurityService.requireSafeXlsx(xlsx))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void xlsxImportAlsoRequiresExtensionMimeAndZipSignature() {
        byte[] xlsxHeader = new byte[]{'P', 'K', 3, 4, 0, 0};
        MockMultipartFile valid = new MockMultipartFile(
                "file", "students.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxHeader);
        MockMultipartFile disguised = new MockMultipartFile(
                "file", "students.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "not-a-zip".getBytes());

        assertThatCode(() -> fileSecurityService.requireSafeXlsx(valid))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> fileSecurityService.requireSafeXlsx(disguised))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void bindingRequiresTheAuthenticatedUsersOwnerPrefix() {
        String owned = "defense-assessment-new/users/12/2026/10/03/0123456789abcdef0123456789abcdef.pdf";
        String anotherUsers = "defense-assessment-new/users/13/2026/10/03/0123456789abcdef0123456789abcdef.pdf";
        String legacy = "defense-assessment-new/2025/08/10/0123456789abcdef0123456789abcdef.pdf";

        assertThatCode(() -> fileSecurityService.requireOwnedPdfKey(12, owned))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> fileSecurityService.requireOwnedPdfKey(12, anotherUsers))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fileSecurityService.requireOwnedPdfKey(12, legacy))
                .isInstanceOf(BusinessException.class);
    }
}
