package system.assessment.defense.prepared.publication;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.util.List;

/** Teacher-only publication contracts. Never reuse the full snapshot as a student response. */
public final class PreparedPublication {
    private static final ObjectMapper JSON = new ObjectMapper();
    private PreparedPublication() {}
    public record Command(@NotNull @Min(0) Long expectedRevision, @NotBlank String idempotencyKey,
                          @NotBlank @Pattern(regexp="[a-f0-9]{64}") String expectedContentSha256) {
        public void validate() {
            if (expectedRevision == null || expectedRevision < 0 || expectedRevision == Long.MAX_VALUE)
                throw new IllegalArgumentException("草稿版本无效");
            PreparedJobInput.uuid(idempotencyKey);
            if (expectedContentSha256==null || !expectedContentSha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("请先取得当前发布检查的内容摘要");
        }
    }
    public record Issue(String code, String questionId, Integer ordinal, String message) {}
    public record Rules(String theme, Integer totalScore, Integer passScore, Integer questionCount, boolean showResult) {}
    public record Audio(String assetId, String bodySha256, String configSha256, String contentSha256,
                        long byteSize, long durationMs, String mediaType) {}
    public record Question(String questionId, String source, String body, int ordinal, int maxScore,
                           int answerSeconds, String rubric, Audio audio) {}
    public record Snapshot(int schemaVersion, int assessmentId, Rules rules, List<Question> questions) {
        public Snapshot { questions = List.copyOf(questions); }
    }
    public record Summary(String id, int versionNo, long draftRevision, String contentSha256,
                          int publishedBy, String publishedAt) {}
    public record View(Summary version, Snapshot snapshot) {}
    public record Status(int assessmentId, long draftRevision, Rules rules, boolean publishable, boolean frozen, String preparedContentSha256,
                         Summary activeVersion, List<Issue> issues, List<Summary> versions) {
        public Status { issues = List.copyOf(issues); versions = List.copyOf(versions); }
    }
    public static String encode(Snapshot snapshot) {
        try { return PreparedJobInput.json(JSON.writeValueAsString(snapshot), 6 * 1024 * 1024); }
        catch (java.io.IOException error) { throw new IllegalArgumentException("试卷快照无法编码"); }
    }
    public static Snapshot decode(String content) {
        try { return JSON.readValue(PreparedJobInput.json(content, 6 * 1024 * 1024), Snapshot.class); }
        catch (java.io.IOException error) { throw new IllegalArgumentException("试卷快照无法读取"); }
    }
}
