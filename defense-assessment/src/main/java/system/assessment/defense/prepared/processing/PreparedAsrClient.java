package system.assessment.defense.prepared.processing;

import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.io.IOException;
import java.net.URI;

public interface PreparedAsrClient {
    record Poll(String taskId,String status,Long seconds,URI resultUri) {
        @Override public String toString(){return "Poll[status="+status+", usageKnown="+(seconds!=null)+", resultAvailable="+(resultUri!=null)+"]";}
    }
    String submit(PreparedAsrPolicy.Snapshot policy,PreparedPrivateOssStore.Preview original,PreparedPrivateOssStore.StoredAnswer media,String apiKey)throws IOException;
    Poll poll(PreparedAsrPolicy.Snapshot policy,String taskId,String apiKey)throws IOException;
    PreparedProcessing.Transcript download(URI result,PreparedAsrPolicy.Snapshot policy,PreparedPrivateOssStore.StoredAnswer media,String inputUrlSha256)throws IOException;
}
