package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import okhttp3.*;
import okio.Buffer;
import system.assessment.defense.prepared.generation.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PreparedGenerationClientTest {
    final String response="{\"id\":\"fixture-request\",\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":34},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}";
    PreparedGenerationPolicy.Snapshot snapshot(){var p=new PreparedGenerationPolicyTest();return new PreparedGenerationPolicy(p.configured(),p.clock).snapshot();}
    PreparedGeneration.Command command(){return new PreparedGeneration.Command(0L,UUID.randomUUID().toString(),"题目要求",1,10,120);}
    @Test void usesBoundedNonStreamingRequestWithNoAutomaticReplayOrRedirect() throws Exception {
        AtomicInteger sent=new AtomicInteger();
        var client=new PreparedGenerationHttpClient(new OkHttpClient.Builder().addInterceptor(chain->{
            sent.incrementAndGet();Request request=chain.request();Buffer body=new Buffer();request.body().writeTo(body);
            String json=body.readUtf8();assertThat(json).contains("\"stream\":false","\"enable_thinking\":false","\"max_completion_tokens\":4096","json_object","题目要求");
            assertThat(request.header("Authorization")).isEqualTo("Bearer fixture-key");
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(response,MediaType.get("application/json"))).build();
        }).build());
        var result=client.generate(snapshot(),command(),"fixture-key");
        assertThat(result.inputTokens()).isEqualTo(12);assertThat(result.outputTokens()).isEqualTo(34);assertThat(sent.get()).isEqualTo(1);
        assertThat(client.http().retryOnConnectionFailure()).isFalse();assertThat(client.http().followRedirects()).isFalse();
        assertThat(client.http().callTimeoutMillis()).isEqualTo(45000);
    }
    @Test void unknownBillingHttpFailureOversizedOrInvalidUsageFailsClosed() {
        for(String raw:List.of("{}",response.replace("12,","-1,"),response.replace("34}","34.5}"),response.replace("fixture-request"," "),"x".repeat(524289))) {
            var client=new PreparedGenerationHttpClient(new OkHttpClient.Builder().addInterceptor(chain->new Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(raw,MediaType.get("application/json"))).build()).build());
            assertThatThrownBy(()->client.generate(snapshot(),command(),"fixture-key")).isInstanceOf(java.io.IOException.class);
        }
    }
    @Test void knownUsageWithMalformedContentReturnsChargedInvalidOutputInsteadOfDiscardingUsage() throws Exception {
        for(String raw:List.of(response.replace("\"{}\"","null"),response.replace("\"stop\"","null"),
                "{\"id\":\"fixture\",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}",
                "{\"id\":\"fixture\",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1},\"choices\":[]}")) {
            var client=withResponse(raw,200);var result=client.generate(snapshot(),command(),"fixture-key");
            assertThat(result.inputTokens()).isPositive();
            assertThat(result.content().isEmpty() || !result.finishReason().equals("stop")).isTrue();
        }
    }
    @Test void unsuccessfulOrUncertainBillingResponseIsNeverReplayed() {
        for(String raw:List.of(response.replace("\"fixture-request\"","null"),response.replace("\"usage\"","\"other\""),
                response.replace("12,","100000001,"),response.replace("34}","100000001}"),
                response.replace("12,","\"12\",")))
            assertThatThrownBy(()->withResponse(raw,200).generate(snapshot(),command(),"fixture-key")).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->withResponse(response,503).generate(snapshot(),command(),"fixture-key")).isInstanceOf(java.io.IOException.class);
    }
    PreparedGenerationHttpClient withResponse(String raw,int status) {
        return new PreparedGenerationHttpClient(new OkHttpClient.Builder().addInterceptor(chain->new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(ResponseBody.create(raw,MediaType.get("application/json"))).build()).build());
    }
}
