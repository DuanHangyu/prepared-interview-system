package system.assessment.defense.prepared.audio;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.jobs.PreparedJobLedger;
import system.assessment.defense.prepared.media.*;
import system.assessment.defense.prepared.speech.PreparedSpeechSpool;
import system.assessment.defense.prepared.storage.*;
import java.nio.file.Path;
import java.util.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind;

/** Non-paid whole-question assembly. Storage retry never invokes TTS or creates paid jobs. */
@Component
public class PreparedAudioAssembler {
    private final PreparedJobLedger jobs;private final PreparedAudioService service;private final PreparedPrivateOssStore store;
    private final PreparedStoragePolicy policy;private final PreparedSpeechSpool spool;private final Environment env;
    public PreparedAudioAssembler(PreparedJobLedger jobs,PreparedAudioService service,PreparedPrivateOssStore store,
                                  PreparedStoragePolicy policy,PreparedSpeechSpool spool,Environment env){
        this.jobs=jobs;this.service=service;this.store=store;this.policy=policy;this.spool=spool;this.env=env;
    }
    @Scheduled(scheduler="preparedPreparationScheduler",initialDelay=30000,fixedDelay=3000) public void runOnce(){
        if(!Boolean.parseBoolean(env.getProperty("prepared.speech.assembly-worker-enabled","false")))return;
        var next=jobs.claim(Kind.ASSEMBLE,"audio-assembler",300);if(next.isEmpty())return;var lease=next.get();
        List<PreparedPrivateOssStore.Stored> parts;PreparedAudio.WholeInput input;
        try{input=PreparedAudio.whole(lease.job().inputJson());parts=service.assemblyParts(lease);
            if(parts.size()!=input.orderedPartJobs().size())throw new IllegalArgumentException();}
        catch(RuntimeException error){jobs.fail(lease,"ASSEMBLY_BINDING_INVALID",false);return;}
        try{
            if(!jobs.heartbeat(lease,300))return;var storage=policy.snapshot();Path target=spool.stage(lease);List<Path> files=new ArrayList<>();
            for(int i=0;i<parts.size();i++){
                if(!jobs.heartbeat(lease,300))return;Path part=target.getParent().resolve("part-"+i+".wav");
                if(!store.download(parts.get(i),part).equals(parts.get(i).proof()))throw new IllegalArgumentException();files.add(part);
            }
            if(!jobs.heartbeat(lease,300))return;var proof=PreparedWaveComposer.compose(files,target);
            if(!jobs.heartbeat(lease,300))return;var scope=new PreparedPrivateOssStore.Broadcast(lease.job().assessmentId(),input.questionId(),input.assetId());
            var saved=store.putBroadcast(scope,target);
            if(!scope.equals(saved.scope()) || !scope.key(storage.deploymentId()).equals(saved.objectKey()) || !proof.equals(saved.proof()))throw new IllegalArgumentException();
            service.complete(lease,saved);
            try{spool.releaseComposition(lease,target,parts.size());}catch(java.io.IOException ignored){ /* Durable proof is complete; only owned staging cleanup remains. */ }
        }catch(Exception error){jobs.fail(lease,"ASSEMBLY_SAVE_FAILED",true);}
    }
}
