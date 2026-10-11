package system.assessment.defense.prepared.processing;

import com.fasterxml.jackson.databind.*;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static system.assessment.defense.prepared.processing.PreparedProcessingCodec.*;

/** Single bounded POST, repeatable GET. No redirects, transport replay or credentials on downloads. */
@Component
public class PreparedAsrHttpClient implements PreparedAsrClient {
    private static final int LIMIT=524288;
    private final OkHttpClient http;private final ObjectMapper json=new ObjectMapper();
    @Autowired public PreparedAsrHttpClient(){this(new OkHttpClient());}
    public PreparedAsrHttpClient(OkHttpClient base){http=base.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(5,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(10,TimeUnit.SECONDS).callTimeout(40,TimeUnit.SECONDS).build();}
    public OkHttpClient http(){return http;}
    public String submit(PreparedAsrPolicy.Snapshot policy,PreparedPrivateOssStore.Preview original,PreparedPrivateOssStore.StoredAnswer media,String key)throws IOException {
        PreparedAsrPolicy.validateEndpoint(policy.endpoint());credential(key);var uri=PreparedAsrUrls.input(original==null?null:original.url(),media);policy.maximumCost(media.proof());
        var body=Map.of("model",policy.model(),"input",Map.of("file_url",uri.toString()),"parameters",Map.of("channel_id",List.of(0),"enable_itn",false,"enable_words",false));
        var request=new Request.Builder().url(policy.endpoint()).header("Authorization","Bearer "+key).header("X-DashScope-Async","enable")
            .post(RequestBody.create(encode(body),MediaType.get("application/json; charset=utf-8"))).build();
        try{var output=read(request).path("output");String id=PreparedJobInput.uuid(output.path("task_id").textValue());
            if(!Set.of("PENDING","RUNNING","SUCCEEDED","FAILED").contains(output.path("task_status").asText()))throw failure();return id;
        }catch(RuntimeException e){throw failure();}
    }
    public Poll poll(PreparedAsrPolicy.Snapshot policy,String task,String key)throws IOException {
        PreparedAsrPolicy.validateEndpoint(policy.endpoint());PreparedJobInput.uuid(task);credential(key);
        URI origin=URI.create(policy.endpoint());String url=origin.getScheme()+"://"+origin.getHost()+"/api/v1/tasks/"+task;
        try{var root=read(new Request.Builder().url(url).header("Authorization","Bearer "+key).get().build());var output=root.path("output");
            if(!task.equals(output.path("task_id").textValue()))throw failure();String status=output.path("task_status").asText();
            if(!Set.of("PENDING","RUNNING","SUCCEEDED","FAILED","UNKNOWN").contains(status))throw failure();
            Long seconds=null;URI result=null;var usage=root.path("usage").path("seconds");
            if(!usage.isMissingNode()){if(!usage.isIntegralNumber()||!usage.canConvertToLong()||usage.longValue()<0||usage.longValue()>100000000)throw failure();seconds=usage.longValue();}
            if("SUCCEEDED".equals(status)&&seconds==null)throw failure();
            try{result=PreparedAsrUrls.result(output.path("result").path("transcription_url").textValue());}catch(IllegalArgumentException ignored){/* Known fees survive malformed content. */}
            return new Poll(task,status,seconds,result);
        }catch(RuntimeException e){throw failure();}
    }
    public PreparedProcessing.Transcript download(URI result,PreparedAsrPolicy.Snapshot policy,PreparedPrivateOssStore.StoredAnswer media,String inputHash)throws IOException {
        URI safe=PreparedAsrUrls.result(result==null?null:result.toString());
        try{sha(inputHash);var root=read(new Request.Builder().url(safe.toString()).header("Accept","application/json").header("Accept-Encoding","identity").get().build());
            if(!root.path("file_url").isTextual()||!PreparedJobInput.digest(root.path("file_url").textValue()).equals(inputHash))throw failure();
            JsonNode info=root.path("audio_info"),tracks=root.path("transcripts");
            if(!"wav".equals(info.path("format").textValue())||integer(info.path("sample_rate"))!=media.proof().sampleRate()||!tracks.isArray()||tracks.size()!=1)throw failure();
            JsonNode track=tracks.get(0),sentences=track.path("sentences");if(integer(track.path("channel_id"))!=0||!sentences.isArray()||sentences.isEmpty()||sentences.size()>10000)throw failure();
            var normalized=new ArrayList<PreparedProcessing.Sentence>();StringBuilder aggregate=new StringBuilder();int index=0;
            for(var s:sentences){if(integer(s.path("sentence_id"))!=index++)throw failure();String sentence=text(s.path("text").textValue(),10000);aggregate.append(sentence);
                if(aggregate.length()>60000)throw failure();normalized.add(new PreparedProcessing.Sentence(integer(s.path("begin_time")),integer(s.path("end_time")),sentence));}
            String full=text(track.path("text").textValue(),60000);
            if(!full.replaceAll("\\s","").equals(aggregate.toString().replaceAll("\\s","")))throw failure();
            var value=new PreparedProcessing.Transcript(1,"dashscope",policy.model(),media.proof().sha256(),media.proof().durationMs(),normalized);
            return transcript(encode(value),media.proof().sha256(),media.proof().durationMs());
        }catch(RuntimeException e){throw failure();}
    }
    private JsonNode read(Request request)throws IOException {
        try(var response=http.newCall(request).execute()){
            if(response.code()!=200||response.body()==null||response.body().contentLength()>LIMIT)throw failure();
            byte[] bytes=response.body().byteStream().readNBytes(LIMIT+1);if(bytes.length>LIMIT)throw failure();
            // Duplicate fields/trailing tokens cannot override original scope or billing evidence.
            return json.readTree(PreparedJobInput.json(new String(bytes,StandardCharsets.UTF_8),LIMIT));
        }catch(IOException|RuntimeException e){throw failure();}
    }
    private static long integer(JsonNode n)throws IOException {if(!n.isIntegralNumber()||!n.canConvertToLong())throw failure();return n.longValue();}
    private static void credential(String key){if(key==null||key.isBlank()||key.length()>512||key.chars().anyMatch(Character::isWhitespace))throw new IllegalArgumentException("文件转写凭据无效");}
    private static IOException failure(){return new IOException("文件转写响应或原录音结果无法安全确认");}
}
