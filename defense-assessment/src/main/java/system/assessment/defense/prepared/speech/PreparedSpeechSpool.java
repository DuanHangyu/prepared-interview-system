package system.assessment.defense.prepared.speech;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.Lease;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Worker-owned POSIX spool. Failure leaves the paid staged file for controlled recovery.
 * Cleanup accepts only this exact job/generation path, never a browser or student-supplied path. */
@Component
public class PreparedSpeechSpool {
    private final Environment env;
    public PreparedSpeechSpool(Environment env){this.env=env;}
    public boolean assemblyEnabled(){return Boolean.parseBoolean(env.getProperty("prepared.speech.assembly-worker-enabled","false"));}
    /** Read-only configuration probe. Actual disk creation is deferred to the worker. */
    public boolean configurationAvailable(){
        try{Path configured=root();checkAncestors(configured);
            Path existing=configured;while(existing!=null && !Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=existing.getParent();
            if(existing==null || !Files.getFileStore(existing).supportsFileAttributeView("posix") || Files.getFileStore(existing).getUsableSpace()<134217728)return false;
            if(Files.exists(configured,LinkOption.NOFOLLOW_LINKS))requirePrivate(configured);return true;
        }catch(IOException|RuntimeException error){return false;}
    }
    public Path stage(Lease lease) throws IOException {
        try {
            Path root=root();checkAncestors(root);
            var permissions=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
            Files.createDirectories(root,permissions);
            if(!Files.getFileStore(root).supportsFileAttributeView("posix") || Files.getFileStore(root).getUsableSpace()<134217728)throw invalid();
            requirePrivate(root);
            Path target=target(root,lease);checkAncestors(target.getParent());Files.createDirectories(target.getParent(),permissions);
            requirePrivate(target.getParent().getParent());requirePrivate(target.getParent());
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw invalid();return target;
        }catch(IOException|RuntimeException error){throw invalid();}
    }
    public void release(Lease lease,Path savedTarget) throws IOException {
        try {
            Path expected=target(root(),lease);checkAncestors(expected.getParent());
            if(!expected.equals(savedTarget) || (Files.exists(expected,LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(expected,LinkOption.NOFOLLOW_LINKS)))throw invalid();
            Files.deleteIfExists(expected);deleteEmpty(expected.getParent());deleteEmpty(expected.getParent().getParent());
        }catch(IOException|RuntimeException error){throw invalid();}
    }
    /** Composition parts have server-defined numbered names in the same private generation. */
    public void releaseComposition(Lease lease,Path savedTarget,int count) throws IOException{
        if(count<1 || count>50)throw invalid();Path expected=target(root(),lease);checkAncestors(expected.getParent());
        if(!expected.equals(savedTarget))throw invalid();
        for(int i=0;i<count;i++){
            Path part=expected.getParent().resolve("part-"+i+".wav");
            if(Files.exists(part,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(part,LinkOption.NOFOLLOW_LINKS))throw invalid();
        }
        for(int i=0;i<count;i++)Files.deleteIfExists(expected.getParent().resolve("part-"+i+".wav"));release(lease,expected);
    }
    private Path root() throws IOException {
        String configured=env.getProperty("prepared.speech.spool-root","");if(configured.isBlank())throw invalid();
        Path path=Path.of(configured);
        if(!path.isAbsolute() || !path.normalize().equals(path) || path.getFileName()==null
                || !"prepared-tts-spool".equals(path.getFileName().toString()))throw invalid();return path;
    }
    private Path target(Path root,Lease lease) throws IOException {
        if(lease==null || lease.job()==null || lease.generation()<1)throw invalid();
        return root.resolve(PreparedJobInput.uuid(lease.job().id())).resolve("generation-"+lease.generation()).resolve("broadcast.wav");
    }
    private static void checkAncestors(Path path) throws IOException {
        for(Path current=path;current!=null;current=current.getParent())
            if(Files.exists(current,LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS))throw invalid();
    }
    private static void deleteEmpty(Path path) throws IOException {
        if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))try(var entries=Files.list(path)){if(entries.findAny().isEmpty())Files.delete(path);}
    }
    private static void requirePrivate(Path path) throws IOException {
        for(var permission:Files.getPosixFilePermissions(path))
            if(permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"))throw invalid();
    }
    private static IOException invalid(){return new IOException("独立播报暂存目录不可用或保存范围无效");}
}
