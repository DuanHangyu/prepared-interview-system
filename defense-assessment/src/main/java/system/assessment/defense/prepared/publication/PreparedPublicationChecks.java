package system.assessment.defense.prepared.publication;

import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.paper.PreparedPaper;
import java.util.*;
import static system.assessment.defense.prepared.publication.PreparedPublication.*;

/** Fail-closed checks, independent of HTTP and providers. READY in a draft is not storage evidence. */
public final class PreparedPublicationChecks {
    private PreparedPublicationChecks() {}
    @FunctionalInterface public interface Assets {
        Optional<PreparedPublicationRepository.Asset> find(int assessment, String question, String key);
    }
    public record Checked(List<Issue> issues, Snapshot snapshot) {
        public Checked { issues=List.copyOf(issues); }
    }
    public static Checked inspect(PreparedPaper.Draft draft, Rules rules, boolean frozen, Assets assets, String deployment) {
        var issues=new ArrayList<Issue>(); var questions=new ArrayList<Question>();
        if (frozen) issues.add(new Issue("PAPER_FROZEN",null,null,"已有学生开始，请复制为新考核后发布修订"));
        if (draft.questions().isEmpty()) issues.add(new Issue("EMPTY_PAPER",null,null,"尚未添加题目"));
        int total=draft.questions().stream().mapToInt(PreparedPaper.Question::maxScore).sum();
        if (rules.totalScore()==null || rules.totalScore()<1 || rules.totalScore()!=total)
            issues.add(new Issue("TOTAL_SCORE_MISMATCH",null,null,"考核总分必须与题目分值合计一致"));
        if (rules.passScore()==null || rules.passScore()<0 || rules.totalScore()==null || rules.passScore()>rules.totalScore())
            issues.add(new Issue("INVALID_PASS_SCORE",null,null,"通过分数须在 0 到考核总分之间"));
        if (rules.questionCount()==null || rules.questionCount()<1 || rules.questionCount()!=draft.questions().size())
            issues.add(new Issue("QUESTION_COUNT_MISMATCH",null,null,"考核题目数量必须与草稿一致"));
        var ids=new HashSet<String>(); int ordinal=0;
        for(var q:draft.questions()) {
            ordinal++;
            if (!uuid(q.questionId()) || !ids.add(q.questionId()) || q.ordinal()!=ordinal || ordinal>50
                    || q.body()==null || q.body().isBlank() || q.body().length()>10000
                    || q.rubric()==null || q.rubric().isBlank() || q.rubric().length()>20000
                    || q.maxScore()<1 || q.maxScore()>10000 || q.answerSeconds()<1 || q.answerSeconds()>1800) {
                issues.add(issue("INVALID_QUESTION",q,"题目正文、标识、顺序或评分规则无效")); continue;
            }
            if (!q.approved()) issues.add(issue("NOT_APPROVED",q,"请审核此题"));
            if (!"READY".equals(q.audioState()) || q.audioObjectKey()==null) {
                issues.add(issue("AUDIO_NOT_READY",q,"请先生成并保存本题播报")); continue;
            }
            var found=assets.find(draft.assessmentId(),q.questionId(),q.audioObjectKey());
            if (found.isEmpty()) { issues.add(issue("AUDIO_NOT_VERIFIED",q,"本题没有经核验的播报资产")); continue; }
            var a=found.get();
            String expected="prepared/"+deployment+"/assessments/"+draft.assessmentId()+"/questions/"+q.questionId()+"/audio/"+a.id()+".wav";
            if (!uuid(a.id()) || a.assessmentId()!=draft.assessmentId() || !q.questionId().equals(a.questionId())
                    || !PreparedJobInput.digest(q.body()).equals(a.bodySha256()) || !hash(a.configSha256()) || !hash(a.contentSha256())
                    || !expected.equals(a.objectKey()) || !a.objectKey().equals(q.audioObjectKey())
                    || a.byteSize()<45 || a.byteSize()>67108864 || a.durationMs()<1 || a.durationMs()>1800000 || !"audio/wav".equals(a.mediaType())) {
                issues.add(issue("AUDIO_MISMATCH",q,"播报与当前文字、所属题目或存储校验不一致")); continue;
            }
            questions.add(new Question(q.questionId(),q.source(),q.body(),q.ordinal(),q.maxScore(),q.answerSeconds(),q.rubric(),a.descriptor()));
        }
        return new Checked(issues,issues.isEmpty()?new Snapshot(1,draft.assessmentId(),rules,questions):null);
    }
    private static Issue issue(String code, PreparedPaper.Question q, String message) { return new Issue(code,q.questionId(),q.ordinal(),message); }
    private static boolean hash(String value) { return value!=null && value.matches("[a-f0-9]{64}"); }
    private static boolean uuid(String value) {
        try { PreparedJobInput.uuid(value); return true; } catch(IllegalArgumentException error) { return false; }
    }
}
