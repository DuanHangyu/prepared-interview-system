package system.assessment.defense.prepared.generation;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.paper.PreparedPaper.QuestionInput;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Model output is data only. Identity and approval are never supplied by a model. */
@Component
public class PreparedGenerationCodec {
    private final ObjectMapper json = new ObjectMapper();
    public List<QuestionInput> decode(String job, String content, int count, int score, int seconds) {
        PreparedJobInput.uuid(job);
        try {
            JsonNode root = json.readTree(PreparedJobInput.json(content, 262144));
            fields(root, Set.of("questions"));
            JsonNode questions = root.get("questions");
            if (count < 1 || count > 20 || !questions.isArray() || questions.size() != count) throw invalid();
            List<QuestionInput> result = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                JsonNode q = questions.get(index);
                fields(q, Set.of("body", "rubric", "maxScore", "answerSeconds"));
                if (!q.get("maxScore").isIntegralNumber() || !q.get("maxScore").canConvertToInt()
                        || q.get("maxScore").intValue() != score || score < 1 || score > 10000
                        || !q.get("answerSeconds").isIntegralNumber() || !q.get("answerSeconds").canConvertToInt()
                        || q.get("answerSeconds").intValue() != seconds || seconds < 1 || seconds > 1800) throw invalid();
                String id = UUID.nameUUIDFromBytes((job + ":suggestion:" + index).getBytes(StandardCharsets.UTF_8)).toString();
                result.add(new QuestionInput(id, text(q.get("body"), 10000), score, seconds, text(q.get("rubric"), 20000)));
            }
            return List.copyOf(result);
        } catch (java.io.IOException error) { throw invalid(); }
    }
    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw invalid();
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalid();
    }
    private static String text(JsonNode node, int max) {
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > max) throw invalid();
        return node.textValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("模型建议格式不符合要求，原草稿未改变"); }
}
