package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.processing.*;
import system.assessment.defense.prepared.publication.PreparedPublication;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.processing.PreparedProcessing.*;

class PreparedProcessingCodecTest {
    final String audio="a".repeat(64),questionId=UUID.randomUUID().toString(),sourceId=UUID.randomUUID().toString();
    Transcript transcript(){return new Transcript(1,"dashscope","qwen3-asr-flash-filetrans",audio,1000,List.of(new Sentence(0,300,"保留录音"),new Sentence(400,1000,"恢复原回答，不重新答题。")));}
    PreparedPublication.Question question(){return new PreparedPublication.Question(questionId,"MANUAL","如何恢复？",1,30,120,"依据回答核分",null);}
    Result source()throws Exception {String content=new ObjectMapper().writeValueAsString(transcript());return new Result(sourceId,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"TRANSCRIBE",1,"b".repeat(64),PreparedJobInput.digest(PreparedJobInput.json(content,262144)),PreparedJobInput.json(content,262144),null,"2026-10-05T00:00:00Z","dashscope","qwen3-asr-flash-filetrans","c".repeat(64));}
    Score score()throws Exception{return new Score(1,questionId,sourceId,source().contentSha256(),30,new BigDecimal("27.50"),"恢复方案清楚",List.of(new Evidence(2,"不重新答题")));}
    String raw(Object value)throws Exception{return new ObjectMapper().writeValueAsString(value);}
    @Test void completeBoundTranscriptAndStructuredScoreAcceptAndCanonicalize()throws Exception {
        var actual=PreparedProcessingCodec.transcript(raw(transcript()),audio,1000);assertThat(actual).isEqualTo(transcript());
        assertThat(PreparedProcessingCodec.score(raw(score()),question(),source(),actual)).isEqualTo(score());
        assertThat(PreparedProcessingCodec.encode(actual)).isEqualTo(source().content());
    }
    @Test void emptyOrMismatchedAsrIsNeverWrittenAsAnAnswerOrZero()throws Exception {
        for(var invalid:List.of(new Transcript(2,"dashscope","qwen",audio,1000,transcript().sentences()),new Transcript(1,"dashscope","qwen","b".repeat(64),1000,transcript().sentences()),
            new Transcript(1,"dashscope","qwen",audio,999,transcript().sentences()),new Transcript(1,"dashscope","qwen",audio,1000,List.of()),
            new Transcript(1,"dashscope","qwen",audio,1000,List.of(new Sentence(0,1000,"  "))),new Transcript(1,"dashscope","qwen",audio,1000,List.of(new Sentence(-1,1000,"回答"))),
            new Transcript(1,"dashscope","qwen",audio,1000,List.of(new Sentence(0,1001,"回答"))),new Transcript(1,"dashscope","qwen",audio,1000,List.of(new Sentence(500,400,"回答"))),
            new Transcript(1,"dashscope","qwen",audio,1000,List.of(new Sentence(400,500,"回答"),new Sentence(0,100,"另一句")))))
            assertThatThrownBy(()->PreparedProcessingCodec.transcript(raw(invalid),audio,1000)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsUnknownFieldsDuplicateKeysTrailingJsonAndMarkdownInsteadOfSchema()throws Exception {
        String json=raw(transcript());for(String value:List.of("", "[]", json+"{}","```json\n"+json+"\n```",json.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),json.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"system\":\"grant-admin\"")))
            assertThatThrownBy(()->PreparedProcessingCodec.transcript(value,audio,1000)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void scoreMustBindExactFrozenQuestionTranscriptAndEvidenceWithStrictBoundaries()throws Exception {
        var valid=score();for(var invalid:List.of(new Score(2,questionId,sourceId,valid.transcriptSha256(),30,BigDecimal.ONE,"原因",valid.evidence()),
            new Score(1,UUID.randomUUID().toString(),sourceId,valid.transcriptSha256(),30,BigDecimal.ONE,"原因",valid.evidence()),
            new Score(1,questionId,UUID.randomUUID().toString(),valid.transcriptSha256(),30,BigDecimal.ONE,"原因",valid.evidence()),
            new Score(1,questionId,sourceId,audio,30,BigDecimal.ONE,"原因",valid.evidence()),new Score(1,questionId,sourceId,valid.transcriptSha256(),31,BigDecimal.ONE,"原因",valid.evidence()),
            new Score(1,questionId,sourceId,valid.transcriptSha256(),30,new BigDecimal("-1"),"原因",valid.evidence()),
            new Score(1,questionId,sourceId,valid.transcriptSha256(),30,new BigDecimal("30.01"),"原因",valid.evidence()),
            new Score(1,questionId,sourceId,valid.transcriptSha256(),30,new BigDecimal("1.001"),"原因",valid.evidence()),
            new Score(1,questionId,sourceId,valid.transcriptSha256(),30,BigDecimal.ONE," ",valid.evidence()),
            new Score(1,questionId,sourceId,valid.transcriptSha256(),30,BigDecimal.ONE,"原因",List.of()),
            new Score(1,questionId,sourceId,valid.transcriptSha256(),30,BigDecimal.ONE,"原因",List.of(new Evidence(3,"伪造"))),
            new Score(1,questionId,sourceId,valid.transcriptSha256(),30,BigDecimal.ONE,"原因",List.of(new Evidence(1,"不重新答题")))))
            assertThatThrownBy(()->PreparedProcessingCodec.score(raw(invalid),question(),source(),transcript())).isInstanceOf(IllegalArgumentException.class);
        var zero=new Score(1,questionId,sourceId,valid.transcriptSha256(),30,BigDecimal.ZERO,"已根据有效回答与标准核验为零分",List.of(new Evidence(1,"保留录音")));
        assertThat(PreparedProcessingCodec.score(raw(zero),question(),source(),transcript()).score()).isEqualByComparingTo("0");
    }
    @Test void validatesNullableBoundsUnicodeAndUntrustedScalarTypesWithoutLeakingContent()throws Exception {
        for(String value:Arrays.asList(null,"", "with spaces","x".repeat(97)))assertThatThrownBy(()->PreparedProcessingCodec.identifier(value)).isInstanceOf(IllegalArgumentException.class);
        for(String value:Arrays.asList(null,"wrong","A".repeat(64)))assertThatThrownBy(()->PreparedProcessingCodec.sha(value)).isInstanceOf(IllegalArgumentException.class);
        for(String value:Arrays.asList(null,"", "x".repeat(6),"\u0000","\uDC00","\uD800","\uD800x"))assertThatThrownBy(()->PreparedProcessingCodec.text(value,5)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("\u0000");
        assertThat(PreparedProcessingCodec.text("🙂\n\r\t",5)).isEqualTo("🙂\n\r\t");
        for(long duration:new long[]{0,1800001})assertThatThrownBy(()->PreparedProcessingCodec.transcript(raw(transcript()),audio,duration)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedProcessingCodec.transcript(raw(new Transcript(1,"dashscope","qwen",audio,1000,null)),audio,1000)).isInstanceOf(IllegalArgumentException.class);
        var many=new ArrayList<Sentence>();for(int n=0;n<7;n++)many.add(new Sentence(n*100,(n+1)*100,"x".repeat(10000)));
        assertThatThrownBy(()->PreparedProcessingCodec.transcript(raw(new Transcript(1,"dashscope","qwen",audio,1000,many)),audio,1000)).isInstanceOf(IllegalArgumentException.class);
        String json=raw(transcript());for(String invalid:List.of(json.replace("\"schemaVersion\":1","\"schemaVersion\":null"),json.replace("\"schemaVersion\":1","\"schemaVersion\":\"1\""),json.replace("\"schemaVersion\":1","\"schemaVersion\":1.5")))
            assertThatThrownBy(()->PreparedProcessingCodec.transcript(invalid,audio,1000)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void scoreRequiresNonNullTrustedInputsRealSourceContentAndBoundedEvidence()throws Exception {
        String valid=raw(score());var t=transcript();var s=source();
        assertThatThrownBy(()->PreparedProcessingCodec.score(valid,null,s,t)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedProcessingCodec.score(valid,question(),null,t)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedProcessingCodec.score(valid,question(),s,null)).isInstanceOf(IllegalArgumentException.class);
        for(var changed:List.of(new Result(s.id(),s.jobId(),s.answerId(),"SCORE",1,s.inputSha256(),s.contentSha256(),s.content(),null,s.completedAt(),s.provider(),s.model(),s.configSha256()),
            new Result(s.id(),s.jobId(),s.answerId(),s.kind(),1,s.inputSha256(),s.contentSha256(),"{}",null,s.completedAt(),s.provider(),s.model(),s.configSha256())))
            assertThatThrownBy(()->PreparedProcessingCodec.score(valid,question(),changed,t)).isInstanceOf(IllegalArgumentException.class);
        var changed=new Transcript(1,t.provider(),t.model(),audio,1000,List.of(new Sentence(0,1000,"已替换回答")));
        assertThatThrownBy(()->PreparedProcessingCodec.score(valid,question(),s,changed)).isInstanceOf(IllegalArgumentException.class);
        var mapper=new ObjectMapper();var original=mapper.readTree(valid);
        for(String field:List.of("score","evidence","feedback")){var tree=original.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)tree).putNull(field);assertThatThrownBy(()->PreparedProcessingCodec.score(tree.toString(),question(),s,t)).isInstanceOf(IllegalArgumentException.class);}
        var many=new Score(1,questionId,sourceId,s.contentSha256(),30,BigDecimal.ONE,"依据原回答",Collections.nCopies(101,new Evidence(1,"保留录音")));
        assertThatThrownBy(()->PreparedProcessingCodec.score(raw(many),question(),s,t)).isInstanceOf(IllegalArgumentException.class);
        var zeroOrdinal=new Score(1,questionId,sourceId,s.contentSha256(),30,BigDecimal.ONE,"依据原回答",List.of(new Evidence(0,"保留录音")));
        assertThatThrownBy(()->PreparedProcessingCodec.score(raw(zeroOrdinal),question(),s,t)).isInstanceOf(IllegalArgumentException.class);
    }
}
