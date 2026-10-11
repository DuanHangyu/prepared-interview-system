package system.assessment.defense.prepared.retention;

import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.processing.PreparedProcessingCodec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** An irreversible execution INTENT, not an object-delete capability or proof of
 * exit. No storage IO is exposed. Future IO must revalidate this generation and
 * every protection, record bounded per-object progress and verify all copies. */
public final class PreparedAudioRetirementExecution {
    private PreparedAudioRetirementExecution() {}
    public record Receipt(String id,int studentId,int assessmentId,String attemptId,String preparationId,
            String idempotencyKey,long expectedRevision,long revision,String startedAt,String evidenceSha256,
            String inventorySha256,int objectCount,long byteSize) {
        public Receipt {
            PreparedJobInput.uuid(id);PreparedJobInput.uuid(attemptId);PreparedJobInput.uuid(preparationId);PreparedJobInput.uuid(idempotencyKey);
            if(studentId<1||assessmentId<1||expectedRevision<1||expectedRevision>=9007199254740991L||revision!=expectedRevision+1
                ||objectCount<1||objectCount>500050||byteSize<46||byteSize>500050L*67108864L
                ||evidenceSha256==null||!evidenceSha256.matches("[a-f0-9]{64}")||inventorySha256==null||!inventorySha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("原录音退出代次绑定无效");
            try {var time=Instant.parse(startedAt);if(!time.truncatedTo(ChronoUnit.MILLIS).toString().equals(startedAt))throw new IllegalArgumentException("原录音退出时间无效");}
            catch(java.time.DateTimeException|NullPointerException failure){throw new IllegalArgumentException("原录音退出时间无效");}
        }
    }
    // EXACT preparation-v2 digest. Old v1 receipts are not silently upgraded into
    // verified physical-object evidence. No locator is copied into this receipt.
    static String evidenceHash(List<?> bindings,PreparedOriginalAudioInventory.Snapshot physical,Instant due){
        if(bindings==null||bindings.isEmpty()||bindings.size()>1000||physical==null||due==null)throw new IllegalArgumentException("原录音退出依据不完整");
        return PreparedJobInput.digest(PreparedProcessingCodec.encode(Map.of("schemaVersion",2,"audioDueAt",due.toString(),
            "bindings",bindings,"physicalObjectCount",physical.objects().size(),"physicalByteSize",physical.byteSize(),"physicalInventorySha256",physical.sha256())));
    }
}
