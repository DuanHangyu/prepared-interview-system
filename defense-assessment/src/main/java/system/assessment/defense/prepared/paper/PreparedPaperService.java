package system.assessment.defense.prepared.paper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.paper.PreparedPaper.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PreparedPaperService {
    private final ResourceAccessControlService access;
    private final PreparedPaperRepository repository;

    public PreparedPaperService(ResourceAccessControlService access, PreparedPaperRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Draft getDraft(int actorId, int assessmentId) {
        access.requireAssessmentManager(actorId, assessmentId);
        return repository.load(assessmentId);
    }

    @Transactional
    public Draft save(int actorId, int assessmentId, long expectedRevision, List<QuestionInput> inputs) {
        access.requireAssessmentManager(actorId, assessmentId);
        requireRevision(expectedRevision);
        if (inputs == null || inputs.size() > 50) throw new IllegalArgumentException("题目最多 50 道");
        Set<String> ids = new HashSet<>();
        for (QuestionInput q : inputs) {
            if (q == null || !validId(q.questionId()) || !ids.add(q.questionId())) {
                throw new IllegalArgumentException("题目标识无效或重复");
            }
            requireText(q.body(), 10000, "题目正文");
            requireText(q.rubric(), 20000, "评分要求");
            if (q.maxScore() < 1 || q.maxScore() > 10000 || q.answerSeconds() < 1 || q.answerSeconds() > 1800) {
                throw new IllegalArgumentException("题目分值或回答时限无效");
            }
        }
        Draft current = repository.loadForUpdate(assessmentId);
        requireCurrent(current, expectedRevision);
        var previous = current.questions().stream().collect(Collectors.toMap(Question::questionId, Function.identity()));
        List<Question> nextQuestions = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            QuestionInput q = inputs.get(index);
            Question old = previous.get(q.questionId());
            boolean sameBody = old != null && old.body().equals(q.body());
            boolean sameRules = sameBody && old.maxScore() == q.maxScore() && old.answerSeconds() == q.answerSeconds()
                    && old.rubric().equals(q.rubric());
            nextQuestions.add(new Question(q.questionId(), old == null ? "MANUAL" : old.source(), q.body(), index + 1,
                    q.maxScore(), q.answerSeconds(), q.rubric(), sameRules && old.approved(),
                    sameBody ? old.audioState() : old == null ? "PENDING" : "STALE",
                    sameBody ? old.audioObjectKey() : null));
        }
        return persist(new Draft(assessmentId, expectedRevision + 1, nextQuestions), expectedRevision, actorId);
    }

    @Transactional
    public Draft approve(int actorId, int assessmentId, long expectedRevision, List<String> questionIds) {
        access.requireAssessmentManager(actorId, assessmentId);
        requireRevision(expectedRevision);
        if (questionIds == null || questionIds.isEmpty() || questionIds.size() > 50) {
            throw new IllegalArgumentException("请选择要审核的题目");
        }
        Set<String> selected = new HashSet<>(questionIds);
        if (selected.size() != questionIds.size() || selected.stream().anyMatch(id -> !validId(id))) {
            throw new IllegalArgumentException("审核题目标识无效或重复");
        }
        Draft current = repository.loadForUpdate(assessmentId);
        requireCurrent(current, expectedRevision);
        Set<String> existing = current.questions().stream().map(Question::questionId).collect(Collectors.toSet());
        if (!existing.containsAll(selected)) throw new IllegalArgumentException("审核题目不属于当前草稿");
        List<Question> approved = current.questions().stream().map(q -> new Question(q.questionId(), q.source(), q.body(),
                q.ordinal(), q.maxScore(), q.answerSeconds(), q.rubric(), q.approved() || selected.contains(q.questionId()),
                q.audioState(), q.audioObjectKey())).toList();
        Draft next = persist(new Draft(assessmentId, expectedRevision + 1, approved), expectedRevision, actorId);
        repository.recordApproval(next, actorId, questionIds);
        return next;
    }

    /** Internal adoption path; callers must supply only server-validated suggestions. */
    @Transactional
    public Draft appendAi(int actorId, int assessmentId, long expectedRevision, List<QuestionInput> suggestions) {
        access.requireAssessmentManager(actorId, assessmentId);
        requireRevision(expectedRevision);
        if (suggestions == null || suggestions.isEmpty() || suggestions.size() > 20) {
            throw new IllegalArgumentException("请选择 1—20 道 AI 建议");
        }
        Draft current = repository.loadForUpdate(assessmentId);
        requireCurrent(current, expectedRevision);
        if (current.questions().size() + suggestions.size() > 50) throw new IllegalArgumentException("题目最多 50 道");
        Set<String> ids = current.questions().stream().map(Question::questionId).collect(Collectors.toSet());
        List<Question> next = new ArrayList<>(current.questions());
        for (QuestionInput q : suggestions) {
            if (q == null || !validId(q.questionId())) throw new IllegalArgumentException("题目标识无效");
            if (!ids.add(q.questionId())) throw conflict();
            requireText(q.body(), 10000, "题目正文"); requireText(q.rubric(), 20000, "评分要求");
            if (q.maxScore() < 1 || q.maxScore() > 10000 || q.answerSeconds() < 1 || q.answerSeconds() > 1800)
                throw new IllegalArgumentException("题目规则无效");
            next.add(new Question(q.questionId(), "AI", q.body(), next.size() + 1, q.maxScore(), q.answerSeconds(),
                    q.rubric(), false, "PENDING", null));
        }
        return persist(new Draft(assessmentId, expectedRevision + 1, next), expectedRevision, actorId);
    }

    private Draft persist(Draft next, long expectedRevision, int actorId) {
        if (!repository.replaceDraft(next, expectedRevision, actorId)) throw conflict();
        return next;
    }

    private static void requireCurrent(Draft current, long expectedRevision) {
        if (current.revision() != expectedRevision) throw conflict();
    }

    private static BusinessException conflict() {
        return new BusinessException(409, "试卷已被修改，请保留当前编辑内容并重新读取最新版本");
    }

    private static void requireRevision(long revision) {
        if (revision < 0 || revision == Long.MAX_VALUE) throw new IllegalArgumentException("草稿版本无效");
    }

    private static boolean validId(String id) {
        try { return id != null && UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException error) { return false; }
    }

    private static void requireText(String text, int maximum, String label) {
        if (text == null || text.isBlank() || text.length() > maximum) {
            throw new IllegalArgumentException(label + "不能为空且不能超过 " + maximum + " 字符");
        }
    }
}
