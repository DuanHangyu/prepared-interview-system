package system.assessment.defense.prepared.speech;

import com.fasterxml.jackson.databind.*;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.media.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Separate bounded HTTPS sends. Audio downloads never carry the model API key. */
@Component
public class PreparedTtsHttpClient implements PreparedTtsClient {
    private static final int RESPONSE_LIMIT=524288;
    private static final long AUDIO_LIMIT=67108864;
    private final OkHttpClient http,downloads;private final Clock clock;private final ObjectMapper json=new ObjectMapper();
    @Autowired public PreparedTtsHttpClient(){this(new OkHttpClient(),Clock.systemUTC());}
    public PreparedTtsHttpClient(OkHttpClient base,Clock clock){
        http=base.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(5,TimeUnit.SECONDS).readTimeout(40,TimeUnit.SECONDS).writeTimeout(10,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build();
        downloads=http.newBuilder().readTimeout(30,TimeUnit.SECONDS).callTimeout(75,TimeUnit.SECONDS).build();this.clock=clock;
    }
    public OkHttpClient http(){return http;}
    @Override public Reply synthesize(PreparedTtsPolicy.Snapshot policy,String text,String key) throws IOException {
        PreparedTtsPolicy.validateEndpoint(policy.endpoint());
        if(PreparedSpeechText.split(text).size()!=1 || text.length()>600)throw new IllegalArgumentException("单次播报文字超过限制");
        if(key==null || key.isBlank() || key.length()>512 || key.chars().anyMatch(Character::isWhitespace))throw new IllegalArgumentException("播报凭据无效");
        String payload=json.writeValueAsString(Map.of("model",policy.model(),"input",Map.of("text",text,"voice",policy.voice(),"language_type",policy.language())));
        var request=new Request.Builder().url(policy.endpoint()).header("Authorization","Bearer "+key)
                .post(RequestBody.create(payload,MediaType.get("application/json; charset=utf-8"))).build();
        try(var response=http.newCall(request).execute()) {
            if(!response.isSuccessful() || response.body()==null)throw unknown();
            byte[] bytes=response.body().byteStream().readNBytes(RESPONSE_LIMIT+1);if(bytes.length>RESPONSE_LIMIT)throw unknown();
            JsonNode root=json.readTree(PreparedJobInput.json(new String(bytes,StandardCharsets.UTF_8),RESPONSE_LIMIT));
            JsonNode id=root.path("request_id"),usage=root.path("usage").path("characters");
            if(!id.isTextual() || !id.textValue().matches("[A-Za-z0-9_.:-]{1,128}") || !usage.isIntegralNumber()
                    || !usage.canConvertToLong() || usage.longValue()<0 || usage.longValue()>100000000)throw unknown();
            var output=root.path("output");JsonNode finish=output.path("finish_reason"),audio=output.path("audio");
            URI uri=null;long expires=0;
            try {
                if(audio.path("url").isTextual() && audio.path("expires_at").isIntegralNumber() && audio.path("expires_at").canConvertToLong()) {
                    expires=audio.path("expires_at").longValue();long now=clock.instant().getEpochSecond();
                    if(expires>now && expires<=now+90000)uri=PreparedTtsUrls.https(audio.path("url").textValue());
                }
            }catch(IllegalArgumentException ignored) { /* Known charge is returned even if content is invalid. */ }
            return new Reply(id.textValue(),usage.longValue(),finish.isTextual()?finish.textValue():"invalid",uri,expires);
        }catch(IOException|IllegalArgumentException error){throw unknown();}
    }
    /** Creates only a new worker-owned staged file. Failed/invalid new artifacts are removed;
     * existing files are never overwritten or deleted. Caller persists the resulting proof. */
    public PreparedWaveAudio.Verified download(URI providerUri,Path target) throws IOException {
        URI uri=PreparedTtsUrls.https(providerUri==null?null:providerUri.toString());
        if(target==null || !target.isAbsolute() || !target.normalize().equals(target)
                || Files.exists(target,LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(target.getParent(),LinkOption.NOFOLLOW_LINKS))throw downloadFailure();
        boolean created=false;
        try(var response=downloads.newCall(new Request.Builder().url(uri.toString()).header("Accept","audio/wav").header("Accept-Encoding","identity").get().build()).execute()) {
            if(response.code()!=200 || response.body()==null || response.body().contentLength()>AUDIO_LIMIT)throw downloadFailure();
            try(var out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                var in=response.body().byteStream()) {
                created=true;byte[] buffer=new byte[8192];long count=0;int read;
                while((read=in.read(buffer))!=-1) {count+=read;if(count>AUDIO_LIMIT)throw downloadFailure();out.write(buffer,0,read);}
                if(response.body().contentLength()>=0 && count!=response.body().contentLength())throw downloadFailure();
            }
            return PreparedWaveAudio.inspect(target);
        }catch(IOException|IllegalArgumentException error){
            if(created) {try {Files.deleteIfExists(target);}catch(IOException ignored) { /* A later controlled spool cleanup may retry. */ }}
            throw downloadFailure();
        }
    }
    private static IOException unknown(){return new IOException("播报响应无法安全确认计费或结果");}
    private static IOException downloadFailure(){return new IOException("播报下载或完整文件校验失败，未确认保存");}
}
