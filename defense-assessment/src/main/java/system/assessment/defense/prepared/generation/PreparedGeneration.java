package system.assessment.defense.prepared.generation;

import com.fasterxml.jackson.databind.*;
import jakarta.validation.constraints.*;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.paper.PreparedPaper;
import java.util.*;

public final class PreparedGeneration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private PreparedGeneration() {}
    public record Command(@NotNull @Min(0) Long expectedRevision, @NotBlank String idempotencyKey,
                          @NotBlank @Size(max=8000) String requirements,
                          @Min(1) @Max(20) int count, @Min(1) @Max(10000) int maxScore,
                          @Min(1) @Max(1800) int answerSeconds) {
        public void validate() {
            PreparedJobInput.uuid(idempotencyKey);
            if (expectedRevision == null || expectedRevision < 0 || expectedRevision == Long.MAX_VALUE
                    || requirements == null || requirements.isBlank() || requirements.length() > 8000
                    || count < 1 || count > 20 || maxScore < 1 || maxScore > 10000 || answerSeconds < 1 || answerSeconds > 1800)
                throw new IllegalArgumentException("出题要求、题数、分值、时限或草稿版本无效");
        }
    }
    public record Input(Command command, PreparedGenerationPolicy.Snapshot policy) {}
    public record AdoptCommand(@NotNull @Min(0) Long expectedRevision, @NotBlank String idempotencyKey,
                               @NotEmpty @Size(max=20) List<@NotBlank String> questionIds) {
        public void validate() {
            PreparedJobInput.uuid(idempotencyKey);
            if (expectedRevision == null || expectedRevision < 0 || expectedRevision == Long.MAX_VALUE
                    || questionIds == null || questionIds.isEmpty() || questionIds.size() > 20
                    || new HashSet<>(questionIds).size() != questionIds.size()) throw new IllegalArgumentException("采用命令无效");
            questionIds.forEach(PreparedJobInput::uuid);
        }
    }
    public record Summary(String id, String state, long inputVersion, String errorCode, String createdAt) {}
    public record Listing(PreparedGenerationPolicy.Availability availability, List<Summary> jobs) {}
    public record View(String id, String state, long inputVersion, String errorCode, String createdAt,
                       List<PreparedPaper.QuestionInput> suggestions, List<String> adoptedQuestionIds) {}
    public record Adoption(PreparedPaper.Draft draft, long adoptedRevision) {}
    public static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (java.io.IOException error) { throw new IllegalArgumentException("出题任务数据无效"); }
    }
    public static Input input(String value) {
        try {
            Input input = JSON.readValue(PreparedJobInput.json(value,65536), Input.class);
            if (input.command() == null || input.policy() == null) throw new IllegalArgumentException("出题任务数据无效");
            input.command().validate(); return input;
        } catch (java.io.IOException error) { throw new IllegalArgumentException("出题任务数据无效"); }
    }
}
