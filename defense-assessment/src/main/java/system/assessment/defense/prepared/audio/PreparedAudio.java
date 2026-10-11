package system.assessment.defense.prepared.audio;

import jakarta.validation.constraints.*;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.speech.PreparedSpeech;
import system.assessment.defense.prepared.speech.PreparedTtsPolicy;
import java.math.BigDecimal;
import java.util.List;

/** Teacher-facing descriptors deliberately exclude object keys, task payloads and credentials. */
public final class PreparedAudio {
    private PreparedAudio() {}
    public record Command(@NotNull @Min(0) Long expectedRevision,@NotBlank String idempotencyKey,
                          @NotBlank String questionId,@NotBlank String expectedConfigurationSha256) {}
    public record Recovery(@NotNull @Min(0) Long expectedRevision,@NotBlank String idempotencyKey) {}
    public record Availability(boolean enabled,String reason,String confirmationSha256,String model,String voice,
                               BigDecimal maximumPartCostCny) {}
    public record View(String id,String questionId,String bodySha256,String jobId,String state,int partCount,
                       int completedParts,int newPartCount,BigDecimal maximumNewCostCny,String errorCode,
                       boolean assemblyRecoverable,Long byteSize,Long durationMs,String createdAt) {}
    public record Listing(int assessmentId,long draftRevision,boolean frozen,Availability availability,List<View> current,List<View> history) {
        public Listing {current=List.copyOf(current);history=List.copyOf(history);}
    }
    public record WholeInput(int schemaVersion,String questionId,String assetId,String body,String bodySha256,
                             String configSha256,List<String> orderedPartJobs) {
        public WholeInput {orderedPartJobs=orderedPartJobs==null?null:List.copyOf(orderedPartJobs);}
        @Override public String toString(){return "WholeAudio[assetId="+assetId+", private text redacted]";}
        public void validate(){
            PreparedJobInput.uuid(questionId);PreparedJobInput.uuid(assetId);sha(bodySha256);sha(configSha256);
            if(schemaVersion!=1 || body==null || body.isBlank() || body.length()>10000 || !PreparedJobInput.digest(body).equals(bodySha256)
                    || orderedPartJobs==null || orderedPartJobs.isEmpty() || orderedPartJobs.size()>50
                    || orderedPartJobs.stream().distinct().count()!=orderedPartJobs.size())throw new IllegalArgumentException("整题播报输入无效");
            orderedPartJobs.forEach(PreparedJobInput::uuid);
        }
    }
    public static String configuration(PreparedTtsPolicy.Snapshot policy){
        return PreparedJobInput.digest(PreparedSpeech.encode(List.of(1,policy.endpoint(),policy.model(),policy.voice(),policy.language())));
    }
    public static String sha(String value){if(value==null || !value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("播报摘要无效");return value;}
    public static WholeInput whole(String json){
        try{var input=new com.fasterxml.jackson.databind.ObjectMapper().readValue(PreparedJobInput.json(json,65536),WholeInput.class);input.validate();return input;}
        catch(java.io.IOException|NullPointerException error){throw new IllegalArgumentException("整题播报输入无效");}
    }
}
