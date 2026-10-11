package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import system.assessment.defense.prepared.attempt.PreparedAnswerMediaRuntime;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PreparedAnswerMediaRuntimeTest {
    @TempDir Path folder;
    MockEnvironment config()throws Exception{return new MockEnvironment().withProperty("prepared.answer-media.worker-enabled","true")
            .withProperty("prepared.answer-media.spool-root",folder.toRealPath().resolve("prepared-answer-spool").toString())
            .withProperty("prepared.answer-media.ffmpeg",Path.of("/Users/duanhangyu/.homebrew/bin/ffmpeg").toRealPath().toString())
            .withProperty("prepared.answer-media.ffprobe",Path.of("/Users/duanhangyu/.homebrew/bin/ffprobe").toRealPath().toString());}
    Lease lease(){return new Lease(new Job(UUID.randomUUID().toString(),1,2,Kind.ANSWER_MEDIA,1,"{}",null,State.RUNNING,1),1,"fixture");}
    @Test void defaultClosedAndMissingOrNonPrivateConfigurationCannotRunWorker()throws Exception {
        assertThat(new PreparedAnswerMediaRuntime(new MockEnvironment()).available()).isFalse();
        for(var entry:Map.of("spool-root","/tmp/unrelated","ffmpeg","relative","ffprobe","").entrySet())assertThat(new PreparedAnswerMediaRuntime(config().withProperty("prepared.answer-media."+entry.getKey(),entry.getValue())).available()).isFalse();
        var runtime=new PreparedAnswerMediaRuntime(config());assertThat(runtime.available()).isTrue();var lease=lease();Path dir=runtime.stage(lease);
        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        assertThatThrownBy(()->runtime.stage(lease)).isInstanceOf(java.io.IOException.class);
        Path root=dir.getParent().getParent();Files.setPosixFilePermissions(root,java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));assertThat(runtime.available()).isFalse();
    }
    @Test void cleanupOnlyExactSuccessfulJobGenerationAndKnownRegularArtifacts()throws Exception {
        var runtime=new PreparedAnswerMediaRuntime(config());var lease=lease();Path dir=runtime.stage(lease);
        Files.writeString(dir.resolve("segment-1.bin"),"original scratch");Files.writeString(dir.resolve("answer.wav.segment-0.wav"),"decoded scratch");Files.writeString(dir.resolve("answer.wav"),"canonical scratch");
        assertThatThrownBy(()->runtime.releaseSuccess(lease,folder.toRealPath())).isInstanceOf(java.io.IOException.class);
        var unrelated=dir.resolve("do-not-delete.txt");Files.writeString(unrelated,"unrelated");assertThatThrownBy(()->runtime.releaseSuccess(lease,dir)).isInstanceOf(java.io.IOException.class);assertThat(Files.exists(dir.resolve("segment-1.bin"))).isTrue();
        Files.delete(unrelated);runtime.releaseSuccess(lease,dir);assertThat(Files.exists(dir)).isFalse();
    }
}
