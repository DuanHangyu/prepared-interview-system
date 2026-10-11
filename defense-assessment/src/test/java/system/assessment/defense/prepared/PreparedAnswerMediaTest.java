package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.prepared.media.PreparedAnswerMedia;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Actual local FFmpeg codecs and decode; generated tones are NOT physical microphone or cloud QA. */
class PreparedAnswerMediaTest {
    @TempDir Path folder;
    Path ffmpeg=Path.of("/Users/duanhangyu/.homebrew/bin/ffmpeg");
    Path ffprobe=Path.of("/Users/duanhangyu/.homebrew/bin/ffprobe");
    PreparedAnswerMedia media;
    @BeforeEach void setup()throws Exception {folder=folder.toRealPath();media=new PreparedAnswerMedia(ffmpeg.toRealPath(),ffprobe.toRealPath());}
    Path recording(String name,String codec,String format,double seconds)throws Exception {
        Path file=folder.resolve(name);
        var p=new ProcessBuilder(ffmpeg.toString(),"-nostdin","-loglevel","error","-f","lavfi","-i","sine=frequency=880:sample_rate=48000","-t",Double.toString(seconds),"-c:a",codec,"-f",format,"-n",file.toString()).redirectErrorStream(true).start();
        assertThat(p.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();return file;
    }
    PreparedAnswerMedia.SegmentInput input(Path file,String mime,long millis){return new PreparedAnswerMedia.SegmentInput(UUID.randomUUID().toString(),1,mime,file,millis);}
    @Test void decodesActualSupportedContainersAndProducesVerifiedWholePcmNotMetadataProof()throws Exception {
        int n=0;
        for(var cfg:List.of(List.of("libopus","webm","audio/webm;codecs=opus"),List.of("libopus","ogg","audio/ogg"),List.of("aac","mp4","audio/mp4"))) {
            var file=recording("source-"+n,""+cfg.get(0),cfg.get(1),1);
            var result=media.verify(List.of(input(file,cfg.get(2),1000)),2,folder.resolve("answer-"+n+++".wav"));
            assertThat(result.audio().durationMs()).isBetween(900L,1200L);assertThat(result.audio().sampleRate()).isEqualTo(16000);
            assertThat(result.audio().channels()).isEqualTo(1);assertThat(result.segments()).hasSize(1);assertThat(result.audio().sha256()).hasSize(64);
            assertThat(Files.exists(file)).isTrue();
        }
    }
    @Test void wrongMimeGarbageShortPrefixAndExcessiveRealDurationCannotBeReliableAnswer()throws Exception {
        var webm=recording("real.webm","libopus","webm",1);
        assertThatThrownBy(()->media.verify(List.of(input(webm,"audio/mp4",1000)),2,folder.resolve("wrong.wav"))).isInstanceOf(java.io.IOException.class);
        var garbage=folder.resolve("garbage.webm");Files.write(garbage,new byte[]{1,2,3});
        assertThatThrownBy(()->media.verify(List.of(input(garbage,"audio/webm",1000)),2,folder.resolve("garbage.wav"))).isInstanceOf(java.io.IOException.class);
        var shortAudio=recording("prefix.webm","libopus","webm",0.2);
        assertThatThrownBy(()->media.verify(List.of(input(shortAudio,"audio/webm",4000)),5,folder.resolve("prefix.wav"))).isInstanceOf(java.io.IOException.class);
        var longAudio=recording("long.webm","libopus","webm",3);
        assertThatThrownBy(()->media.verify(List.of(input(longAudio,"audio/webm",3000)),1,folder.resolve("long.wav"))).isInstanceOf(java.io.IOException.class);
        assertThat(Files.exists(webm)).isTrue();assertThat(Files.exists(garbage)).isTrue();
    }
    @Test void independentlyDecodesEachRecorderAndNeverRawConcatenatesDifferentContainers()throws Exception {
        var a=recording("one.webm","libopus","webm",1);var b=recording("two.ogg","libopus","ogg",1);
        var first=input(a,"audio/webm",1000);var second=new PreparedAnswerMedia.SegmentInput(UUID.randomUUID().toString(),2,"audio/ogg",b,1000);
        var result=media.verify(List.of(first,second),3,folder.resolve("both.wav"));
        assertThat(result.audio().durationMs()).isBetween(1900L,2200L);assertThat(result.segments()).hasSize(2);
        assertThatThrownBy(()->media.verify(List.of(second,first),3,folder.resolve("unordered.wav"))).isInstanceOf(java.io.IOException.class);
    }
    @Test void refusesSymlinksExistingOutputsUnsafeBinariesAndNonLocalFormats()throws Exception {
        var original=recording("one.webm","libopus","webm",1);var link=folder.resolve("link.webm");Files.createSymbolicLink(link,original);
        assertThatThrownBy(()->media.verify(List.of(input(link,"audio/webm",1000)),2,folder.resolve("linked.wav"))).isInstanceOf(java.io.IOException.class);
        var target=folder.resolve("existing.wav");Files.writeString(target,"original");
        assertThatThrownBy(()->media.verify(List.of(input(original,"audio/webm",1000)),2,target)).isInstanceOf(java.io.IOException.class);assertThat(Files.readString(target)).isEqualTo("original");
        assertThatThrownBy(()->new PreparedAnswerMedia(Path.of("relative"),ffprobe)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->media.verify(List.of(input(original,"application/x-mpegurl",1000)),2,folder.resolve("playlist.wav"))).isInstanceOf(java.io.IOException.class);
    }
    @Test void boundedChildTimeoutTerminatesItsReaderAndDoesNotHangExecutorShutdown()throws Exception {
        Path tooling=folder.resolve("tools");Files.createDirectory(tooling);Path hung=tooling.resolve("ffprobe");
        Files.writeString(hung,"#!/bin/sh\n/bin/sleep 10\n");Files.setPosixFilePermissions(hung,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var bounded=new PreparedAnswerMedia(ffmpeg.toRealPath(),hung,java.time.Duration.ofMillis(100));
        var original=recording("hung.webm","libopus","webm",1);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->assertThatThrownBy(()->bounded.verify(List.of(input(original,"audio/webm",1000)),2,folder.resolve("hung.wav"))).isInstanceOf(java.io.IOException.class));
        assertThat(Files.exists(original)).isTrue();
    }
    @Test void invalidOperationAndOriginalSegmentClaimsNeverDecodeOrOverwrite()throws Exception {
        var original=recording("bounds.webm","libopus","webm",1);var valid=input(original,"audio/webm",1000);
        for(int seconds:new int[]{0,1801})assertThatThrownBy(()->media.verify(List.of(valid),seconds,folder.resolve("invalid-limit-"+seconds+".wav"))).isInstanceOf(java.io.IOException.class);
        for(var invalid:List.of(List.<PreparedAnswerMedia.SegmentInput>of(),Collections.nCopies(1001,valid)))assertThatThrownBy(()->media.verify(invalid,2,folder.resolve("invalid-count.wav"))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->media.verify(null,2,folder.resolve("null-list.wav"))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->media.verify(List.of(valid),2,null)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->media.verify(List.of(valid),2,Path.of("relative.wav"))).isInstanceOf(java.io.IOException.class);
        for(var invalid:Arrays.asList(null,new PreparedAnswerMedia.SegmentInput(null,1,"audio/webm",original,1000),new PreparedAnswerMedia.SegmentInput("not-uuid",1,"audio/webm",original,1000),
                new PreparedAnswerMedia.SegmentInput(valid.id(),1,"audio/webm",original,0),new PreparedAnswerMedia.SegmentInput(valid.id(),1,"audio/webm",original,2001),
                new PreparedAnswerMedia.SegmentInput(valid.id(),1,"audio/webm",null,1000),new PreparedAnswerMedia.SegmentInput(valid.id(),1,null,original,1000)))
            assertThatThrownBy(()->media.verify(Collections.singletonList(invalid),2,folder.resolve("invalid-"+UUID.randomUUID()+".wav"))).isInstanceOf(java.io.IOException.class);
        var duplicate=new PreparedAnswerMedia.SegmentInput(valid.id(),2,"audio/webm",original,1000);
        assertThatThrownBy(()->media.verify(List.of(valid,duplicate),3,folder.resolve("duplicate.wav"))).isInstanceOf(java.io.IOException.class);
        for(var duration:Arrays.asList(null,java.time.Duration.ZERO,java.time.Duration.ofSeconds(-1),java.time.Duration.ofSeconds(91)))
            assertThatThrownBy(()->new PreparedAnswerMedia(ffmpeg.toRealPath(),ffprobe.toRealPath(),duration)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(original)).isTrue();
    }
}
