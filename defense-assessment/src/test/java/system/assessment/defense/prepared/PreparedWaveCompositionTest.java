package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.prepared.media.*;
import java.io.IOException;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** PCM prepared-broadcast composition only, never concatenates student recorder streams. */
class PreparedWaveCompositionTest {
    @TempDir Path folder;
    byte[] wave(){return new PreparedSpeechMediaTest().wave();}
    Path file(byte[] bytes) throws IOException {Path path=folder.resolve(UUID.randomUUID()+".wav");Files.write(path,bytes);return path;}
    @Test void allPcmPartsAreJoinedInOrderWithOneValidHeaderAndFullContentProof() throws Exception {
        byte[] first=wave(),second=wave();second[44]=42;Path a=file(first),b=file(second),target=folder.resolve("whole.wav");
        var proof=PreparedWaveComposer.compose(List.of(a,b),target);assertThat(proof.durationMs()).isEqualTo(2000);
        assertThat(proof.byteSize()).isEqualTo(96044);byte[] whole=Files.readAllBytes(target);
        assertThat(Arrays.copyOfRange(whole,44,48044)).isEqualTo(Arrays.copyOfRange(first,44,first.length));
        assertThat(Arrays.copyOfRange(whole,48044,whole.length)).isEqualTo(Arrays.copyOfRange(second,44,second.length));
        assertThat(Files.readAllBytes(a)).isEqualTo(first);assertThat(Files.readAllBytes(b)).isEqualTo(second);
    }
    @Test void optionalMetadataIsNotMistakenForPcmOrDuplicatedInTheResult() throws Exception {
        byte[] original=wave();var b=ByteBuffer.allocate(original.length+10).order(ByteOrder.LITTLE_ENDIAN);
        b.put(original,0,12).put("JUNK".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(1).put((byte)99).put((byte)0).put(original,12,original.length-12).putInt(4,b.capacity()-8);
        Path target=folder.resolve("metadata.wav");PreparedWaveComposer.compose(List.of(file(b.array())),target);
        assertThat(Files.readAllBytes(target)).isEqualTo(original);
    }
    @Test void mismatchedRateOrChannelsFailsBeforeCreatingOutput() throws Exception {
        for(boolean stereo:List.of(false,true)) {
            byte[] other=wave();var b=ByteBuffer.wrap(other).order(ByteOrder.LITTLE_ENDIAN);
            if(stereo)b.putShort(22,(short)2).putInt(28,96000).putShort(32,(short)4);else b.putInt(24,16000).putInt(28,32000);
            Path target=folder.resolve(UUID.randomUUID()+".wav");
            assertThatThrownBy(()->PreparedWaveComposer.compose(List.of(file(wave()),file(other)),target)).isInstanceOf(IOException.class);
            assertThat(Files.exists(target)).isFalse();
        }
    }
    @Test void existingOutputAndSourceFilesAreNeverOverwrittenOrDeleted() throws Exception {
        Path source=file(wave());assertThatThrownBy(()->PreparedWaveComposer.compose(List.of(source),source)).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(source)).isEqualTo(wave());
        Path link=folder.resolve("linked.wav");Files.createSymbolicLink(link,source);
        assertThatThrownBy(()->PreparedWaveComposer.compose(List.of(source),link)).isInstanceOf(IOException.class);
        assertThat(Files.isSymbolicLink(link)).isTrue();
    }
    @Test void emptyUnboundedInvalidOrMissingInputsAreRejected() throws Exception {
        Path good=file(wave()),target=folder.resolve("rejected.wav");
        for(List<Path> parts:Arrays.<List<Path>>asList(null,List.of(),Collections.nCopies(51,good),List.of(folder.resolve("missing")),List.of(file(new byte[50])))) {
            assertThatThrownBy(()->PreparedWaveComposer.compose(parts,target)).isInstanceOf(IOException.class);
            assertThat(Files.exists(target)).isFalse();
        }
        assertThatThrownBy(()->PreparedWaveComposer.compose(List.of(good),Path.of("relative.wav"))).isInstanceOf(IOException.class);
        assertThatThrownBy(()->PreparedWaveComposer.compose(List.of(good),null)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->PreparedWaveComposer.compose(List.of(good),folder.resolve("absent/whole.wav"))).isInstanceOf(IOException.class);
    }
    @Test void aggregateDurationAndByteLimitsApplyToTheWholeQuestionNotEachPart() throws Exception {
        Path duration=sparsePcm(8000,900),size=sparsePcm(24000,900);
        for(Path part:List.of(duration,size)) {
            Path target=folder.resolve(UUID.randomUUID()+".wav");
            assertThatThrownBy(()->PreparedWaveComposer.compose(Collections.nCopies(3,part),target)).isInstanceOf(IOException.class);
            assertThat(Files.exists(target)).isFalse();assertThat(Files.exists(part)).isTrue();
        }
    }
    Path sparsePcm(int rate,int seconds) throws Exception {
        long bytes=(long)rate*seconds*2;byte[] header=Arrays.copyOf(wave(),44);
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4,(int)bytes+36).putInt(24,rate).putInt(28,rate*2).putInt(40,(int)bytes);
        Path path=folder.resolve(UUID.randomUUID()+".wav");
        try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(header));channel.position(bytes+43);channel.write(ByteBuffer.wrap(new byte[]{0}));
        }return path;
    }
}
