package system.assessment.defense.infrastructure.aop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogAspectTest {

    @Test
    void requestSummaryNeverSerializesArgumentContent() {
        SensitiveArgument argument = new SensitiveArgument();

        String summary = LogAspect.summarizeArguments(new Object[]{argument, "student-original-answer"});

        assertEquals("count=2, types=[SensitiveArgument, String]", summary);
        assertFalse(argument.toStringCalled);
        assertFalse(summary.contains("student-original-answer"));
    }

    @Test
    void responseSummaryOnlyIncludesTheResultType() {
        String summary = LogAspect.summarizeResult("model-original-output");

        assertEquals("String", summary);
        assertFalse(summary.contains("model-original-output"));
    }

    @Test
    void sanitizeForLogMasksSecrets() {
        String content = """
                {"account":"admin","password":"plain-password","oldPassword":"old-secret","newPassword":"new-secret","token":"raw-token","Authorization":"Bearer abc"}
                """;

        String sanitized = LogAspect.sanitizeForLog(content);

        assertTrue(sanitized.contains("\"password\":\"***\""));
        assertTrue(sanitized.contains("\"oldPassword\":\"***\""));
        assertTrue(sanitized.contains("\"newPassword\":\"***\""));
        assertTrue(sanitized.contains("\"token\":\"***\""));
        assertTrue(sanitized.contains("\"Authorization\":\"***\""));
        assertFalse(sanitized.contains("plain-password"));
        assertFalse(sanitized.contains("old-secret"));
        assertFalse(sanitized.contains("new-secret"));
        assertFalse(sanitized.contains("raw-token"));
        assertFalse(sanitized.contains("Bearer abc"));
    }

    @Test
    void sanitizeForLogTruncatesLongPayloads() {
        String sanitized = LogAspect.sanitizeForLog("a".repeat(1200));

        assertTrue(sanitized.length() < 1100);
        assertTrue(sanitized.contains("truncated,length=1200"));
    }

    private static final class SensitiveArgument {
        private boolean toStringCalled;

        @Override
        public String toString() {
            toStringCalled = true;
            return "password=secret,answer=student-original-answer";
        }
    }
}
