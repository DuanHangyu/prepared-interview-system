package system.assessment.defense.prepared.generation;

import java.io.IOException;

public interface PreparedGenerationClient {
    record Reply(String providerId,long inputTokens,long outputTokens,String content,String finishReason) {}
    Reply generate(PreparedGenerationPolicy.Snapshot policy,PreparedGeneration.Command command,String apiKey) throws IOException;
}
