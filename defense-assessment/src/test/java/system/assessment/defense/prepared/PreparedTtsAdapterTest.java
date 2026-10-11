package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import okhttp3.*;
import okio.Buffer;
import system.assessment.defense.prepared.speech.*;
import java.nio.file.*;
import java.net.URI;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** HTTP interception and synthetic PCM only. No real provider or object-storage claim. */
class PreparedTtsAdapterTest {
    @TempDir Path folder;
    final Clock clock=Clock.fixed(Instant.parse("2026-10-04T08:00:00Z"),ZoneOffset.UTC);
    final String url="http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/1d/ab/fixture.wav?Expires=1791108000&OSSAccessKeyId=fixture&Signature=secret-fixture";
    final String response="{\"request_id\":\"fixture-request\",\"usage\":{\"characters\":12},\"output\":{\"finish_reason\":\"stop\",\"audio\":{\"url\":\""+url+"\",\"expires_at\":1791108000}}}";
    MockEnvironment configured() {
        return new MockEnvironment().withProperty("prepared.models.enabled","true").withProperty("prepared.speech.worker-enabled","true")
                .withProperty("prepared.speech.api-key","fixture-key").withProperty("prepared.speech.endpoint","https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
                .withProperty("prepared.speech.model","qwen3-tts-flash").withProperty("prepared.speech.voice","Cherry").withProperty("prepared.speech.language","Chinese")
                .withProperty("prepared.speech.billing-reference","independent-fixture").withProperty("prepared.speech.pricing-reference","https://help.aliyun.com/en/model-studio/model-pricing")
                .withProperty("prepared.speech.pricing-verified-at","2026-10-04T07:00:00Z").withProperty("prepared.speech.max-billed-characters","600")
                .withProperty("prepared.speech.cny-per-ten-thousand-characters","0.8");
    }
    PreparedTtsPolicy policy(){return new PreparedTtsPolicy(configured(),clock);}
    @Test void pricingIsConservativeDecimalAndUsesTheSharedHundredYuanBudget() {
        var p=policy();var s=p.snapshot();assertThat(p.availability().enabled()).isTrue();
        assertThat(s.maximumCost()).isEqualByComparingTo("0.048");assertThat(p.actualCost(s,12)).isEqualByComparingTo("0.00096");
        assertThat(p.budgetCap()).isEqualByComparingTo("100");assertThat(p.pauseAt()).isEqualByComparingTo("80");
        assertThat(s.budgetScope()).isEqualTo("prepared-model-tests");
        assertThat(s).isEqualTo(new PreparedTtsPolicy.Snapshot(s.endpoint(),s.model(),s.voice(),s.language(),s.billingReference(),s.pricingReference(),s.verifiedAt(),s.maxBilledCharacters(),new BigDecimal("0.800000"),s.budgetScope()));
    }
    @Test void disabledMissingOldFutureOrUnboundedPricingRefusesPaidPreparation() {
        assertThat(new PreparedTtsPolicy(new MockEnvironment(),clock).availability().enabled()).isFalse();
        for(var entry:Map.ofEntries(Map.entry("api-key",""),Map.entry("worker-enabled","false"),Map.entry("endpoint","https://evil.invalid/api"),
                Map.entry("model","qwen-tts"),Map.entry("voice","not-a-supported-voice"),Map.entry("language","Unknown"),Map.entry("billing-reference","bad identity"),
                Map.entry("pricing-reference","http://help.aliyun.com/pricing"),Map.entry("pricing-verified-at","2026-09-01T00:00:00Z"),
                Map.entry("max-billed-characters","599"),Map.entry("cny-per-ten-thousand-characters","0")).entrySet()) {
            var env=configured().withProperty("prepared.speech."+entry.getKey(),entry.getValue());
            assertThat(new PreparedTtsPolicy(env,clock).availability().enabled()).as(entry.getKey()).isFalse();
        }
        for(String date:List.of("2026-10-05T00:00:00Z","bad-date"))assertThat(new PreparedTtsPolicy(configured().withProperty("prepared.speech.pricing-verified-at",date),clock).availability().enabled()).isFalse();
        for(String rate:List.of("-1","NaN","1001","0.1234567"))assertThat(new PreparedTtsPolicy(configured().withProperty("prepared.speech.cny-per-ten-thousand-characters",rate),clock).availability().enabled()).isFalse();
        assertThat(new PreparedTtsPolicy(configured().withProperty("prepared.models.enabled","false"),clock).availability().enabled()).isFalse();
        assertThat(new PreparedTtsPolicy(configured().withProperty("prepared.speech.max-billed-characters","1000000"),clock).availability().enabled()).isFalse();
    }
    @Test void sendsOneBoundedNativeHttpRequestAndAccountsForCharactersNotTokens() throws Exception {
        AtomicInteger sends=new AtomicInteger();
        var client=new PreparedTtsHttpClient(new OkHttpClient.Builder().addInterceptor(chain->{
            sends.incrementAndGet();var req=chain.request();var buffer=new Buffer();req.body().writeTo(buffer);
            assertThat(buffer.readUtf8()).contains("qwen3-tts-flash","Cherry","language_type","Chinese","题目文字").doesNotContain("instructions","enable_thinking");
            assertThat(req.header("Authorization")).isEqualTo("Bearer fixture-key");assertThat(req.header("X-DashScope-SSE")).isNull();
            return reply(req,response,200);
        }).build(),clock);
        var r=client.synthesize(policy().snapshot(),"题目文字","fixture-key");
        assertThat(r.characters()).isEqualTo(12);assertThat(r.providerId()).isEqualTo("fixture-request");
        assertThat(r.audioUri().toString()).startsWith("https://dashscope-result-bj.").endsWith("Signature=secret-fixture");
        assertThat(r.toString()).doesNotContain("secret-fixture","OSSAccessKeyId");assertThat(sends.get()).isEqualTo(1);
        assertThat(client.http().retryOnConnectionFailure()).isFalse();assertThat(client.http().followRedirects()).isFalse();assertThat(client.http().callTimeoutMillis()).isEqualTo(45000);
    }
    @Test void billingKnownButBadAudioKeepsItsChargeForSettlement() throws Exception {
        for(String raw:List.of(response.replace(url,"https://evil.invalid/x.wav"),response.replace("\"stop\"","null"),response.replace("1791108000","1"),
                response.replace("\"audio\":", "\"other\":"),response.replace("\"url\":\""+url+"\"","\"url\":123"))) {
            var r=withResponse(raw,200).synthesize(policy().snapshot(),"题目文字","fixture-key");
            assertThat(r.characters()).isEqualTo(12);assertThat(r.audioUri()==null || !"stop".equals(r.finishReason())).isTrue();
        }
    }
    @Test void unknownUsageOrHttpFailureIsNotAutomaticallyReplayed() {
        for(String raw:List.of("{}",response.replace("12}","-1}"),response.replace("12}","12.5}"),response.replace("\"characters\"","\"other\""),
                response.replace("fixture-request"," "),"x".repeat(524289),response.replace("12}","100000001}")))
            assertThatThrownBy(()->withResponse(raw,200).synthesize(policy().snapshot(),"题目","fixture-key")).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->withResponse(response,503).synthesize(policy().snapshot(),"题目","fixture-key")).isInstanceOf(java.io.IOException.class);
        for(String text:List.of(" ","x".repeat(601),"\uD800")) assertThatThrownBy(()->withResponse(response,200).synthesize(policy().snapshot(),text,"fixture-key")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void providerUrlsCannotBeUsedForSsrfOrSecretLogging() {
        for(String value:List.of("http://127.0.0.1/a.wav",url.replace("dashscope-result-bj.oss-cn-beijing.aliyuncs.com","dashscope-result-bj.oss-cn-beijing.aliyuncs.com.evil.invalid"),
                url.replace("http://","http://user@"),url.replace("fixture.wav","../fixture.wav"),url.replace("fixture.wav","%2e%2e/fixture.wav"),url+"#secret",url.replace("fixture.wav","file.html"))) {
            assertThatThrownBy(()->PreparedTtsUrls.https(value)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret-fixture");
        }
    }
    @Test void downloadsAndValidatesFullWaveWithoutPassingTheModelKeyOrOverwritingLocalData() throws Exception {
        byte[] wave=new PreparedSpeechMediaTest().wave();AtomicInteger sends=new AtomicInteger();
        var client=new PreparedTtsHttpClient(new OkHttpClient.Builder().addInterceptor(chain->{
            sends.incrementAndGet();assertThat(chain.request().header("Authorization")).isNull();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(wave,MediaType.get("audio/wav"))).build();
        }).build(),clock);
        var target=folder.resolve("download.wav");var downloaded=client.download(PreparedTtsUrls.https(url),target);
        assertThat(downloaded.byteSize()).isEqualTo(wave.length);assertThat(Files.readAllBytes(target)).isEqualTo(wave);
        assertThatThrownBy(()->client.download(PreparedTtsUrls.https(url),target)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readAllBytes(target)).isEqualTo(wave);assertThat(sends.get()).isEqualTo(1);
    }
    @Test void invalidMediaOrFailedDownloadNeverReportsVerifiedFile() throws Exception {
        for(var raw:List.of(new byte[0],new byte[50])) {
            var client=new PreparedTtsHttpClient(new OkHttpClient.Builder().addInterceptor(chain->new Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(raw,MediaType.get("audio/wav"))).build()).build(),clock);
            Path target=folder.resolve(UUID.randomUUID()+".wav");assertThatThrownBy(()->client.download(PreparedTtsUrls.https(url),target)).isInstanceOf(java.io.IOException.class);
            assertThat(Files.exists(target)).isFalse();
        }
        Path target=folder.resolve("failed.wav");assertThatThrownBy(()->withResponse(response,302).download(PreparedTtsUrls.https(url),target)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.exists(target)).isFalse();
    }
    private PreparedTtsHttpClient withResponse(String raw,int status){return new PreparedTtsHttpClient(new OkHttpClient.Builder().addInterceptor(chain->reply(chain.request(),raw,status)).build(),clock);}
    @Test void invalidKeysUrlsExpiriesAndStagingPathsAreRefusedWithoutLeakingSecrets() {
        for(String key:Arrays.asList(null," ","x".repeat(513),"contains space"))
            assertThatThrownBy(()->withResponse(response,200).synthesize(policy().snapshot(),"题目",key)).isInstanceOf(IllegalArgumentException.class);
        for(String url:Arrays.asList(null,"x".repeat(4097),"ftp://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.wav",
                "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com:8443/a.wav","https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/./a.wav"))
            assertThatThrownBy(()->PreparedTtsUrls.https(url)).isInstanceOf(IllegalArgumentException.class);
        for(Path path:Arrays.asList(null,Path.of("relative.wav"),folder.resolve("x/../file.wav"),folder.resolve("absent/file.wav")))
            assertThatThrownBy(()->withResponse(response,200).download(PreparedTtsUrls.https(this.url),path)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->withResponse(response,200).download(null,folder.resolve("none.wav"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PreparedTtsClient.Reply("fixture",0,"invalid",null,0).toString()).contains("audioAvailable=false");
    }
    @Test void knownChargesSurviveMalformedOrUnreasonablyDistantAudioExpiry() throws Exception {
        for(String raw:List.of(response.replace("\"expires_at\":1791108000","\"expires_at\":1791308000"),response.replace("\"expires_at\":1791108000","\"expires_at\":\"not-an-integer\""),
                response.replace("\"expires_at\":1791108000","\"expires_at\":999999999999999999999"))) {
            var r=withResponse(raw,200).synthesize(policy().snapshot(),"题目文字","fixture-key");assertThat(r.characters()).isEqualTo(12);assertThat(r.audioUri()).isNull();
        }
        assertThatThrownBy(()->withResponse(response.replace("12}","999999999999999999999}"),200).synthesize(policy().snapshot(),"题目","fixture-key")).isInstanceOf(java.io.IOException.class);
    }
    @Test void billingPolicyRejectsAbnormalEvidenceCredentialsAndUsageBounds() {
        for(var entry:List.of(Map.entry("pricing-reference","https://user@help.aliyun.com/pricing"),Map.entry("pricing-reference","https://help.aliyun.com:8443/pricing"),
                Map.entry("pricing-reference","https://help.aliyun.com/pricing#fragment"),Map.entry("max-billed-characters","1000001"),
                Map.entry("max-billed-characters","bad"),Map.entry("api-key","x".repeat(513)),Map.entry("api-key","contains space"),
                Map.entry("pricing-reference","x".repeat(1001))))
            assertThat(new PreparedTtsPolicy(configured().withProperty("prepared.speech."+entry.getKey(),entry.getValue()),clock).availability().enabled()).isFalse();
        for(long usage:List.of(-1L,100000001L))assertThatThrownBy(()->policy().actualCost(policy().snapshot(),usage)).isInstanceOf(IllegalArgumentException.class);
    }
    private Response reply(Request req,String raw,int status){return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(ResponseBody.create(raw,MediaType.get("application/json"))).build();}
}
