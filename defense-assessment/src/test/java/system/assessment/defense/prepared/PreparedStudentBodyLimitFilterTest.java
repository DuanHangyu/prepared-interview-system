package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import system.assessment.defense.prepared.attempt.PreparedStudentBodyLimitFilter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class PreparedStudentBodyLimitFilterTest {
    @Test void teacherReviewCommandsHaveTheirOwnSixteenKiBBoundOnCanonicalEncodedAndStreamingRequests()throws Exception {
        for(String path:new String[]{"/backend/prepared-reviews/9/attempts/a/answers/b/confirm","/campus/backend/%70repared-reviews/9/attempts/a/answers/b/regrade"})for(boolean known:new boolean[]{true,false}) {
            var req=request("POST",path,new byte[16385]);if(path.startsWith("/campus"))req.setContextPath("/campus");
            var streaming=new jakarta.servlet.http.HttpServletRequestWrapper(req){@Override public long getContentLengthLong(){return known?super.getContentLengthLong():-1;}};
            var response=new MockHttpServletResponse();filter.doFilter(streaming,response,(a,b)->{throw new AssertionError("oversize review must not reach JSON");});assertThat(response.getStatus()).isEqualTo(413);
        }
        var admitted=new AtomicBoolean();filter.doFilter(request("POST","/backend/prepared-reviews/9/confirm",new byte[16384]),new MockHttpServletResponse(),(a,b)->{assertThat(a.getInputStream().readAllBytes()).hasSize(16384);admitted.set(true);});assertThat(admitted).isTrue();
        for(String path:new String[]{"/backend/prepared-reviews;v=1/9","/backend/prepared-reviews%3Bv=1/9"})for(String method:new String[]{"GET","POST"}){
            var response=new MockHttpServletResponse();filter.doFilter(request(method,path,new byte[]{1}),response,(a,b)->{throw new AssertionError("no matrix routes");});assertThat(response.getStatus()).isEqualTo(400);
        }
        var compressed=request("POST","/backend/prepared-reviews/9/confirm",new byte[]{1});compressed.addHeader("Content-Encoding","gzip");var denied=new MockHttpServletResponse();filter.doFilter(compressed,denied,(a,b)->{throw new AssertionError("no decompression");});assertThat(denied.getStatus()).isEqualTo(415);
    }
    private final String chunkPath="/backend/prepared-interviews/9/attempts/00000000-0000-0000-0000-000000000001/answers/00000000-0000-0000-0000-000000000002/chunks/00000000-0000-0000-0000-000000000003/0";
    private final PreparedStudentBodyLimitFilter filter=new PreparedStudentBodyLimitFilter(new ObjectMapper());
    private MockHttpServletRequest request(String method,String path,byte[] bytes) {
        var request=new MockHttpServletRequest(method,path);request.setContentType("application/json");request.setContent(bytes);return request;
    }
    @Test void admittedJsonIsReplayableByTheActualDownstreamParser() throws Exception {
        byte[] data="{\"idempotencyKey\":\"原命令\"}".getBytes(StandardCharsets.UTF_8);
        var response=new MockHttpServletResponse();var called=new AtomicBoolean();
        filter.doFilter(request("POST","/backend/prepared-interviews/9/attempts",data),response,(req,res)->{
            assertThat(req.getInputStream().readAllBytes()).isEqualTo(data);called.set(true);
        });assertThat(called).isTrue();assertThat(response.getStatus()).isEqualTo(200);
    }
    @Test void declaredOrStreamingOversizeNeverReachesTheJsonParser() throws Exception {
        for(boolean known:new boolean[]{true,false}) {
            var request=request("POST","/backend/prepared-interviews/9/attempts/a/answers/b/end",new byte[PreparedStudentBodyLimitFilter.MAX_BYTES+1]);
            var streaming=new jakarta.servlet.http.HttpServletRequestWrapper(request){@Override public long getContentLengthLong(){return known?super.getContentLengthLong():-1;}};
            var response=new MockHttpServletResponse();var called=new AtomicBoolean();
            filter.doFilter(streaming,response,(req,res)->called.set(true));
            assertThat(called).isFalse();assertThat(response.getStatus()).isEqualTo(413);
            assertThat(new ObjectMapper().readTree(response.getContentAsString()).path("code").asInt()).isEqualTo(413);
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
        }
    }
    @Test void encodedAndMatrixPathsAndContextMountsCannotBypassTheSameBodyLimit() throws Exception {
        for(String path:new String[]{"/backend/%70repared-interviews/9/attempts","/backend/prepared-interviews;v=1/9/attempts","/campus/backend/%70repared-interviews/9/attempts"}) {
            var request=request("POST",path,new byte[PreparedStudentBodyLimitFilter.MAX_BYTES+1]);if(path.startsWith("/campus"))request.setContextPath("/campus");
            var response=new MockHttpServletResponse();var called=new AtomicBoolean();filter.doFilter(request,response,(req,res)->called.set(true));assertThat(called).isFalse();assertThat(response.getStatus()).isEqualTo(path.contains(";")?400:413);
        }
    }
    @Test void studentMatrixParametersAreRejectedForReadAndWriteIncludingEncodedSemicolons() throws Exception {
        for(String method:new String[]{"GET","POST"})for(String path:new String[]{"/backend/prepared-interviews;v=1/9/attempts","/backend/prepared-interviews%3Bv=1/9/attempts"}) {
            var response=new MockHttpServletResponse();var called=new AtomicBoolean();filter.doFilter(request(method,path,new byte[]{1}),response,(req,res)->called.set(true));assertThat(called).isFalse();assertThat(response.getStatus()).isEqualTo(400);
        }
    }
    @Test void exactLimitIsAdmittedAndUnrelatedLegacyUploadsAreNotBuffered() throws Exception {
        var response=new MockHttpServletResponse();var called=new AtomicBoolean();
        filter.doFilter(request("POST","/backend/prepared-interviews/9/attempts/a/answers/b/end",new byte[PreparedStudentBodyLimitFilter.MAX_BYTES]),response,(req,res)->{assertThat(req.getInputStream().readAllBytes()).hasSize(PreparedStudentBodyLimitFilter.MAX_BYTES);called.set(true);});
        assertThat(called).isTrue();called.set(false);
        filter.doFilter(request("POST","/file/upload",new byte[PreparedStudentBodyLimitFilter.MAX_BYTES+1]),new MockHttpServletResponse(),(req,res)->called.set(true));assertThat(called).isTrue();
    }
    @Test void compressedOrNonJsonStudentCommandsAreRejectedWithoutReadingTheBody() throws Exception {
        for(String mode:new String[]{"compressed","non-json","missing"}) {
            var request=request("POST","/backend/prepared-interviews/9/attempts",new byte[]{1});
            if(mode.equals("compressed"))request.addHeader("Content-Encoding","gzip");else request.setContentType(mode.equals("missing")?null:"text/plain");
            var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{throw new AssertionError("must not parse");});assertThat(response.getStatus()).isEqualTo(415);
        }
    }
    @Test void readOnlyMethodsAndOtherPathsPassThroughUntouched() throws Exception {
        for(String path:new String[]{"/backend/prepared-interviews/9/attempts/current","/backend/prepared-papers/9"}) {
            var request=request("GET",path,new byte[0]);var called=new AtomicBoolean();
            filter.doFilter(request,new MockHttpServletResponse(),(req,res)->{assertThat(req).isSameAs(request);called.set(true);});assertThat(called).isTrue();
        }
    }
    @Test void concurrentLargeCommandsAreBackpressuredAndPermitIsReleasedEvenWhenTheDownstreamFails() throws Exception {
        var bounded=new PreparedStudentBodyLimitFilter(new ObjectMapper(),1);var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        byte[] large=new byte[65537];
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var original=pool.submit(()->{try{bounded.doFilter(request("POST","/backend/prepared-interviews/9/attempts",large),new MockHttpServletResponse(),(req,res)->{entered.countDown();try{assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}throw new java.io.IOException("fixture failure");});return null;}catch(java.io.IOException failure){return failure;}});
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var response=new MockHttpServletResponse();bounded.doFilter(request("POST","/backend/prepared-interviews/9/attempts",large),response,(req,res)->{throw new AssertionError("must backpressure");});
            assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getHeader("Retry-After")).isEqualTo("1");
            release.countDown();assertThat(original.get(5,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(java.io.IOException.class);
            var called=new AtomicBoolean();bounded.doFilter(request("POST","/backend/prepared-interviews/9/attempts",new byte[]{1}),new MockHttpServletResponse(),(req,res)->called.set(true));assertThat(called).isTrue();
        }finally{release.countDown();}
    }
    @Test void boundedSmallCommandsDoNotWaitForTheLargeManifestLaneOrLoseTheirOwnBody()throws Exception {
        var bounded=new PreparedStudentBodyLimitFilter(new ObjectMapper(),1);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        byte[] large=new byte[65537],small="{\"idempotencyKey\":\"original-small-command\"}".getBytes(StandardCharsets.UTF_8);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            try{
                var occupied=pool.submit(()->{bounded.doFilter(request("POST","/backend/prepared-interviews/9/attempts/a/answers/b/end",large),new MockHttpServletResponse(),(req,res)->{
                    entered.countDown();try{assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}
                });return null;});
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var response=new MockHttpServletResponse();var called=new AtomicBoolean();
                bounded.doFilter(request("POST","/backend/prepared-interviews/9/attempts",small),response,(req,res)->{assertThat(req.getInputStream().readAllBytes()).isEqualTo(small);called.set(true);});
                assertThat(response.getStatus()).as("small original command must not inherit large manifest backpressure").isEqualTo(200);
                assertThat(called).isTrue();release.countDown();occupied.get(5,java.util.concurrent.TimeUnit.SECONDS);
            }finally{release.countDown();}
        }
    }
    @Test void invalidConcurrencyIsRefusedAndInterruptedAdmissionDoesNotReadOrLoseTheInterruption() throws Exception {
        assertThatThrownBy(()->new PreparedStudentBodyLimitFilter(new ObjectMapper(),0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedStudentBodyLimitFilter(new ObjectMapper(),17)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedStudentBodyLimitFilter(new ObjectMapper(),1,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedStudentBodyLimitFilter(new ObjectMapper(),1,129)).isInstanceOf(IllegalArgumentException.class);
        var response=new MockHttpServletResponse();Thread.currentThread().interrupt();
        try{filter.doFilter(request("POST","/backend/prepared-interviews/9/attempts",new byte[]{1}),response,(req,res)->{throw new AssertionError("must not read");});assertThat(response.getStatus()).isEqualTo(503);assertThat(Thread.currentThread().isInterrupted()).isTrue();}
        finally{Thread.interrupted();}
    }
    @Test void actualSmallBytesAreBoundedEvenWhenLengthIsFalseAndUnknownLengthsKeepTheLargeLane()throws Exception {
        var dishonest=request("POST","/backend/prepared-interviews/9/attempts",new byte[PreparedStudentBodyLimitFilter.SMALL_COMMAND_MAX_BYTES+1]);
        var declaredSmall=new jakarta.servlet.http.HttpServletRequestWrapper(dishonest){@Override public long getContentLengthLong(){return 1;}};
        var denied=new MockHttpServletResponse();filter.doFilter(declaredSmall,denied,(req,res)->{throw new AssertionError("cannot smuggle large bytes through small lane");});assertThat(denied.getStatus()).isEqualTo(413);
        var exact=request("POST","/backend/prepared-interviews/9/attempts",new byte[PreparedStudentBodyLimitFilter.SMALL_COMMAND_MAX_BYTES]);
        filter.doFilter(exact,new MockHttpServletResponse(),(req,res)->assertThat(req.getInputStream().readAllBytes()).hasSize(65536));
        var bounded=new PreparedStudentBodyLimitFilter(new ObjectMapper(),1);var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            try{
                var occupied=pool.submit(()->{bounded.doFilter(request("POST","/backend/prepared-interviews/9/attempts",new byte[65537]),new MockHttpServletResponse(),(req,res)->{entered.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}});return null;});
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var unknown=new jakarta.servlet.http.HttpServletRequestWrapper(request("POST","/backend/prepared-interviews/9/attempts",new byte[]{1})){@Override public long getContentLengthLong(){return -1;}};
                var response=new MockHttpServletResponse();bounded.doFilter(unknown,response,(req,res)->{throw new AssertionError("unknown body must not bypass large admission");});assertThat(response.getStatus()).isEqualTo(503);
                release.countDown();occupied.get(5,java.util.concurrent.TimeUnit.SECONDS);
            }finally{release.countDown();}
        }
    }
    @Test void smallLaneHasItsOwnBackpressureAndIsReleasedAfterDownstreamFailure()throws Exception {
        var bounded=new PreparedStudentBodyLimitFilter(new ObjectMapper(),1,1);var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            try{
                var occupied=pool.submit(()->{try{bounded.doFilter(request("POST","/backend/prepared-interviews/9/attempts",new byte[]{1}),new MockHttpServletResponse(),(req,res)->{entered.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}throw new java.io.IOException("small command fixture failure");});return null;}catch(java.io.IOException expected){return expected;}});
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var response=new MockHttpServletResponse();bounded.doFilter(request("POST","/backend/prepared-interviews/9/attempts",new byte[]{1}),response,(req,res)->{throw new AssertionError("small lane must remain bounded");});assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getHeader("Retry-After")).isEqualTo("1");
                bounded.doFilter(request("POST","/backend/prepared-interviews/9/attempts",new byte[65537]),new MockHttpServletResponse(),(req,res)->assertThat(req.getInputStream().readAllBytes()).hasSize(65537));
                release.countDown();assertThat(occupied.get(5,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(java.io.IOException.class);
                var called=new AtomicBoolean();bounded.doFilter(request("POST","/backend/prepared-interviews/9/attempts",new byte[]{1}),new MockHttpServletResponse(),(req,res)->called.set(true));assertThat(called).isTrue();
            }finally{release.countDown();}
        }
    }
    @Test void dedicatedChunkTransportStreamsBoundedBinaryWithoutJsonBuffering()throws Exception {
        var request=request("PUT",chunkPath,new byte[]{1,2,3});request.setContentType("application/octet-stream");var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->assertThat(req.getInputStream().readAllBytes()).containsExactly(1,2,3));assertThat(response.getStatus()).isEqualTo(200);
        var invalid=request("POST",chunkPath,new byte[]{1});invalid.setContentType("application/octet-stream");
        filter.doFilter(invalid,new MockHttpServletResponse(),(req,res)->{throw new AssertionError("binary only in PUT chunk route");});
    }
    @Test void chunkLimitsApplyToDeclaredAndActualBytesAndEncodedCanonicalRoutes()throws Exception {
        for(boolean known:new boolean[]{true,false}) {
            var request=request("PUT",chunkPath.replace("prepared-interviews","%70repared-interviews"),new byte[2097153]);request.setContentType("application/octet-stream");
            var wrapper=new jakarta.servlet.http.HttpServletRequestWrapper(request){@Override public long getContentLengthLong(){return known?super.getContentLengthLong():-1;}};
            var response=new MockHttpServletResponse();
            filter.doFilter(wrapper,response,(req,res)->req.getInputStream().readAllBytes());assertThat(response.getStatus()).isEqualTo(413);
        }
    }
    @Test void compressedChunkAndBinaryOnMetadataRouteAreRefusedBeforeReading()throws Exception {
        for(String path:new String[]{chunkPath,"/backend/prepared-interviews/9/attempts"}) {
            var request=request("PUT",path,new byte[1]);request.setContentType("application/octet-stream");if(path.equals(chunkPath))request.addHeader("Content-Encoding","gzip");
            var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{throw new AssertionError("must reject");});assertThat(response.getStatus()).isEqualTo(415);
        }
    }
    @Test void dedicatedBundleTransportAdmitsBoundedEightMiBButMetadataStaysSmall()throws Exception {
        String path=chunkPath.substring(0,chunkPath.indexOf("/chunks/"))+"/upload-bundles/"+"a".repeat(64);
        var request=request("PUT",path,new byte[8388608]);request.setContentType("application/octet-stream");var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->assertThat(req.getInputStream().readAllBytes()).hasSize(8388608));assertThat(response.getStatus()).isEqualTo(200);
        assertThat(PreparedStudentBodyLimitFilter.isChunkRoute(path)).isTrue();assertThat(PreparedStudentBodyLimitFilter.isChunkRoute(path.substring(0,path.lastIndexOf('/')))).isTrue();
        var metadata=request("POST",path.substring(0,path.lastIndexOf('/')),new byte[32769]);var rejected=new MockHttpServletResponse();
        filter.doFilter(metadata,rejected,(req,res)->{throw new AssertionError("bounded original group metadata only");});assertThat(rejected.getStatus()).isEqualTo(413);
        for(boolean known:new boolean[]{true,false}) {
            var large=request("PUT",path.replace("prepared-interviews","%70repared-interviews"),new byte[8388609]);large.setContentType("application/octet-stream");
            var streaming=new jakarta.servlet.http.HttpServletRequestWrapper(large){@Override public long getContentLengthLong(){return known?super.getContentLengthLong():-1;}};
            var denied=new MockHttpServletResponse();filter.doFilter(streaming,denied,(req,res)->req.getInputStream().readAllBytes());assertThat(denied.getStatus()).isEqualTo(413);
        }
    }
}
