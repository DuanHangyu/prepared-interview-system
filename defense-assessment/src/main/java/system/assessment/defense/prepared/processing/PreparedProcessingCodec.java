package system.assessment.defense.prepared.processing;

import com.fasterxml.jackson.databind.*;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.publication.PreparedPublication;
import static system.assessment.defense.prepared.processing.PreparedProcessing.*;

/** Strict result boundary; never execute model content or treat empty ASR as an answer. */
public final class PreparedProcessingCodec {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private PreparedProcessingCodec() {}
    public static Transcript transcript(String content,String audioSha256,long durationMs){
        var result=decode(content,Transcript.class);sha(audioSha256);
        if(result.schemaVersion()!=1||!audioSha256.equals(result.audioSha256())||durationMs<1||durationMs>1800000||result.durationMs()!=durationMs
            ||result.sentences()==null||result.sentences().isEmpty()||result.sentences().size()>10000)throw invalid();
        identifier(result.provider());identifier(result.model());long last=0;int characters=0;
        for(var sentence:result.sentences()){
            if(sentence==null||sentence.startMs()<last||sentence.endMs()<=sentence.startMs()||sentence.endMs()>durationMs)throw invalid();
            text(sentence.text(),10000);characters+=sentence.text().length();if(characters>60000)throw invalid();last=sentence.endMs();
        }
        return result;
    }
    public static Score score(String content,PreparedPublication.Question question,Result source,Transcript transcript){
        var result=decode(content,Score.class);PreparedJobInput.uuid(result.questionId());PreparedJobInput.uuid(result.transcriptId());sha(result.transcriptSha256());
        if(question==null||source==null||transcript==null||result.schemaVersion()!=1||!question.questionId().equals(result.questionId())
            ||!"TRANSCRIBE".equals(source.kind())||!source.id().equals(result.transcriptId())||!source.contentSha256().equals(result.transcriptSha256())
            ||!PreparedJobInput.digest(encode(transcript)).equals(source.contentSha256())||!encode(transcript).equals(source.content())
            ||result.maxScore()!=question.maxScore()||result.score()==null||result.score().signum()<0||result.score().compareTo(java.math.BigDecimal.valueOf(question.maxScore()))>0
            ||result.score().stripTrailingZeros().scale()>2||result.evidence()==null||result.evidence().isEmpty()||result.evidence().size()>100)throw invalid();
        text(result.feedback(),4000);
        for(var evidence:result.evidence()){
            if(evidence==null||evidence.sentenceOrdinal()<1||evidence.sentenceOrdinal()>transcript.sentences().size())throw invalid();
            text(evidence.quote(),1000);if(!transcript.sentences().get(evidence.sentenceOrdinal()-1).text().contains(evidence.quote()))throw invalid();
        }
        return result;
    }
    public static String encode(Object value){return encode(value,262144);}
    public static <T>T decode(String content,Class<T> type){return decode(content,type,262144);}
    /** Frozen provenance embeds escaped original JSON plus the full rubric; its envelope can be
     * larger than a normalized result. This does not relax any transcript/score content limits. */
    public static String encodeInput(Object value){return encode(value,524288);}
    public static <T>T decodeInput(String content,Class<T> type){return decode(content,type,524288);}
    private static String encode(Object value,int maximum){try{return PreparedJobInput.json(JSON.writeValueAsString(value),maximum);}catch(java.io.IOException failure){throw invalid();}}
    private static <T>T decode(String content,Class<T> type,int maximum){try{return JSON.readValue(PreparedJobInput.json(content,maximum),type);}catch(java.io.IOException failure){throw invalid();}}
    public static String identifier(String value){if(value==null||!value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}"))throw invalid();return value;}
    public static String sha(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw invalid();return value;}
    public static String text(String value,int limit){
        if(value==null||value.isBlank()||value.length()>limit)throw invalid();
        for(int n=0;n<value.length();n++){
            char c=value.charAt(n);if(Character.isHighSurrogate(c)){if(++n>=value.length()||!Character.isLowSurrogate(value.charAt(n)))throw invalid();}
            else if(Character.isLowSurrogate(c)||Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t')throw invalid();
        }return value;
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("后台结果格式、输入绑定或内容边界无法核验；保留原录音供处理");}
}
