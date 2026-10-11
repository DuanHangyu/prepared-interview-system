package system.assessment.defense.application.service;

import cn.hutool.json.JSONUtil;
import org.apache.commons.lang3.StringUtils;
import system.assessment.defense.application.dto.AssessmentEvaluationDTO;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;

/**
 * Converts persisted model output into a trusted application projection.
 */
final class StoredAssessmentEvaluationParser {

    private StoredAssessmentEvaluationParser() {
    }

    static AssessmentEvaluationDTO parse(String storedValue) {
        if (StringUtils.isBlank(storedValue)) {
            throw invalidEvaluation();
        }
        try {
            AssessmentEvaluationDTO evaluation =
                    JSONUtil.toBean(storedValue, AssessmentEvaluationDTO.class);
            if (evaluation == null) {
                throw invalidEvaluation();
            }
            evaluation.verifyIntegrity();
            return evaluation;
        } catch (RuntimeException exception) {
            throw invalidEvaluation();
        }
    }

    private static BusinessException invalidEvaluation() {
        return new BusinessException(ErrorCodeEnums.ANALYSIS_CHECK_FAIL);
    }
}
