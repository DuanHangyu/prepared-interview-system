package system.assessment.defense.infrastructure.migration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Local validation subprocess protocol. Admin credentials never leave this process. */
public final class OwnedPreparedValidationMysql {
    private OwnedPreparedValidationMysql(){}
    public static void main(String[] args) {
        try {
            if(args.length!=1)throw new IllegalArgumentException("Invalid control directory");
            Path project=Path.of("..").toRealPath(),run=Path.of(args[0]);
            if(!project.getFileName().toString().equals("prepared-interview-system")||!run.isAbsolute()||!run.equals(run.toRealPath())
                ||!run.getParent().equals(project.resolve(".runtime"))||!run.getFileName().toString().matches("validation-[A-Za-z0-9]{6}")
                ||!Files.getPosixFilePermissions(run).equals(PosixFilePermissions.fromString("rwx------"))
                ||!Files.getOwner(run).equals(Files.getOwner(project)))throw new IllegalArgumentException("Invalid control directory");
            Path descriptor=run.resolve("database.json");
            if(Files.exists(descriptor,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Descriptor already exists");
            try(var fixture=OwnedMigrationMysql.openForPreparedValidation();var input=new BufferedReader(new InputStreamReader(System.in))) {
                String nonce=UUID.randomUUID().toString();
                String json="{\"schemaVersion\":1,\"url\":"+quote(fixture.applicationUrl())+",\"username\":"+quote(fixture.applicationUsername())
                    +",\"password\":"+quote(fixture.applicationPassword())+",\"data\":"+quote(fixture.data().toString())+",\"pid\":"+fixture.pid()
                    +",\"ownerPid\":"+ProcessHandle.current().pid()+",\"nonce\":"+quote(nonce)+"}\n";
                Files.createFile(descriptor,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(descriptor,json,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                Path browser=run.resolve("browser-database.json");
                String browserJson="{\"schemaVersion\":1,\"nativePid\":"+fixture.pid()+",\"ownerPid\":"+ProcessHandle.current().pid()
                    +",\"nativePort\":"+java.net.URI.create(fixture.applicationUrl().substring(5)).getPort()+",\"nonce\":"+quote(nonce)+"}\n";
                Files.createFile(browser,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(browser,browserJson,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                System.out.println("READY");System.out.flush();
                String command;
                while((command=input.readLine())!=null) {
                    if(command.equals("CHECK")){fixture.checkApplicationIdentity();System.out.println("CHECKED");System.out.flush();}
                    else if(command.equals("STOP")){fixture.close();System.out.println("STOPPED");System.out.flush();return;}
                    else throw new IllegalArgumentException("Invalid control command");
                }
                // EOF also closes only the process handle created above; retained files are not erased.
            }
        }catch(Exception failure){System.err.println("OWNED_DATABASE_FAILED");System.exit(1);}
    }
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
}
