package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.prepared.media.*;
import java.nio.file.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic PCM and text boundaries only; no provider, speech recognition or physical device claim. */
class PreparedSpeechMediaTest {
    @TempDir Path folder;
    Path audio(byte[] bytes) throws Exception {var p=folder.resolve(UUID.randomUUID()+".wav");Files.write(p,bytes);return p;}
    byte[] wave() {
        var b=ByteBuffer.allocate(48044).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(48036).put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16);
        b.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(48000);
        while(b.hasRemaining())b.putShort((short)100);
        return b.array();
    }
    byte[] changed(int position,int value,int width) {
        byte[] bytes=wave();var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(width==2)b.putShort(position,(short)value);else b.putInt(position,value);return bytes;
    }
    @Test void fullTextIsSplitWithoutTruncationWhitespaceLossOrBrokenUnicode() {
        for(String body:List.of("小题", "x".repeat(600), "x".repeat(601), "问题。".repeat(3000), "😀".repeat(4999),"x".repeat(599)+"😀","a".repeat(580)+"。\n\n"+"b".repeat(900))) {
            var pieces=PreparedSpeechText.split(body);
            assertThat(String.join("",pieces)).isEqualTo(body);
            assertThat(pieces).allSatisfy(p->{assertThat(p.length()).isBetween(1,600);assertThat(Character.isHighSurrogate(p.charAt(p.length()-1))).isFalse();});
            assertThatThrownBy(()->pieces.clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }
    @Test void invalidTextAndIsolatedSurrogatesAreRejectedBeforePaidSynthesis() {
        for(String body:Arrays.asList(null," ","x".repeat(10001),"a\uD800b","\uDC00b","a\uD800"))
            assertThatThrownBy(()->PreparedSpeechText.split(body)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pcmWaveValidationComputesActualBoundedContentProof() throws Exception {
        byte[] bytes=wave();var result=PreparedWaveAudio.inspect(audio(bytes));
        assertThat(result.byteSize()).isEqualTo(48044);assertThat(result.durationMs()).isEqualTo(1000);
        assertThat(result.sampleRate()).isEqualTo(24000);assertThat(result.channels()).isEqualTo(1);
        assertThat(result.dataOffset()).isEqualTo(44);assertThat(result.dataSize()).isEqualTo(48000);
        assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    @Test void extensionOrNonemptyBytesNeverCountAsAValidWave() throws Exception {
        for(byte[] bytes:List.of(new byte[0],new byte[44],Arrays.copyOf(wave(),45),changed(0,0,4),changed(8,0,4),changed(4,48037,4))) {
            Path p=audio(bytes);assertThatThrownBy(()->PreparedWaveAudio.inspect(p)).isInstanceOf(java.io.IOException.class);
        }
    }
    @Test void compressedBigEndianInvalidFormatOrResourceBoundsAreRejected() throws Exception {
        for(byte[] bytes:List.of(changed(20,3,2),changed(22,0,2),changed(22,3,2),changed(24,1,4),changed(24,96000,4),
                changed(28,10,4),changed(32,4,2),changed(34,8,2),changed(16,15,4),changed(40,0,4),changed(40,47999,4),changed(40,48002,4))) {
            Path p=audio(bytes);assertThatThrownBy(()->PreparedWaveAudio.inspect(p)).isInstanceOf(java.io.IOException.class);
        }
    }
    @Test void duplicateAndOutOfOrderChunksAreNotSilentlyConcatenated() throws Exception {
        byte[] original=wave();
        var duplicate=ByteBuffer.allocate(original.length+24).order(ByteOrder.LITTLE_ENDIAN);
        duplicate.put(original,0,36).put(original,12,24).put(original,36,original.length-36);
        duplicate.putInt(4,duplicate.capacity()-8);
        Path dup=audio(duplicate.array());assertThatThrownBy(()->PreparedWaveAudio.inspect(dup)).isInstanceOf(java.io.IOException.class);
        byte[] dataFirst=original.clone();System.arraycopy("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,dataFirst,12,4);
        Path outOfOrder=audio(dataFirst);assertThatThrownBy(()->PreparedWaveAudio.inspect(outOfOrder)).isInstanceOf(java.io.IOException.class);
    }
    @Test void boundedOptionalMetadataIsHashedAndSkippedWithoutAllocatingItsDeclaredSize() throws Exception {
        byte[] original=wave();var b=ByteBuffer.allocate(original.length+10).order(ByteOrder.LITTLE_ENDIAN);
        b.put(original,0,12).put("JUNK".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(1).put((byte)42).put((byte)0).put(original,12,original.length-12);
        b.putInt(4,b.capacity()-8);var result=PreparedWaveAudio.inspect(audio(b.array()));
        assertThat(result.dataOffset()).isEqualTo(54);assertThat(result.durationMs()).isEqualTo(1000);
    }
    @Test void missingFilesDirectoriesSymlinksAndOversizeFilesFailClosed() throws Exception {
        assertThatThrownBy(()->PreparedWaveAudio.inspect(null)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->PreparedWaveAudio.inspect(folder.resolve("absent.wav"))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->PreparedWaveAudio.inspect(folder)).isInstanceOf(java.io.IOException.class);
        Path source=audio(wave()),link=folder.resolve("linked.wav");Files.createSymbolicLink(link,source);
        assertThatThrownBy(()->PreparedWaveAudio.inspect(link)).isInstanceOf(java.io.IOException.class);
        Path large=folder.resolve("oversize.wav");
        try(var channel=java.nio.channels.FileChannel.open(large,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            channel.position(67108864);channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertThatThrownBy(()->PreparedWaveAudio.inspect(large)).isInstanceOf(java.io.IOException.class);
    }

    @Test void validStereoExtendedFormatAndVeryShortWaveHaveConsistentDuration() throws Exception {
        byte[] stereo=wave();var s=ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN);
        s.putShort(22,(short)2).putInt(28,96000).putShort(32,(short)4);
        var verified=PreparedWaveAudio.inspect(audio(stereo));assertThat(verified.channels()).isEqualTo(2);assertThat(verified.durationMs()).isEqualTo(500);
        byte[] original=wave();var extended=ByteBuffer.allocate(original.length+2).order(ByteOrder.LITTLE_ENDIAN);
        extended.put(original,0,36).putShort((short)0).put(original,36,original.length-36).putInt(4,extended.capacity()-8).putInt(16,18);
        assertThat(PreparedWaveAudio.inspect(audio(extended.array())).dataOffset()).isEqualTo(46);
        extended.putShort(36,(short)1);Path nonzero=audio(extended.array());assertThatThrownBy(()->PreparedWaveAudio.inspect(nonzero)).isInstanceOf(java.io.IOException.class);
        byte[] tiny=Arrays.copyOf(wave(),46);ByteBuffer.wrap(tiny).order(ByteOrder.LITTLE_ENDIAN).putInt(4,38).putInt(40,2);
        assertThat(PreparedWaveAudio.inspect(audio(tiny)).durationMs()).isEqualTo(1);
    }
    @Test void duplicateDataMissingRequiredChunksPartialChunkAndTooManyChunksFail() throws Exception {
        byte[] original=wave();var duplicate=ByteBuffer.allocate(original.length+10).order(ByteOrder.LITTLE_ENDIAN);
        duplicate.put(original).put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(2).putShort((short)0).putInt(4,duplicate.capacity()-8);
        var missingFmt=original.clone();System.arraycopy("JUNK".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,missingFmt,12,4);
        var missingData=original.clone();System.arraycopy("JUNK".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,missingData,36,4);
        byte[] partial=Arrays.copyOf(original,original.length+1);ByteBuffer.wrap(partial).order(ByteOrder.LITTLE_ENDIAN).putInt(4,partial.length-8);
        var many=ByteBuffer.allocate(original.length+257*8).order(ByteOrder.LITTLE_ENDIAN);many.put(original,0,12);
        for(int i=0;i<257;i++)many.put("JUNK".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(0);
        many.put(original,12,original.length-12).putInt(4,many.capacity()-8);
        for(var bytes:List.of(duplicate.array(),missingFmt,missingData,partial,many.array(),changed(24,-1,4))) {
            Path p=audio(bytes);assertThatThrownBy(()->PreparedWaveAudio.inspect(p)).isInstanceOf(java.io.IOException.class);
        }
    }
    @Test void durationLimitIsIndependentOfMaximumFileSize() throws Exception {
        int dataSize=8000*2*1801;byte[] header=Arrays.copyOf(wave(),44);
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4,dataSize+36).putInt(24,8000).putInt(28,16000).putInt(40,dataSize);
        Path p=folder.resolve("too-long.wav");
        try(var channel=java.nio.channels.FileChannel.open(p,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(header));channel.position(dataSize+43L);channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertThatThrownBy(()->PreparedWaveAudio.inspect(p)).isInstanceOf(java.io.IOException.class);
    }
}
