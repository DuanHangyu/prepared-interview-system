package system.assessment.defense.prepared;

import okhttp3.*;
import okio.Buffer;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import system.assessment.defense.prepared.processing.*;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.media.PreparedWaveAudio;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.time.*;
import java.net.URI;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit synthetic vendor HTTP responses, never a paid integration test. */
class PreparedAsrAdapterTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-10-05T08:00:00Z"),ZoneOffset.UTC);
    final String task="51a3245a-3bcc-4338-a910-a130cc9b11fb";
    final String input="https://prepared-interview-fixture.oss-cn-beijing.aliyuncs.com/prepared/prepared-fixture/students/2/assessments/4/attempts/"+task+"/answers/"+task+"/media/"+"a".repeat(64)+".wav?Signature=private-original";
    final String result="http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/pre/20251027/13%3A57/fixture.json?Expires=123&Signature=private-result";
    final String success="{\"request_id\":\"poll-request\",\"output\":{\"task_id\":\""+task+"\",\"task_status\":\"SUCCEEDED\",\"result\":{\"transcription_url\":\""+result+"\"}},\"usage\":{\"seconds\":1}}";
    MockEnvironment config(){return new MockEnvironment().withProperty("prepared.models.enabled","true").withProperty("prepared.asr.worker-enabled","true")
        .withProperty("prepared.asr.api-key","fixture-key").withProperty("prepared.asr.endpoint",PreparedAsrPolicy.ENDPOINT)
        .withProperty("prepared.asr.model","qwen3-asr-flash-filetrans").withProperty("prepared.asr.billing-reference","independent-fixture")
        .withProperty("prepared.asr.pricing-reference","https://help.aliyun.com/zh/model-studio/model-pricing")
        .withProperty("prepared.asr.pricing-verified-at","2026-10-05T07:00:00Z").withProperty("prepared.asr.cny-per-second","0.001");}
    PreparedAsrPolicy policy(){return new PreparedAsrPolicy(config(),clock);}
    PreparedWaveAudio.Verified audio(){return new PreparedWaveAudio.Verified(32044,"b".repeat(64),1000,16000,1,44,32000);}
    PreparedPrivateOssStore.StoredAnswer media(){var s=new PreparedPrivateOssStore.AnswerFile(4,2,task,task,"a".repeat(64));return new PreparedPrivateOssStore.StoredAnswer(s,s.key("prepared-fixture"),audio());}
    String raw(){return "{\"file_url\":\""+input+"\",\"audio_info\":{\"format\":\"wav\",\"sample_rate\":16000},\"transcripts\":[{\"channel_id\":0,\"text\":\"合成文本。\",\"sentences\":[{\"sentence_id\":0,\"begin_time\":0,\"end_time\":1000,\"text\":\"合成文本。\"}]}]}";}
    PreparedAsrHttpClient client(String response,int status){return new PreparedAsrHttpClient(new OkHttpClient.Builder().addInterceptor(c->reply(c.request(),response,status)).build());}
    Response reply(Request req,String raw,int code){return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(ResponseBody.create(raw,MediaType.get("application/json"))).build();}
    @Test void verifiedIndependentPriceBoundsWholeOriginalAndKnownTaskCanUseHistoricalPrice(){
        var p=policy();var s=p.snapshot();assertThat(p.availability().enabled()).isTrue();assertThat(s.maximumCost(audio())).isEqualByComparingTo("0.001");assertThat(s.cost(3)).isEqualByComparingTo("0.003");
        assertThat(s).isEqualTo(new PreparedAsrPolicy.Snapshot(s.endpoint(),s.model(),s.billingReference(),s.pricingReference(),s.verifiedAt(),new BigDecimal("0.001000"),s.budgetScope()));
        var newer=new PreparedAsrPolicy(config().withProperty("prepared.asr.cny-per-second","0.002"),clock);assertThat(newer.canPoll(s)).isTrue();assertThat(s.cost(3)).isEqualByComparingTo("0.003");
    }
    @Test void unsafeMissingStaleOrWrongModelConfigNeverPermitsNewPaidCalls(){
        assertThat(new PreparedAsrPolicy(new MockEnvironment(),clock).availability().enabled()).isFalse();
        for(var e:List.of(Map.entry("worker-enabled","false"),Map.entry("api-key",""),Map.entry("api-key","bad key"),Map.entry("endpoint","http://127.0.0.1"),Map.entry("model","qwen3-asr-flash"),Map.entry("billing-reference","bad identity"),Map.entry("pricing-reference","https://evil.invalid/pricing"),Map.entry("pricing-verified-at","2026-09-01T00:00:00Z"),Map.entry("pricing-verified-at","2026-10-06T00:00:00Z"),Map.entry("cny-per-second","0"),Map.entry("cny-per-second","1")))
            assertThat(new PreparedAsrPolicy(config().withProperty("prepared.asr."+e.getKey(),e.getValue()),clock).availability().enabled()).as(e.getKey()).isFalse();
        assertThat(new PreparedAsrPolicy(config().withProperty("prepared.asr.billing-reference","other-account"),clock).canPoll(policy().snapshot())).isFalse();
    }
    @Test void submitsExactlyOnceWithOriginalPrivateWaveAndSingleChannelThenQueriesKnownTask()throws Exception {
        var sends=new java.util.concurrent.atomic.AtomicInteger();
        var client=new PreparedAsrHttpClient(new OkHttpClient.Builder().addInterceptor(c->{sends.incrementAndGet();var r=c.request();assertThat(r.header("Authorization")).isEqualTo("Bearer fixture-key");
            if(r.method().equals("POST")){assertThat(r.header("X-DashScope-Async")).isEqualTo("enable");var b=new Buffer();r.body().writeTo(b);assertThat(b.readUtf8()).contains("qwen3-asr-flash-filetrans","channel_id","enable_itn",input).doesNotContain("language","rubric","studentId");return reply(r,"{\"output\":{\"task_id\":\""+task+"\",\"task_status\":\"PENDING\"}}",200);}
            assertThat(r.url().toString()).endsWith("/api/v1/tasks/"+task);return reply(r,success,200);
        }).build());
        assertThat(client.submit(policy().snapshot(),new PreparedPrivateOssStore.Preview(input,"2026-10-06T08:00:00Z"),media(),"fixture-key")).isEqualTo(task);
        var poll=client.poll(policy().snapshot(),task,"fixture-key");assertThat(poll.seconds()).isEqualTo(1);assertThat(poll.resultUri().toString()).startsWith("https://").contains("13%3A57");
        assertThat(poll.toString()).doesNotContain("private-result","Signature");assertThat(sends).hasValue(2);assertThat(client.http().retryOnConnectionFailure()).isFalse();assertThat(client.http().followRedirects()).isFalse();
    }
    @Test void knownChargeSurvivesBadResultUrlButInvalidTaskOrUsageNeverClaimsSuccess()throws Exception {
        assertThat(client(success.replace(result,"https://evil.invalid/result.json"),200).poll(policy().snapshot(),task,"fixture-key").seconds()).isEqualTo(1);
        assertThat(client(success.replace(result,"https://evil.invalid/result.json"),200).poll(policy().snapshot(),task,"fixture-key").resultUri()).isNull();
        for(String raw:List.of("{}",success.replace(task,"another-task"),success.replace("\"seconds\":1","\"seconds\":1.5"),success.replace("\"seconds\":1","\"seconds\":-1"),"x".repeat(524289)))
            assertThatThrownBy(()->client(raw,200).poll(policy().snapshot(),task,"fixture-key")).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->client(success,302).poll(policy().snapshot(),task,"fixture-key")).isInstanceOf(java.io.IOException.class);
    }
    @Test void boundedResultDownloadDoesNotCarryModelKeyAndNormalizesExactFileAndSentenceEvidence()throws Exception {
        var client=new PreparedAsrHttpClient(new OkHttpClient.Builder().addInterceptor(c->{assertThat(c.request().header("Authorization")).isNull();assertThat(c.request().url().scheme()).isEqualTo("https");return reply(c.request(),raw(),200);}).build());
        var text=client.download(PreparedAsrUrls.result(result),policy().snapshot(),media(),PreparedJobInput.digest(input));assertThat(text.sentences()).hasSize(1);assertThat(text.audioSha256()).isEqualTo(audio().sha256());
        assertThat(PreparedProcessingCodec.encode(text)).doesNotContain("private-original","file_url","Signature");
        for(String raw:List.of(raw().replace("16000","8000"),raw().replace("\"wav\"","\"mp3\""),raw().replace("\"channel_id\":0","\"channel_id\":1"),raw().replace("\"sentence_id\":0","\"sentence_id\":2"),raw().replace("1000","1001"),raw().replace("private-original","other-original"),raw().replace("合成文本。","")))
            assertThatThrownBy(()->client(raw,200).download(PreparedAsrUrls.result(result),policy().snapshot(),media(),PreparedJobInput.digest(input))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->client("x".repeat(524289),200).download(PreparedAsrUrls.result(result),policy().snapshot(),media(),PreparedJobInput.digest(input))).isInstanceOf(java.io.IOException.class);
    }
    @Test void resultUrlsAndPrivateInputNeverPermitSsrfTraversalOrWrongOriginal() {
        for(String u:Arrays.asList(null,"http://127.0.0.1/a.json",result.replace("13%3A57","%2e%2e"),result.replace("13%3A57",".."),result.replace("http://","http://user@"),result+"#secret",result.replace("fixture.json","file.html"),result.replace("aliyuncs.com","aliyuncs.com.evil.invalid")))
            assertThatThrownBy(()->PreparedAsrUrls.result(u)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-result");
        for(String u:List.of(input.replace(".wav",".mp3"),input.replace("/students/2/","/students/3/"),input.replace("https:","http:"),input.replace("aliyuncs.com","aliyuncs.com.evil.invalid")))
            assertThatThrownBy(()->PreparedAsrUrls.input(u,media())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void longWholeAnswerSupportsThirtyMinutesInsteadOfShortChatAudioLimits() {
        var a=new PreparedWaveAudio.Verified(57600044,"a".repeat(64),1800000,16000,1,44,57600000);assertThat(policy().snapshot().maximumCost(a)).isEqualByComparingTo("1.8");
        for(var bad:List.of(new PreparedWaveAudio.Verified(1,"a".repeat(64),0,16000,1,44,0),new PreparedWaveAudio.Verified(1,"a".repeat(64),1800001,16000,1,44,1),new PreparedWaveAudio.Verified(1,"a".repeat(64),1,8000,1,44,1),new PreparedWaveAudio.Verified(1,"a".repeat(64),1,16000,2,44,1)))
            assertThatThrownBy(()->policy().snapshot().maximumCost(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->policy().snapshot().maximumCost(null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void strictIntegerUsageEmptyOutputsPendingFailureAndMalformedHttpAreBounded()throws Exception {
        for(String state:List.of("PENDING","RUNNING","FAILED","UNKNOWN")){
            var p=client("{\"output\":{\"task_id\":\""+task+"\",\"task_status\":\""+state+"\"}}",200).poll(policy().snapshot(),task,"fixture-key");assertThat(p.status()).isEqualTo(state);assertThat(p.seconds()).isNull();
        }
        for(String value:List.of("-1","100000001","999999999999999999999","null","\"1\""))assertThatThrownBy(()->client(success.replace("\"seconds\":1","\"seconds\":"+value),200).poll(policy().snapshot(),task,"fixture-key")).isInstanceOf(java.io.IOException.class);
        for(String raw:List.of(success.replace("\"usage\":","\"other\":"),success.replace("SUCCEEDED","INVALID"),"[]","{}{}",success.replace("\"seconds\":1","\"seconds\":1,\"seconds\":2")))
            assertThatThrownBy(()->client(raw,200).poll(policy().snapshot(),task,"fixture-key")).isInstanceOf(java.io.IOException.class);
        for(String key:Arrays.asList(null," ","bad key","x".repeat(513)))assertThatThrownBy(()->client(success,200).poll(policy().snapshot(),task,key)).isInstanceOf(IllegalArgumentException.class);
        for(String raw:List.of("{}","[]","{\"output\":{\"task_id\":\""+task+"\",\"task_status\":\"UNKNOWN\"}}"))
            assertThatThrownBy(()->client(raw,200).submit(policy().snapshot(),new PreparedPrivateOssStore.Preview(input,"2026-10-06T08:00:00Z"),media(),"fixture-key")).isInstanceOf(java.io.IOException.class);
    }
    @Test void additionalUnsafeEndpointsPricesAndResultBindingsAreRejected()throws Exception {
        for(String endpoint:Arrays.asList(null,"https://user@dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription",PreparedAsrPolicy.ENDPOINT+"?secret=x",PreparedAsrPolicy.ENDPOINT+"#fragment",PreparedAsrPolicy.ENDPOINT.replace(".com/",".com:8443/"),PreparedAsrPolicy.ENDPOINT.replace("dashscope.aliyuncs.com","evil.invalid")))
            assertThatThrownBy(()->PreparedAsrPolicy.validateEndpoint(endpoint)).isInstanceOf(IllegalArgumentException.class);
        PreparedAsrPolicy.validateEndpoint(PreparedAsrPolicy.ENDPOINT.replace("dashscope.aliyuncs.com","fixture.cn-beijing.maas.aliyuncs.com"));
        for(var e:List.of(Map.entry("pricing-reference","http://help.aliyun.com/pricing"),Map.entry("pricing-reference","https://user@help.aliyun.com/pricing"),Map.entry("pricing-reference","https://help.aliyun.com:8443/pricing"),Map.entry("pricing-reference","https://help.aliyun.com/pricing#fragment"),Map.entry("pricing-reference","x".repeat(1001)),Map.entry("api-key","x".repeat(513)),Map.entry("pricing-verified-at","invalid-date"),Map.entry("cny-per-second","NaN"),Map.entry("cny-per-second","0.0000001")))
            assertThat(new PreparedAsrPolicy(config().withProperty("prepared.asr."+e.getKey(),e.getValue()),clock).availability().enabled()).isFalse();
        for(long seconds:List.of(-1L,100000001L))assertThatThrownBy(()->policy().snapshot().cost(seconds)).isInstanceOf(IllegalArgumentException.class);
        for(String u:List.of(result.replace("http:","ftp:"),result.replace("fixture.json","./fixture.json"),result.replace("aliyuncs.com/","aliyuncs.com:8443/"),result.substring(0,result.indexOf('?')),"x".repeat(4097)))
            assertThatThrownBy(()->PreparedAsrUrls.result(u)).isInstanceOf(IllegalArgumentException.class);
        for(String raw:List.of(raw().replace("\"transcripts\":[","\"transcripts\":[{},"),raw().replace("\"sentences\":[", "\"sentences\":null,\"unused\":["),raw().replace("\"begin_time\":0","\"begin_time\":0.5"),raw().replace("\"channel_id\":0","\"channel_id\":null"),raw().replace("\"text\":\"合成文本。\",\"sentences\"","\"text\":\"other\",\"sentences\"")))
            assertThatThrownBy(()->client(raw,200).download(PreparedAsrUrls.result(result),policy().snapshot(),media(),PreparedJobInput.digest(input))).isInstanceOf(java.io.IOException.class);
    }
}
