package system.assessment.defense.prepared.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Internal worker-only decoder. Each original recorder is decoded separately; only verified PCM
 * is composed. Process isolation/RSS quotas must additionally be verified in the deployment. */
public final class PreparedAnswerMedia {
    public record SegmentInput(String id,int ordinal,String mimeType,Path file,long declaredDurationMs) {}
    public record SegmentProof(String id,int ordinal,long durationMs,String sourceSha256) {}
    public record Proof(PreparedWaveAudio.Verified audio,List<SegmentProof> segments) {public Proof{segments=List.copyOf(segments);}}
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Path ffmpeg,ffprobe;
    private final Duration timeout;
    private static final Semaphore DECODERS=new Semaphore(2,true);
    public PreparedAnswerMedia(Path ffmpeg,Path ffprobe) {
        this(ffmpeg,ffprobe,Duration.ofSeconds(90));
    }
    public PreparedAnswerMedia(Path ffmpeg,Path ffprobe,Duration timeout) {
        this.ffmpeg=binary(ffmpeg,"ffmpeg");this.ffprobe=binary(ffprobe,"ffprobe");
        if(timeout==null||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(90))>0)throw new IllegalArgumentException("录音核验超时范围无效");this.timeout=timeout;
    }
    public Proof verify(List<SegmentInput> inputs,int seconds,Path target)throws IOException {
        if(inputs==null||inputs.isEmpty()||inputs.size()>1000||seconds<1||seconds>1800||!newTarget(target))throw invalid();
        if(!DECODERS.tryAcquire())throw new IOException("录音媒体核验繁忙，原内容保留待重试");
        var outputs=new ArrayList<Path>();var proofs=new ArrayList<SegmentProof>();long bytes=0,duration=0;var ids=new HashSet<String>();
        // Whole-operation wall budget, not 90 seconds multiplied by 1000 recorder segments.
        long expires=System.nanoTime()+timeout.toNanos();
        try {
            for(int i=0;i<inputs.size();i++) {
                var input=inputs.get(i);
                if(input==null||input.id()==null||!input.id().matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                        ||!ids.add(input.id())||input.ordinal()!=i+1||input.declaredDurationMs()<1||input.declaredDurationMs()>(long)seconds*1000
                        ||!regular(input.file()))throw invalid();
                bytes+=Files.size(input.file());if(bytes>67108864)throw invalid();String demuxer=demuxer(input.mimeType());
                String sourceHash=hash(input.file());
                var probe=JSON.readTree(run(ffprobe,List.of("-v","error","-max_alloc","16777216","-protocol_whitelist","file",
                        "-probesize","1048576","-analyzeduration","3000000","-f",demuxer,"-i",input.file().toString(),
                        "-show_entries","format=format_name:stream=codec_type,codec_name,channels,sample_rate","-of","json"),expires));
                var streams=probe.path("streams");
                if(!streams.isArray()||streams.size()!=1)throw invalid();var stream=streams.get(0);
                String expectedCodec="mov".equals(demuxer)?"aac":"opus";
                if(!"audio".equals(stream.path("codec_type").asText())||!expectedCodec.equals(stream.path("codec_name").asText())
                        ||stream.path("channels").asInt()<1||stream.path("channels").asInt()>2
                        ||stream.path("sample_rate").asInt()<8000||stream.path("sample_rate").asInt()>48000)throw invalid();
                Path decoded=target.getParent().resolve(target.getFileName()+".segment-"+i+".wav");if(!newTarget(decoded))throw invalid();
                outputs.add(decoded);
                byte[] errors=run(ffmpeg,List.of("-nostdin","-hide_banner","-v","error","-xerror","-max_alloc","16777216",
                        "-threads","1","-protocol_whitelist","file","-probesize","1048576","-analyzeduration","3000000",
                        "-err_detect","explode","-f",demuxer,"-i",input.file().toString(),"-map","0:0","-vn","-sn","-dn",
                        "-ac","1","-ar","16000","-c:a","pcm_s16le","-threads","1","-fs","67108864","-bitexact","-f","wav","-n",decoded.toString()),expires);
                if(errors.length!=0)throw invalid();Files.setPosixFilePermissions(decoded,PosixFilePermissions.fromString("rw-------"));
                var proof=PreparedWaveAudio.inspect(decoded);duration+=proof.durationMs();
                // Declared timing is untrusted evidence, not an offline authenticity guarantee. A large
                // mismatch requires review, never quiet padding or acceptance of a playable prefix.
                if(Math.abs(proof.durationMs()-input.declaredDurationMs())>1000||duration>(long)seconds*1000
                        ||!sourceHash.equals(hash(input.file())))throw invalid();
                proofs.add(new SegmentProof(input.id(),input.ordinal(),proof.durationMs(),sourceHash));
            }
            var complete=compose(outputs,target);
            if(complete.durationMs()>(long)seconds*1000)throw invalid();
            Files.setPosixFilePermissions(target,PosixFilePermissions.fromString("rw-------"));
            return new Proof(complete,proofs);
        }catch(IOException|RuntimeException failure){throw invalid();}
        finally{DECODERS.release();}
        // Originals and failure artifacts are never removed here; owned successful scratch cleanup
        // is the worker's responsibility after the durable receipt commits.
    }
    private static PreparedWaveAudio.Verified compose(List<Path> parts,Path target)throws IOException {
        // Composer's public broadcast API is bounded to 50. Answer recorder sequences can have 1000;
        // its shared PCM writer retains a separate bound without changing broadcast callers.
        return PreparedWaveComposer.composeAnswer(parts,target);
    }
    private static String demuxer(String mime)throws IOException {
        if(mime==null)throw invalid();return switch(mime){
            case "audio/webm","audio/webm;codecs=opus"->"matroska";
            case "audio/ogg","audio/ogg;codecs=opus"->"ogg";
            case "audio/mp4"->"mov";default->throw invalid();};
    }
    private static boolean regular(Path path)throws IOException {
        if(path==null||!path.isAbsolute()||!path.normalize().equals(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)<1||Files.size(path)>67108864)return false;
        for(var p=path.getParent();p!=null;p=p.getParent())if(!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))return false;return true;
    }
    private static boolean newTarget(Path target)throws IOException {
        if(target==null||!target.isAbsolute()||!target.normalize().equals(target)||Files.exists(target,LinkOption.NOFOLLOW_LINKS))return false;
        for(var p=target.getParent();p!=null;p=p.getParent())if(!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))return false;return true;
    }
    private static Path binary(Path path,String name){
        try{if(path==null||!path.isAbsolute()||!path.normalize().equals(path)||!name.equals(path.getFileName().toString())
                    ||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!Files.isExecutable(path))throw new IllegalArgumentException();
            return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        }catch(IOException|RuntimeException unavailable){throw new IllegalArgumentException("录音核验工具必须是受部署管理的本地可执行文件");}
    }
    private static byte[] run(Path executable,List<String> args,long expires)throws IOException {
        Process process=null;var executor=Executors.newVirtualThreadPerTaskExecutor();
        try {
            var command=new ArrayList<String>();command.add(executable.toString());command.addAll(args);
            var builder=new ProcessBuilder(command).redirectErrorStream(true);builder.environment().clear();builder.environment().put("LANG","C");
            process=builder.start();process.getOutputStream().close();var running=process;
            var output=executor.submit(()->{try(var stream=running.getInputStream();var bytes=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int read;while((read=stream.read(buffer))!=-1){if(bytes.size()+read>65536){running.destroyForcibly();throw invalid();}bytes.write(buffer,0,read);}return bytes.toByteArray();}});
            long remaining=expires-System.nanoTime();
            if(remaining<=0||!process.waitFor(remaining,TimeUnit.NANOSECONDS)||process.exitValue()!=0)throw invalid();
            return output.get(1,TimeUnit.SECONDS);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw invalid();}
        catch(ExecutionException|TimeoutException failure){throw invalid();}
        finally{if(process!=null){process.descendants().forEach(p->p.destroyForcibly());if(process.isAlive())process.destroyForcibly();}executor.shutdownNow();}
    }
    private static String hash(Path file)throws IOException {
        try{var digest=java.security.MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            byte[] bytes=new byte[8192];long size=0;int read;while((read=in.read(bytes))!=-1){size+=read;if(size>67108864)throw invalid();digest.update(bytes,0,read);}}
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("摘要算法不可用");}
    }
    private static IOException invalid(){return new IOException("原录音媒体、完整性或时长需核验，请保留原内容");}
}
