package system.assessment.defense.prepared.attempt;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.Lease;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.media.PreparedAnswerMedia;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Independent owner-only answer scratch. Never accepts paths from a student or media metadata. */
@Component
public class PreparedAnswerMediaRuntime {
    private final Environment env;
    public PreparedAnswerMediaRuntime(Environment env){this.env=env;}
    public boolean enabled(){return Boolean.parseBoolean(env.getProperty("prepared.answer-media.worker-enabled","false"));}
    public boolean available(){try{if(!enabled())return false;decoder();Path root=root();check(root);Path existing=root;while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=existing.getParent();
        if(!Files.getFileStore(existing).supportsFileAttributeView("posix")||Files.getFileStore(existing).getUsableSpace()<402653184)return false;
        if(Files.exists(root,LinkOption.NOFOLLOW_LINKS))privateDirectory(root);return true;}catch(IOException|RuntimeException unavailable){return false;}}
    public PreparedAnswerMedia decoder(){return new PreparedAnswerMedia(Path.of(env.getProperty("prepared.answer-media.ffmpeg","")),Path.of(env.getProperty("prepared.answer-media.ffprobe","")));}
    public Path stage(Lease lease)throws IOException {
        if(!available())throw invalid();var permissions=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        Path root=root();Files.createDirectories(root,permissions);privateDirectory(root);
        Path job=root.resolve(PreparedJobInput.uuid(lease.job().id()));Files.createDirectories(job,permissions);privateDirectory(job);
        Path generation=job.resolve("generation-"+lease.generation());if(lease.generation()<1)throw invalid();Files.createDirectory(generation,permissions);privateDirectory(generation);return generation;
    }
    public void releaseSuccess(Lease lease,Path directory)throws IOException {
        Path expected=root().resolve(PreparedJobInput.uuid(lease.job().id())).resolve("generation-"+lease.generation());
        if(!expected.equals(directory))throw invalid();check(directory);privateDirectory(directory);
        java.util.List<Path> files;
        try(var entries=Files.list(directory)){files=entries.limit(12003).toList();}
        if(files.size()>12002)throw invalid();
        for(var file:files)if(!file.getFileName().toString().matches("(object-[0-9]+\\.bin|segment-[0-9]+\\.bin|answer\\.wav(\\.segment-[0-9]+\\.wav)?)")||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw invalid();
        for(var file:files)Files.delete(file);Files.delete(directory);
        try(var entries=Files.list(directory.getParent())){if(entries.findAny().isEmpty())Files.delete(directory.getParent());}
    }
    private Path root()throws IOException {Path root=Path.of(env.getProperty("prepared.answer-media.spool-root",""));
        if(!root.isAbsolute()||!root.normalize().equals(root)||root.getFileName()==null||!"prepared-answer-spool".equals(root.getFileName().toString()))throw invalid();return root;}
    private static void check(Path path)throws IOException{for(Path p=path;p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))throw invalid();}
    private static void privateDirectory(Path path)throws IOException{check(path);for(var p:Files.getPosixFilePermissions(path))if(p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_"))throw invalid();}
    private static IOException invalid(){return new IOException("独立录音核验暂存目录不可用，原内容保留");}
}
