package system.assessment.defense.prepared.processing;

import system.assessment.defense.prepared.generation.PreparedGenerationPolicy;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import java.util.List;
import static system.assessment.defense.prepared.processing.PreparedProcessingCodec.*;

public interface PreparedScoreClient {
    /** Only the frozen question/rubric and exact source sentences, not private media URLs or identities. */
    record Input(int schemaVersion,String questionId,String body,String rubric,int maxScore,String transcriptId,String transcriptSha256,List<PreparedProcessing.Sentence> sentences){
        public void validate(){
            if(schemaVersion!=1||maxScore<1||maxScore>10000||sentences==null||sentences.isEmpty()||sentences.size()>10000)throw new IllegalArgumentException("评分输入不完整");
            PreparedJobInput.uuid(questionId);PreparedJobInput.uuid(transcriptId);sha(transcriptSha256);text(body,10000);text(rubric,20000);
            long last=0;int length=0;for(var s:sentences){if(s==null||s.startMs()<last||s.endMs()<=s.startMs()||s.endMs()>1800000)throw new IllegalArgumentException("转写句子不完整");text(s.text(),10000);length+=s.text().length();last=s.endMs();}
            if(length>60000)throw new IllegalArgumentException("原转写超限；不能截断评分");PreparedScoreClient.encode(this);
        }
    }
    record Reply(String providerRequestId,long inputTokens,long outputTokens,String content,String finishReason){}
    static String encode(Object value){return encodeInput(value);}
    static <T>T decode(String json,Class<T> type){return decodeInput(json,type);}
    Reply score(PreparedGenerationPolicy.Snapshot policy,Input input,String apiKey)throws java.io.IOException;
}
