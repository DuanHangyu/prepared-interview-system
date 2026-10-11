package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentQuestionAnswerPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * @USER taoHouChao
 * @DATE 23:39 2025/8/17
 */
@Data
public class AssessmentEvaluationDTO {

    private static final int MAX_EVALUATION_RESULTS = 100;
    private static final int MAX_ANALYSIS_ITEMS = 50;
    private static final int MAX_ANALYSIS_LIST_ITEMS = 50;
    private static final int MAX_NAME_CHARS = 200;
    private static final int MAX_QUESTION_CHARS = 2_000;
    private static final int MAX_ANSWER_CHARS = 10_000;
    private static final int MAX_DESCRIPTION_CHARS = 4_000;
    private static final int MAX_SUMMARY_CHARS = 8_000;
    private static final int MAX_DEFENSE_ANSWER_CHARS = 50_000;

    @Schema(description = "问答")
    private List<AnswerEvaluationDTO> result;

    @Schema(description = "考核信息")
    private DefenseDTO defense;

    @Schema(description = "分析结果")
    private AnalysisBO analysis;

    @Schema(description = "总结")
    private String summary;

    public List<ValueDTO> getValue() {
        return analysis.getAnalysis().stream()
                .map(item -> new ValueDTO(item.getName(), item.getScore()))
                .toList();
    }

    public void fillEmptyValue(Map<Integer, StudentAssessmentQuestionAnswerPO> quesitonAnswerIdMap, StudentAssessmentRecordPO recordPO) {
        if (CollectionUtils.isNotEmpty(result)) {
            for (AnswerEvaluationDTO evaluation : result) {
                Optional.ofNullable(quesitonAnswerIdMap.get(evaluation.getId()))
                        .ifPresent(item -> {
                            if (StringUtils.isBlank(evaluation.getQuestion())) {
                                evaluation.setQuestion(item.getQuestion());
                            }
                            if (StringUtils.isBlank(evaluation.getAnswer())) {
                                evaluation.setAnswer(item.getAnswer());
                            }
                            if (StringUtils.isBlank(evaluation.getFollowQuestion())) {
                                evaluation.setFollowQuestion(item.getFollowQuestion());
                            }
                            if (StringUtils.isBlank(evaluation.getFollowAnswer())) {
                                evaluation.setFollowAnswer(item.getFollowAnswer());
                            }
                        });
            }
        }
        Optional.ofNullable(defense)
                .ifPresent(item -> {
                    if (StringUtils.isBlank(item.getDefenseAnswer())) {
                        item.setDefenseAnswer(recordPO.getDefenseContent());
                    }
                });
    }

    /**
     * 验证数据完整性
     */
    public void verifyIntegrity() {
        verifyIntegrity(null);
    }

    public void verifyIntegrity(String schemaType) {
        if (analysis == null || CollectionUtils.isEmpty(analysis.getAnalysis())
                || analysis.getAnalysis().size() > MAX_ANALYSIS_ITEMS) {
            throw new BusinessException(ErrorCodeEnums.ANALYSIS_CHECK_FAIL);
        }
        if (StringUtils.isBlank(summary)
                || summary.trim().length() > MAX_SUMMARY_CHARS
                || invalidTextList(analysis.getSuggestions())
                || invalidTextList(analysis.getStrengths())
                || invalidTextList(analysis.getWeaknesses())
                || analysis.getAnalysis().stream().anyMatch(item -> item == null
                || StringUtils.isBlank(item.getName())
                || item.getName().trim().length() > MAX_NAME_CHARS
                || StringUtils.isBlank(item.getDescription())
                || item.getDescription().trim().length() > MAX_DESCRIPTION_CHARS
                || item.getScore() == null)) {
            throw new BusinessException(ErrorCodeEnums.ANALYSIS_CHECK_FAIL);
        }
        int count = (int) analysis.getAnalysis().stream()
                .map(AnalysisDTO::getName)
                .distinct()
                .count();
        if (count != analysis.getAnalysis().size()) {
            throw new BusinessException(ErrorCodeEnums.ANALYSIS_CHECK_FAIL);
        }
        if ("question".equals(schemaType) || "defense_question".equals(schemaType)) {
            if (result == null || result.size() > MAX_EVALUATION_RESULTS
                    || result.stream().anyMatch(this::invalidAnswerEvaluation)) {
                throw new BusinessException(ErrorCodeEnums.ANALYSIS_CHECK_FAIL);
            }
            long distinctIds = result.stream().map(AnswerEvaluationDTO::getId).distinct().count();
            if (distinctIds != result.size()) {
                throw new BusinessException(ErrorCodeEnums.ANALYSIS_CHECK_FAIL);
            }
        }
        if ("defense".equals(schemaType) || "defense_question".equals(schemaType)) {
            if (defense == null || defense.getDefenseAnswer() == null
                    || defense.getDefenseAnswer().length() > MAX_DEFENSE_ANSWER_CHARS) {
                throw new BusinessException(ErrorCodeEnums.ANALYSIS_CHECK_FAIL);
            }
        }
    }

    private boolean invalidTextList(List<String> values) {
        return values == null || values.size() > MAX_ANALYSIS_LIST_ITEMS
                || values.stream().anyMatch(value -> StringUtils.isBlank(value)
                || value.trim().length() > MAX_DESCRIPTION_CHARS);
    }

    private boolean invalidAnswerEvaluation(AnswerEvaluationDTO answer) {
        return answer == null || answer.getId() == null
                || invalidText(answer.getQuestion(), MAX_QUESTION_CHARS)
                || invalidText(answer.getAnswer(), MAX_ANSWER_CHARS)
                || invalidText(answer.getFollowQuestion(), MAX_QUESTION_CHARS)
                || invalidText(answer.getFollowAnswer(), MAX_ANSWER_CHARS);
    }

    private boolean invalidText(String value, int maximum) {
        return value == null || value.length() > maximum;
    }

    public void normalizeScores(Integer totalScore) {
        verifyIntegrity();
        int maximum = totalScore == null ? 0 : Math.max(0, totalScore);
        List<AnalysisDTO> scoreItems = analysis.getAnalysis();
        int[] sanitized = scoreItems.stream()
                .mapToInt(item -> Math.max(0, Math.min(maximum, item.getScore())))
                .toArray();
        long sum = IntStream.of(sanitized).asLongStream().sum();
        if (sum <= maximum) {
            for (int index = 0; index < sanitized.length; index++) {
                scoreItems.get(index).setScore(sanitized[index]);
            }
            return;
        }

        int[] normalized = new int[sanitized.length];
        double[] remainders = new double[sanitized.length];
        int allocated = 0;
        for (int index = 0; index < sanitized.length; index++) {
            double scaled = sanitized[index] * (double) maximum / sum;
            normalized[index] = (int) Math.floor(scaled);
            remainders[index] = scaled - normalized[index];
            allocated += normalized[index];
        }
        List<Integer> order = IntStream.range(0, sanitized.length)
                .boxed()
                .sorted(Comparator.comparingDouble((Integer index) -> remainders[index]).reversed())
                .toList();
        for (int index = 0; allocated < maximum && index < order.size(); index++, allocated++) {
            normalized[order.get(index)]++;
        }
        for (int index = 0; index < normalized.length; index++) {
            scoreItems.get(index).setScore(normalized[index]);
        }
    }
}
