package system.assessment.defense.application.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 上传文件与 OSS 对象键的白名单校验。系统业务上传仅接受 PDF。
 */
@Service
public class FileSecurityService {

    public static final int MAX_PDF_BYTES = 20 * 1024 * 1024;
    public static final int MAX_XLSX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ORIGINAL_FILENAME_LENGTH = 255;
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_MAGIC = new byte[]{'P', 'K', 3, 4};
    private static final Pattern SAFE_PDF_KEY = Pattern.compile(
            "^defense-assessment-new/(?:users/\\d+/)?\\d{4}/\\d{2}/\\d{2}/[a-f0-9]{32}\\.pdf$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern USER_OWNED_PDF_KEY = Pattern.compile(
            "^defense-assessment-new/users/(\\d+)/\\d{4}/\\d{2}/\\d{2}/[a-f0-9]{32}\\.pdf$",
            Pattern.CASE_INSENSITIVE);

    public void requireSafePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCodeEnums.INVALID_FILE);
        }
        if (file.getSize() > MAX_PDF_BYTES) {
            throw new BusinessException(ErrorCodeEnums.FILE_TOO_LARGE);
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null
                || originalFilename.length() > MAX_ORIGINAL_FILENAME_LENGTH
                || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".pdf")
                || originalFilename.contains("\0")) {
            throw new BusinessException(ErrorCodeEnums.INVALID_FILE);
        }
        if (!"application/pdf".equalsIgnoreCase(file.getContentType())) {
            throw new BusinessException(ErrorCodeEnums.INVALID_FILE);
        }
        requireMagic(file, PDF_MAGIC);
    }

    public void requireSafeXlsx(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCodeEnums.INVALID_FILE);
        }
        if (file.getSize() > MAX_XLSX_BYTES) {
            throw new BusinessException(ErrorCodeEnums.FILE_TOO_LARGE);
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null
                || originalFilename.length() > MAX_ORIGINAL_FILENAME_LENGTH
                || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".xlsx")
                || originalFilename.contains("\0")
                || !"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                .equalsIgnoreCase(file.getContentType())) {
            throw new BusinessException(ErrorCodeEnums.INVALID_FILE);
        }
        requireMagic(file, ZIP_MAGIC);
    }

    public void requireSafePdfKey(String key) {
        if (key == null || !SAFE_PDF_KEY.matcher(key).matches()) {
            throw new BusinessException(ErrorCodeEnums.INVALID_FILE);
        }
    }

    /**
     * A file reference submitted for a new business binding must belong to the
     * authenticated uploader. Legacy keys remain readable through the resource
     * ACL, but cannot be attached to an unrelated record as if they were new.
     */
    public void requireOwnedPdfKey(Integer ownerId, String key) {
        requireSafePdfKey(key);
        var matcher = USER_OWNED_PDF_KEY.matcher(key);
        if (ownerId == null || !matcher.matches()
                || !ownerId.toString().equals(matcher.group(1))) {
            throw new BusinessException(ErrorCodeEnums.NO_PERMISSION);
        }
    }

    public boolean isOwnedPdfKey(Integer ownerId, String key) {
        if (ownerId == null || key == null) {
            return false;
        }
        var matcher = USER_OWNED_PDF_KEY.matcher(key);
        return matcher.matches() && ownerId.toString().equals(matcher.group(1));
    }

    public Optional<Integer> ownerIdOfUserPdfKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        var matcher = USER_OWNED_PDF_KEY.matcher(key);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.valueOf(matcher.group(1)));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private void requireMagic(MultipartFile file, byte[] expectedMagic) {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(expectedMagic.length);
            if (header.length != expectedMagic.length) {
                throw new BusinessException(ErrorCodeEnums.INVALID_FILE);
            }
            for (int index = 0; index < expectedMagic.length; index++) {
                if (header[index] != expectedMagic[index]) {
                    throw new BusinessException(ErrorCodeEnums.INVALID_FILE);
                }
            }
        } catch (IOException exception) {
            throw new BusinessException(ErrorCodeEnums.INVALID_FILE);
        }
    }
}
