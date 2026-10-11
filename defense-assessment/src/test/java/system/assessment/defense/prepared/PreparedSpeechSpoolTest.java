package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.*;
import system.assessment.defense.prepared.speech.PreparedSpeechSpool;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PreparedSpeechSpoolTest {
    @TempDir Path folder;
    @org.junit.jupiter.api.BeforeEach void canonicalDirectory() throws Exception {folder=folder.toRealPath();}
    Lease lease(){return new Lease(new Job(UUID.randomUUID().toString(),1,1,Kind.TTS,0,"{}",null,State.RUNNING,1),1,"fixture");}
    PreparedSpeechSpool spool(Path path){return new PreparedSpeechSpool(new MockEnvironment().withProperty("prepared.speech.spool-root",path.toString()));}
    @Test void stageIsPrivateScopedNewAndCannotOverwriteRetainedPaidMedia() throws Exception {
        var lease=lease();var spool=spool(folder.resolve("prepared-tts-spool"));Path target=spool.stage(lease);
        assertThat(target).isAbsolute();assertThat(target.toString()).contains(lease.job().id(),"generation-1");assertThat(Files.exists(target)).isFalse();
        Files.write(target,new byte[]{1,2});assertThatThrownBy(()->spool.stage(lease)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readAllBytes(target)).isEqualTo(new byte[]{1,2});
        var permissions=Files.getPosixFilePermissions(target.getParent());assertThat(permissions).doesNotContain(java.nio.file.attribute.PosixFilePermission.GROUP_READ,java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
        spool.release(lease,target);assertThat(Files.exists(target)).isFalse();assertThat(Files.exists(target.getParent())).isFalse();
    }
    @Test void oldWorkerGenerationAndUnrelatedFilesCannotBeCleaned() throws Exception {
        var lease=lease();var spool=spool(folder.resolve("prepared-tts-spool"));Path target=spool.stage(lease);Files.write(target,new byte[]{3});
        var old=new Lease(lease.job(),2,"fixture");assertThatThrownBy(()->spool.release(old,target)).isInstanceOf(java.io.IOException.class);
        Path unrelated=folder.resolve("student-recording.wav");Files.write(unrelated,new byte[]{4});
        assertThatThrownBy(()->spool.release(lease,unrelated)).isInstanceOf(java.io.IOException.class);assertThat(Files.exists(unrelated)).isTrue();
        Path other=target.getParent().resolve("retained.txt");Files.write(other,new byte[]{5});spool.release(lease,target);assertThat(Files.exists(other)).isTrue();
    }
    @Test void invalidBroadRelativeOrSymlinkRootsAreRejected() throws Exception {
        for(Path root:java.util.List.of(folder,Path.of("relative/prepared-tts-spool"),folder.resolve("x/../prepared-tts-spool")))
            assertThatThrownBy(()->spool(root).stage(lease())).isInstanceOf(java.io.IOException.class);
        Path real=folder.resolve("real");Files.createDirectory(real);Path link=folder.resolve("prepared-tts-spool");Files.createSymbolicLink(link,real);
        assertThatThrownBy(()->spool(link).stage(lease())).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->new PreparedSpeechSpool(new MockEnvironment()).stage(lease())).isInstanceOf(java.io.IOException.class);
    }
    @Test void existingSharedDirectoryIsNotSilentlyTreatedAsAPrivateSpool() throws Exception {
        Path root=folder.resolve("prepared-tts-spool");Files.createDirectory(root);
        Files.setPosixFilePermissions(root,java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(()->spool(root).stage(lease())).isInstanceOf(java.io.IOException.class);
        assertThat(Files.getPosixFilePermissions(root)).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
    }
}
