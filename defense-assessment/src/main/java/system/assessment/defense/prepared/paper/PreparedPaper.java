package system.assessment.defense.prepared.paper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public final class PreparedPaper {
    private PreparedPaper() {}

    public record QuestionInput(
            @NotBlank String questionId,
            @NotBlank @Size(max = 10000) String body,
            @Min(1) @Max(10000) int maxScore,
            @Min(1) @Max(1800) int answerSeconds,
            @NotBlank @Size(max = 20000) String rubric) {}

    public record Question(String questionId, String source, String body, int ordinal,
                           int maxScore, int answerSeconds, String rubric, boolean approved,
                           String audioState, String audioObjectKey) {}

    public record Draft(int assessmentId, long revision, List<Question> questions) {
        public Draft { questions = List.copyOf(questions); }
    }

    public record SaveCommand(@NotNull @Min(0) Long expectedRevision,
                               @NotNull @Size(max = 50) List<@Valid QuestionInput> questions) {}

    public record ApproveCommand(@NotNull @Min(0) Long expectedRevision,
                                  @NotEmpty @Size(max = 50) List<@NotBlank String> questionIds) {}
}
