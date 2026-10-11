package system.assessment.defense.application.dto;

import org.junit.jupiter.api.Test;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssessmentEvaluationDTOTest {

    @Test
    void normalizesNegativeAndExcessiveAiScoresToAssessmentTotal() {
        AssessmentEvaluationDTO evaluation = evaluationWithScores(-8, 90, 90);

        evaluation.normalizeScores(100);

        List<Integer> scores = evaluation.getAnalysis().getAnalysis().stream()
                .map(AnalysisDTO::getScore)
                .toList();
        assertThat(scores).allMatch(score -> score >= 0 && score <= 100);
        assertThat(scores.stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
    }

    @Test
    void rejectsIncompleteEvaluationEvenWhenJsonCouldDeserialize() {
        AssessmentEvaluationDTO evaluation = evaluationWithScores(80);
        evaluation.setSummary(null);

        assertThatThrownBy(() -> evaluation.verifyIntegrity("question"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsDuplicateQuestionIdsAndOversizedAnalysisText() {
        AssessmentEvaluationDTO evaluation = evaluationWithScores(80);
        AnswerEvaluationDTO first = AnswerEvaluationDTO.builder()
                .id(7).question("问题").answer("回答").followQuestion("").followAnswer("").build();
        AnswerEvaluationDTO duplicate = AnswerEvaluationDTO.builder()
                .id(7).question("问题二").answer("回答二").followQuestion("").followAnswer("").build();
        evaluation.setResult(List.of(first, duplicate));

        assertThatThrownBy(() -> evaluation.verifyIntegrity("question"))
                .isInstanceOf(BusinessException.class);

        evaluation.setResult(List.of(first));
        evaluation.getAnalysis().getAnalysis().getFirst().setDescription("x".repeat(4_001));
        assertThatThrownBy(() -> evaluation.verifyIntegrity("question"))
                .isInstanceOf(BusinessException.class);
    }

    private AssessmentEvaluationDTO evaluationWithScores(int... scores) {
        AnalysisBO analysis = new AnalysisBO();
        analysis.setAnalysis(java.util.stream.IntStream.range(0, scores.length)
                .mapToObj(index -> {
                    AnalysisDTO item = new AnalysisDTO();
                    item.setName("维度" + index);
                    item.setDescription("评价" + index);
                    item.setScore(scores[index]);
                    return item;
                })
                .toList());
        analysis.setSuggestions(List.of("建议"));
        analysis.setStrengths(List.of("优势"));
        analysis.setWeaknesses(List.of("不足"));

        AssessmentEvaluationDTO evaluation = new AssessmentEvaluationDTO();
        evaluation.setAnalysis(analysis);
        evaluation.setResult(List.of());
        evaluation.setSummary("总结");
        return evaluation;
    }
}
