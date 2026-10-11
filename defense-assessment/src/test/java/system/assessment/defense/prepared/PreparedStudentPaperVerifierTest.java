package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import java.util.stream.Stream;
import system.assessment.defense.prepared.attempt.PreparedStudentPaperVerifier;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.publication.PreparedPublication;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PreparedStudentPaperVerifierTest {
    final String qid=UUID.randomUUID().toString(), asset=UUID.randomUUID().toString();
    PreparedPublication.Question question(int ordinal,int seconds) {
        return new PreparedPublication.Question(qid,"MANUAL","学生题目正文",ordinal,10,seconds,"不得返回的教师标准",
                new PreparedPublication.Audio(asset,PreparedJobInput.digest("学生题目正文"),"a".repeat(64),"b".repeat(64),48044,1000,"audio/wav"));
    }
    PreparedPublication.Snapshot snapshot(int schema,int assessment,List<PreparedPublication.Question> questions) {
        return new PreparedPublication.Snapshot(schema,assessment,new PreparedPublication.Rules("预检主题",10,6,1,false),questions);
    }
    PreparedStudentPaperVerifier.Metadata verify(PreparedPublication.Snapshot snapshot) {
        String body=PreparedPublication.encode(snapshot);
        return PreparedStudentPaperVerifier.verify(12,body,PreparedJobInput.digest(body));
    }
    @Test void onlySafeMetadataIsProjectedFromVerifiedImmutableSnapshot() throws Exception {
        var result=verify(snapshot(1,12,List.of(question(1,120))));
        assertThat(result.theme()).isEqualTo("预检主题");assertThat(result.questionCount()).isEqualTo(1);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result))
                .doesNotContain(qid,asset,"学生题目正文","教师标准","maxScore","passScore","showResult","rubric");
    }
    @Test void jsonStorageFormattingDoesNotChangeCanonicalContentHash() {
        var input=snapshot(1,12,List.of(question(1,120)));String canonical=PreparedPublication.encode(input);
        assertThat(PreparedStudentPaperVerifier.verify(12,canonical.replace(":",": "),PreparedJobInput.digest(canonical)).questionCount()).isEqualTo(1);
    }
    @Test void incorrectHashDifferentAssessmentAndUnsupportedSchemaFailClosed() {
        String body=PreparedPublication.encode(snapshot(1,12,List.of(question(1,120))));
        assertThatThrownBy(()->PreparedStudentPaperVerifier.verify(12,body,"c".repeat(64))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->verify(snapshot(1,13,List.of(question(1,120))))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->verify(snapshot(2,12,List.of(question(1,120))))).isInstanceOf(IllegalStateException.class);
    }
    @Test void emptyAndCountMismatchCannotPretendPublicationIsReady() {
        assertThatThrownBy(()->verify(snapshot(1,12,List.of()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->verify(snapshot(1,12,List.of(question(1,120),question(2,120))))).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(ints={0,-1,1801,Integer.MAX_VALUE})
    void invalidTimerIsRejectedEvenWithMatchingHash(int seconds) {
        assertThatThrownBy(()->verify(snapshot(1,12,List.of(question(1,seconds))))).isInstanceOf(IllegalStateException.class);
    }
    @Test void nonContiguousOrderDuplicateIdentityOrMissingBroadcastFailsClosed() {
        assertThatThrownBy(()->verify(snapshot(1,12,List.of(question(2,120))))).isInstanceOf(IllegalStateException.class);
        var duplicate=new PreparedPublication.Snapshot(1,12,new PreparedPublication.Rules("预检",20,12,2,false),List.of(question(1,120),question(2,120)));
        assertThatThrownBy(()->verify(duplicate)).isInstanceOf(IllegalStateException.class);
        var missing=new PreparedPublication.Question(qid,"MANUAL","正文",1,10,120,"标准",null);
        assertThatThrownBy(()->verify(snapshot(1,12,List.of(missing)))).isInstanceOf(IllegalStateException.class);
    }
    @Test void malformedNullOrOversizedJsonNeverLeaksParserDetails() {
        for(String value:new String[]{null,"[]","{","{\"schemaVersion\":1}","x".repeat(6*1024*1024+1)})
            assertThatThrownBy(()->PreparedStudentPaperVerifier.verify(12,value,"a".repeat(64)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("试卷快照完整性验证失败");
    }
    static Stream<Arguments> invalidChanges() {
        var cases=new ArrayList<Arguments>();
        for(String field:List.of("theme","questionCount","totalScore","passScore")) {
            Consumer<ObjectNode> change=n->((ObjectNode)n.path("rules")).putNull(field);
            cases.add(Arguments.of("规则空值："+field,change));
        }
        for(String field:List.of("body","rubric","questionId","source")) {
            Consumer<ObjectNode> change=n->((ObjectNode)n.path("questions").get(0)).putNull(field);
            cases.add(Arguments.of("题目空值："+field,change));
        }
        for(String field:List.of("assetId","bodySha256","configSha256","contentSha256","mediaType")) {
            Consumer<ObjectNode> change=n->((ObjectNode)n.path("questions").get(0).path("audio")).putNull(field);
            cases.add(Arguments.of("音频空值："+field,change));
        }
        for(var entry:Map.of("questionCount",0,"totalScore",0,"passScore",-1).entrySet()) {
            Consumer<ObjectNode> change=n->((ObjectNode)n.path("rules")).put(entry.getKey(),entry.getValue());
            cases.add(Arguments.of("规则下界："+entry.getKey(),change));
        }
        for(var entry:Map.of("questionCount",51,"totalScore",500001,"passScore",11).entrySet()) {
            Consumer<ObjectNode> change=n->((ObjectNode)n.path("rules")).put(entry.getKey(),entry.getValue());
            cases.add(Arguments.of("规则上界："+entry.getKey(),change));
        }
        for(var entry:Map.of("body"," ","rubric"," ","questionId","invalid","source","UNKNOWN").entrySet()) {
            Consumer<ObjectNode> change=n->((ObjectNode)n.path("questions").get(0)).put(entry.getKey(),entry.getValue());
            cases.add(Arguments.of("题目非法值："+entry.getKey(),change));
        }
        for(var entry:Map.of("maxScore",0,"ordinal",0).entrySet()) {
            Consumer<ObjectNode> change=n->((ObjectNode)n.path("questions").get(0)).put(entry.getKey(),entry.getValue());
            cases.add(Arguments.of("题目非法数值："+entry.getKey(),change));
        }
        for(var entry:Map.of("bodySha256","c".repeat(64),"configSha256","INVALID","contentSha256","INVALID","assetId","invalid","mediaType","audio/mp3").entrySet()) {
            Consumer<ObjectNode> change=n->((ObjectNode)n.path("questions").get(0).path("audio")).put(entry.getKey(),entry.getValue());
            cases.add(Arguments.of("音频非法值："+entry.getKey(),change));
        }
        for(long bytes:new long[]{44,67108865}) {
            Consumer<ObjectNode> change=n->((ObjectNode)n.path("questions").get(0).path("audio")).put("byteSize",bytes);
            cases.add(Arguments.of("音频字节边界："+bytes,change));
        }
        for(long duration:new long[]{0,1800001}) {
            Consumer<ObjectNode> change=n->((ObjectNode)n.path("questions").get(0).path("audio")).put("durationMs",duration);
            cases.add(Arguments.of("音频时长边界："+duration,change));
        }
        Consumer<ObjectNode> longBody=n->((ObjectNode)n.path("questions").get(0)).put("body","字".repeat(10001));
        Consumer<ObjectNode> longRubric=n->((ObjectNode)n.path("questions").get(0)).put("rubric","字".repeat(20001));
        Consumer<ObjectNode> longTheme=n->((ObjectNode)n.path("rules")).put("theme","字".repeat(1001));
        Consumer<ObjectNode> emptyTheme=n->((ObjectNode)n.path("rules")).put("theme"," ");
        Consumer<ObjectNode> badScore=n->((ObjectNode)n.path("questions").get(0)).put("maxScore",10001);
        Consumer<ObjectNode> mismatchedTotal=n->((ObjectNode)n.path("rules")).put("totalScore",9);
        Consumer<ObjectNode> nullRules=n->n.putNull("rules");
        Consumer<ObjectNode> nullQuestion=n->((com.fasterxml.jackson.databind.node.ArrayNode)n.path("questions")).set(0,com.fasterxml.jackson.databind.node.NullNode.instance);
        cases.addAll(List.of(Arguments.of("超长正文",longBody),Arguments.of("超长评分标准",longRubric),Arguments.of("超长主题",longTheme),
                Arguments.of("空主题",emptyTheme),Arguments.of("题目分值上界",badScore),Arguments.of("分值合计不一致",mismatchedTotal),
                Arguments.of("无规则",nullRules),Arguments.of("无题目实体",nullQuestion)));
        return cases.stream();
    }
    @ParameterizedTest(name="{0}") @MethodSource("invalidChanges")
    void invalidStructureFailsEvenIfSomeoneRecomputedItsHash(String name,Consumer<ObjectNode> change) throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var node=(ObjectNode)json.readTree(PreparedPublication.encode(snapshot(1,12,List.of(question(1,120)))));
        change.accept(node);String content=PreparedJobInput.json(node.toString(),6*1024*1024);
        assertThatThrownBy(()->PreparedStudentPaperVerifier.verify(12,content,PreparedJobInput.digest(content)))
                .isInstanceOf(IllegalStateException.class).hasMessage("试卷快照完整性验证失败");
    }
    @Test void absentOrInvalidExpectedHashIsNeverAccepted() {
        String body=PreparedPublication.encode(snapshot(1,12,List.of(question(1,120))));
        for(String hash:new String[]{null,"","NOT-A-HASH"})
            assertThatThrownBy(()->PreparedStudentPaperVerifier.verify(12,body,hash)).isInstanceOf(IllegalStateException.class);
    }
    @Test void legitimateMaximumScoringAndAudioBoundsRemainCompatible() {
        var q=question(1,1800);
        var max=new PreparedPublication.Question(q.questionId(),"AI",q.body(),1,10000,1800,q.rubric(),
                new PreparedPublication.Audio(q.audio().assetId(),q.audio().bodySha256(),q.audio().configSha256(),q.audio().contentSha256(),67108864,1800000,"audio/wav"));
        assertThat(verify(new PreparedPublication.Snapshot(1,12,new PreparedPublication.Rules("边界",10000,0,1,false),List.of(max))).questionCount()).isEqualTo(1);
    }
}
