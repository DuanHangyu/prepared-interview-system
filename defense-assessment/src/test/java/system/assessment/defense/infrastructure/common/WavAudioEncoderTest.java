package system.assessment.defense.infrastructure.common;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WavAudioEncoderTest {

    @Test
    void createsACompleteMono16BitHeaderForTheRequestedSampleRate() {
        byte[] pcm = {1, 2, 3, 4};

        byte[] wav = WavAudioEncoder.encodeMono16Bit(pcm, 16_000);
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);

        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(header.getInt(24)).isEqualTo(16_000);
        assertThat(header.getInt(28)).isEqualTo(32_000);
        assertThat(header.getShort(34)).isEqualTo((short) 16);
        assertThat(header.getInt(40)).isEqualTo(pcm.length);
        assertThat(wav).hasSize(44 + pcm.length).endsWith(pcm);
    }
}
