package system.assessment.defense.prepared.speech;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import system.assessment.defense.prepared.media.PreparedWaveAudio;

public interface PreparedTtsClient {
    record Reply(String providerId,long characters,String finishReason,URI audioUri,long expiresAt) {
        @Override public String toString(){return "TtsReply[providerId="+providerId+", characters="+characters+", audioAvailable="+(audioUri!=null)+"]";}
    }
    /** Only a worker with a durable budget reservation and dispatch grant may invoke this paid method. */
    Reply synthesize(PreparedTtsPolicy.Snapshot snapshot,String text,String key) throws IOException;
    PreparedWaveAudio.Verified download(URI audioUri,Path newTarget) throws IOException;
}
