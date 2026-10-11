package system.assessment.defense.prepared.attempt;

import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.publication.PreparedPublication;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Never return this teacher-only snapshot. Its metadata projection is safe before device preflight. */
public final class PreparedStudentPaperVerifier {
    private PreparedStudentPaperVerifier() {}
    public record Metadata(String theme, int questionCount) {}
    public static Metadata verify(int assessmentId, String content, String expectedHash) {
        try {
            var snapshot=PreparedPublication.decode(content);
            require(snapshot.schemaVersion()==1 && snapshot.assessmentId()==assessmentId);
            hash(expectedHash);
            require(PreparedJobInput.digest(PreparedPublication.encode(snapshot)).equals(expectedHash));
            var rules=Objects.requireNonNull(snapshot.rules());
            text(rules.theme(),1000);
            range(rules.questionCount(),1,50);range(rules.totalScore(),1,500000);
            range(rules.passScore(),0,rules.totalScore());
            require(snapshot.questions().size()==rules.questionCount());
            Set<String> identities=new HashSet<>();int total=0, ordinal=1;
            for (var q:snapshot.questions()) {
                Objects.requireNonNull(q);PreparedJobInput.uuid(q.questionId());
                require(identities.add(q.questionId()));require(q.ordinal()==ordinal++);
                require(Set.of("MANUAL","AI").contains(q.source()));
                text(q.body(),10000);text(q.rubric(),20000);
                range(q.maxScore(),1,10000);range(q.answerSeconds(),1,1800);total=Math.addExact(total,q.maxScore());
                var audio=Objects.requireNonNull(q.audio());PreparedJobInput.uuid(audio.assetId());
                hash(audio.bodySha256());hash(audio.configSha256());hash(audio.contentSha256());
                require(PreparedJobInput.digest(q.body()).equals(audio.bodySha256()));
                require(audio.byteSize()>=45 && audio.byteSize()<=67108864);
                require(audio.durationMs()>=1 && audio.durationMs()<=1800000);
                require("audio/wav".equals(audio.mediaType()));
            }
            require(total==rules.totalScore());
            return new Metadata(rules.theme(),rules.questionCount());
        } catch (RuntimeException invalid) {
            // No source text, parser detail, private teacher rubric or storage information in the failure.
            throw new IllegalStateException("试卷快照完整性验证失败");
        }
    }
    private static void text(String value,int maximum) { require(value!=null && !value.isBlank() && value.length()<=maximum); }
    private static void range(Integer value,int minimum,int maximum) { require(value!=null && value>=minimum && value<=maximum); }
    private static void hash(String value) { require(value!=null && value.matches("[a-f0-9]{64}")); }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("快照无效"); }
}
