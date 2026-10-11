package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Test-only run scope. Full validation never adopts or rotates old fixture identities. */
final class PreparedBrowserFixtureScope {
    private static final Set<String> ROLES=Set.of("teacher","student","unrelated");
    private static final String UUID4="[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}";

    static String namespace(){
        String value=System.getenv("PREPARED_VALIDATION_RUN_ID");
        if("true".equals(System.getenv("PREPARED_OWNED_MYSQL_MIGRATION_TESTS"))&&value==null)
            throw new IllegalArgumentException("Owned validation browser scope required");
        if(value!=null&&!value.matches(UUID4))throw new IllegalArgumentException("Invalid validation browser scope");
        return value;
    }
    static String account(String role,String namespace){
        if(!ROLES.contains(role))throw new IllegalArgumentException("Invalid fixture role");
        if(namespace==null)return "prepared_browser_"+role;
        if(!namespace.matches(UUID4))throw new IllegalArgumentException("Invalid validation browser scope");
        return "prepared_gate_"+role+"_"+namespace.replace("-","");
    }
    static void record(String namespace,int teacher,int student,int unrelated,int assessment){
        if(namespace==null)return;
        account("teacher",namespace);
        if(teacher<=0||student<=0||unrelated<=0||assessment<=0||Set.of(teacher,student,unrelated).size()!=3)
            throw new IllegalArgumentException("Invalid owned fixture identity");
        try{
            Path root=Path.of("..").toRealPath(),runtime=root.resolve(".runtime");
            if(!root.getFileName().toString().equals("prepared-interview-system")||Files.isSymbolicLink(runtime)
                ||!Files.isDirectory(runtime,LinkOption.NOFOLLOW_LINKS)
                ||!Files.getPosixFilePermissions(runtime).equals(PosixFilePermissions.fromString("rwx------")))
                throw new IllegalArgumentException("Private NEW runtime required");
            byte[] bytes=new ObjectMapper().writeValueAsBytes(new TreeMap<>(Map.of(
                "namespace",namespace,"teacherId",teacher,"studentId",student,"unrelatedId",unrelated,"assessmentId",assessment)));
            Path file=runtime.resolve("owned-browser-"+namespace+".json");
            try{Files.createFile(file,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}
            catch(FileAlreadyExistsException existing){
                if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>4096
                    ||!Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------"))
                    ||!Arrays.equals(bytes,Files.readAllBytes(file)))throw new IllegalStateException("Owned fixture receipt mismatch");
                return;
            }
            Files.write(file,bytes,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        }catch(java.io.IOException failure){throw new IllegalStateException("Private fixture receipt unavailable",failure);}
    }
}
