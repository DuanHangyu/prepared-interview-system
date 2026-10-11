package system.assessment.defense.prepared.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.media.*;
import java.io.IOException;

/** Internal persisted part data. Paid provider URLs and local filesystem paths are not outputs. */
public final class PreparedSpeech {
    private static final ObjectMapper JSON=new ObjectMapper();
    private PreparedSpeech() {}
    public record PartInput(int schemaVersion,String questionId,String assetId,String bodySha256,
                            int partIndex,int partCount,String text,PreparedTtsPolicy.Snapshot policy) {
        @Override public String toString(){return "SpeechPart[questionId="+questionId+",partIndex="+partIndex+",text redacted]";}
        public void validate(){
            PreparedJobInput.uuid(questionId);PreparedJobInput.uuid(assetId);
            if(schemaVersion!=1 || bodySha256==null || !bodySha256.matches("[0-9a-f]{64}") || partCount<1 || partCount>50
                    || partIndex<0 || partIndex>=partCount || policy==null || PreparedSpeechText.split(text).size()!=1)throw invalid();
            PreparedTtsPolicy.validateEndpoint(policy.endpoint());
        }
    }
    public record PartOutput(int schemaVersion,String questionId,String assetId,String objectKey,PreparedWaveAudio.Verified proof) {}
    public static String encode(Object input){
        try{return JSON.writeValueAsString(input);}catch(IOException error){throw invalid();}
    }
    public static PartInput part(String input){
        try{var part=JSON.readValue(PreparedJobInput.json(input,65536),PartInput.class);part.validate();return part;}
        catch(IOException|NullPointerException error){throw invalid();}
    }
    public static PartOutput output(String input){
        try{var output=JSON.readValue(PreparedJobInput.json(input,65536),PartOutput.class);
            PreparedJobInput.uuid(output.questionId());PreparedJobInput.uuid(output.assetId());var p=output.proof();
            if(output.schemaVersion()!=1 || output.objectKey()==null || p==null || p.byteSize()<46 || p.byteSize()>67108864
                    || p.sha256()==null || !p.sha256().matches("[0-9a-f]{64}") || p.durationMs()<1 || p.durationMs()>1800000
                    || p.sampleRate()<8000 || p.sampleRate()>48000 || p.channels()<1 || p.channels()>2
                    || p.dataOffset()<44 || p.dataSize()<2 || p.dataOffset()>p.byteSize()-p.dataSize()
                    || p.dataSize()%(p.channels()*2)!=0)throw invalid();return output;
        }catch(IOException|NullPointerException error){throw invalid();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("播报分段任务数据无效");}
}
