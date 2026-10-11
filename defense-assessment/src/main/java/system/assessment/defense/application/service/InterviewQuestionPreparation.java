package system.assessment.defense.application.service;

import cn.hutool.json.JSONArray;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

final class InterviewQuestionPreparation {

    private InterviewQuestionPreparation() {
    }

    static List<InterviewLiveQuestion> normalize(JSONArray questions, Integer expectedCount) {
        int targetCount = expectedCount == null ? 0 : Math.max(expectedCount, 0);
        List<InterviewLiveQuestion> normalized = new ArrayList<>();
        if (questions != null) {
            for (int i = 0; i < questions.size(); i++) {
                if (targetCount > 0 && normalized.size() >= targetCount) {
                    break;
                }
                cn.hutool.json.JSONObject questionObject = questions.getJSONObject(i);
                if (questionObject == null) {
                    continue;
                }
                String question = StringUtils.trimToEmpty(questionObject.getStr("question"));
                if (StringUtils.isBlank(question)) {
                    continue;
                }
                InterviewLiveQuestion liveQuestion = new InterviewLiveQuestion();
                liveQuestion.setQuestionDimension(StringUtils.defaultIfBlank(
                        StringUtils.trimToEmpty(questionObject.getStr("questionDimension")), "综合能力"));
                liveQuestion.setQuestion(question);
                liveQuestion.setFollowGenerated(false);
                normalized.add(liveQuestion);
            }
        }
        while (targetCount > 0 && normalized.size() < targetCount) {
            normalized.add(fallback(normalized.size() + 1));
        }
        return normalized;
    }

    private static InterviewLiveQuestion fallback(int index) {
        InterviewLiveQuestion question = new InterviewLiveQuestion();
        question.setQuestionDimension("综合能力");
        question.setQuestion("请结合你的答辩内容，补充说明第%s个关键技术点的依据、实现细节和验证结果。".formatted(index));
        question.setFollowGenerated(false);
        return question;
    }
}
