package system.assessment.defense.infrastructure.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class WavAudioEncoder {

    private static final int HEADER_SIZE = 44;
    private static final short CHANNELS = 1;
    private static final short BITS_PER_SAMPLE = 16;

    private WavAudioEncoder() {
    }

    public static byte[] encodeMono16Bit(byte[] pcmData, int sampleRate) {
        if (pcmData == null) {
            throw new IllegalArgumentException("PCM data cannot be null");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("Sample rate must be positive");
        }
        int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + pcmData.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + pcmData.length);
        buffer.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort(CHANNELS);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * blockAlign);
        buffer.putShort((short) blockAlign);
        buffer.putShort(BITS_PER_SAMPLE);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(pcmData.length);
        buffer.put(pcmData);
        return buffer.array();
    }
}
