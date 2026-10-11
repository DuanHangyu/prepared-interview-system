package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.paper.PreparedPaper;

import static org.assertj.core.api.Assertions.assertThat;

class PreparedPaperCommandValidationTest {
    @Test void savingRequiresAnExplicitExpectedRevisionRatherThanDefaultingToZero() throws Exception {
        var command = new ObjectMapper().readValue("{\"questions\":[]}", PreparedPaper.SaveCommand.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(command)).anyMatch(v -> v.getPropertyPath().toString().equals("expectedRevision"));
        }
    }

    @Test void reviewAlsoRequiresAnExplicitExpectedRevision() throws Exception {
        var command = new ObjectMapper().readValue("{\"questionIds\":[\"11111111-1111-4111-8111-111111111111\"]}", PreparedPaper.ApproveCommand.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(command)).anyMatch(v -> v.getPropertyPath().toString().equals("expectedRevision"));
        }
    }
}
