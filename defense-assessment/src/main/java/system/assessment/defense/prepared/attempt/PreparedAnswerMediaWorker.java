package system.assessment.defense.prepared.attempt;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.jobs.PreparedJobLedger;
import system.assessment.defense.prepared.media.PreparedAnswerMedia;
import system.assessment.defense.prepared.storage.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.Kind;

/** Non-paid student media verification, isolated from teacher ASSEMBLE jobs and model queues. */
@Component
public class PreparedAnswerMediaWorker {
    private final PreparedJobLedger jobs;private final PreparedAnswerMediaService service;private final PreparedAnswerMediaRuntime runtime;
    private final PreparedPrivateOssStore store;private final PreparedStoragePolicy policy;
    public PreparedAnswerMediaWorker(PreparedJobLedger jobs,PreparedAnswerMediaService service,PreparedAnswerMediaRuntime runtime,PreparedPrivateOssStore store,PreparedStoragePolicy policy){
        this.jobs=jobs;this.service=service;this.runtime=runtime;this.store=store;this.policy=policy;
    }
    @Scheduled(scheduler="preparedMediaScheduler",initialDelay=30000,fixedDelay=3000) public void runOnce(){
        if(!runtime.available()||!policy.availability().enabled())return;
        var next=jobs.claim(Kind.ANSWER_MEDIA,"answer-media-worker",300);if(next.isEmpty())return;var lease=next.orElseThrow();
        PreparedAnswerMediaService.Plan plan;
        try{plan=service.plan(lease);}
        catch(PreparedUploadWindow.Expired expired){jobs.fail(lease,PreparedJobLedger.ANSWER_MEDIA_UPLOAD_WINDOW_EXPIRED,false);return;}
        catch(RuntimeException unavailable){jobs.fail(lease,"ANSWER_MEDIA_BINDING_INVALID",false);return;}
        system.assessment.defense.prepared.retention.PreparedOriginalAudioIoJournal.Handle io=null;
        try {
            io=service.beginMediaIo(lease,plan);
            Path directory=runtime.stage(lease);var physical=new HashMap<String,Path>();var groups=new HashMap<String,PreparedAnswerBundleRepository.Row>();
            for(var group:plan.bundles())groups.put(group.scope().id(),group);
            var inputs=new ArrayList<PreparedAnswerMedia.SegmentInput>();int locationIndex=0,objectIndex=0;
            for(var segment:plan.manifest().segments()) {
                if(!jobs.heartbeat(lease,300))return;
                Path original=directory.resolve("segment-"+segment.ordinal()+".bin");
                try(var output=Files.newOutputStream(original,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                    Files.setPosixFilePermissions(original,PosixFilePermissions.fromString("rw-------"));
                    for(var declared:segment.chunks()) {
                        if(!jobs.heartbeat(lease,300))return;
                        var location=plan.locations().get(locationIndex++);var scope=location.row().scope();Path bytes;long offset=0;
                        if(location.bundleId()!=null) {
                            bytes=physical.get(location.bundleId());offset=location.offset();
                            if(bytes==null){var group=groups.get(location.bundleId());bytes=directory.resolve("object-"+objectIndex+++".bin");
                                store.downloadAnswerBundle(new PreparedPrivateOssStore.StoredBundle(group.scope(),group.key()),bytes);physical.put(location.bundleId(),bytes);}
                        }else {
                            bytes=directory.resolve("object-"+objectIndex+++".bin");store.downloadAnswerChunk(new PreparedPrivateOssStore.StoredChunk(scope,location.row().objectKey()),bytes);
                        }
                        copyOriginal(bytes,offset,declared.byteSize(),declared.sha256(),output);
                    }
                }
                if(Files.size(original)!=segment.byteSize())throw new IOException("原分段大小无法核验");
                inputs.add(new PreparedAnswerMedia.SegmentInput(segment.id(),segment.ordinal(),segment.mimeType(),original,segment.endedAt()-segment.startedAt()));
            }
            if(!jobs.heartbeat(lease,300))return;Path target=directory.resolve("answer.wav");PreparedAnswerMedia.Proof proof;
            try{proof=runtime.decoder().verify(inputs,plan.answerSeconds(),target);}
            catch(IOException invalid){jobs.fail(lease,"ANSWER_MEDIA_INVALID",false);return;}
            if(!jobs.heartbeat(lease,300))return;var input=plan.input();
            var scope=new PreparedPrivateOssStore.AnswerFile(input.assessment(),input.student(),input.attempt(),input.answer(),input.manifestSha256());
            var saved=store.putAnswer(scope,target);
            service.complete(lease,saved,proof);
            try{runtime.releaseSuccess(lease,directory);}catch(IOException ignored){/* Only owned successful scratch remains; durable original receipts are untouched. */}
        }catch(PreparedUploadWindow.Expired expired){jobs.fail(lease,PreparedJobLedger.ANSWER_MEDIA_UPLOAD_WINDOW_EXPIRED,false);}
        catch(Exception failure){jobs.fail(lease,"ANSWER_MEDIA_SAVE_FAILED",true);}
        finally{if(io!=null)service.endMediaIo(io);}
    }
    /** Exact bundle range or original single chunk, each rehashed. No raw cross-recorder joining. */
    private static void copyOriginal(Path file,long offset,int size,String expected,OutputStream output)throws IOException {
        if(offset<0||size<1||size>2097152||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||offset+size>Files.size(file))throw new IOException("原字节范围无法核验");
        MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("摘要算法不可用");}
        try(var channel=Files.newByteChannel(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            channel.position(offset);byte[] buffer=new byte[8192];int remaining=size;
            while(remaining>0){int read=channel.read(java.nio.ByteBuffer.wrap(buffer,0,Math.min(buffer.length,remaining)));if(read<1)throw new IOException("原录音缺片");digest.update(buffer,0,read);output.write(buffer,0,read);remaining-=read;}
        }
        if(!HexFormat.of().formatHex(digest.digest()).equals(expected))throw new IOException("原录音字节摘要不符");
    }
}
