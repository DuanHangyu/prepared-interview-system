package system.assessment.defense.prepared.processing;

import java.math.BigDecimal;
import java.util.List;

/** Internal model/result contracts. Not a student projection or proof of provider integration. */
public final class PreparedProcessing {
    private PreparedProcessing() {}
    public record Sentence(long startMs,long endMs,String text) {}
    public record Transcript(int schemaVersion,String provider,String model,String audioSha256,long durationMs,List<Sentence> sentences) {
        public Transcript {if(sentences!=null)sentences=List.copyOf(sentences);}
    }
    public record Evidence(int sentenceOrdinal,String quote) {}
    public record Score(int schemaVersion,String questionId,String transcriptId,String transcriptSha256,int maxScore,
                        BigDecimal score,String feedback,List<Evidence> evidence) {
        public Score {if(evidence!=null)evidence=List.copyOf(evidence);if(score!=null)score=score.stripTrailingZeros();}
    }
    public record Result(String id,String jobId,String answerId,String kind,int version,String inputSha256,
                         String contentSha256,String content,String sourceResultId,String completedAt,String provider,String model,String configSha256) {}
    public record Receipt(int schemaVersion,String resultId,String answerId,String kind,int version,String contentSha256,boolean effective) {}
    public record RegradeCommand(String idempotencyKey,long expectedRevision,String transcriptId,String reason) {}
    public record ConfirmCommand(String idempotencyKey,long expectedRevision,String scoreId,String reason) {}
    public record AdjustCommand(String idempotencyKey,long expectedRevision,BigDecimal score,String reason) {
        public AdjustCommand {if(score!=null)score=score.stripTrailingZeros();}
    }
    /** Explicit human provenance; never populate provider/model or invent transcript evidence. */
    public record ManualScore(String id,String answerId,int version,long revision,String questionId,int maxScore,
                              BigDecimal score,String reason,int recordedBy,String recordedAt,String audioSha256,
                              String transcriptId,String scoreId,String previousManualScoreId) {
        public ManualScore {if(score!=null)score=score.stripTrailingZeros();}
    }
    public record View(String answerId,long revision,String transcriptId,String scoreId,boolean confirmed,List<Result> history,String manualScoreId) {
        public View {history=List.copyOf(history);}
    }
}
