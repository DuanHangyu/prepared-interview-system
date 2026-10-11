package system.assessment.defense.infrastructure.migration;

import system.assessment.defense.prepared.bootstrap.PreparedLocalMysqlBootstrap;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Test-only fresh native server. Never adopts/rotates an existing database or
 * forwards caller administrator credentials. Only our Process handle is stopped. */
public final class OwnedMigrationMysql implements AutoCloseable {
    private final Path root,base,data,admin;private final String url,password,schema,appPassword;private final Process server;
    private boolean closed;
    private OwnedMigrationMysql(Path root,Path base,PreparedLocalMysqlBootstrap.Result initialized,String url,String password,String schema,String appPassword,Process server){
        this.root=root;this.base=base;this.data=initialized.data();this.admin=initialized.adminConfiguration();this.url=url;this.password=password;this.server=server;
        this.schema=schema;this.appPassword=appPassword;
    }
    public static OwnedMigrationMysql openIfEnabled()throws Exception{
        String enabled=System.getenv("PREPARED_OWNED_MYSQL_MIGRATION_TESTS");
        if(enabled==null||enabled.equals("false"))return null;
        if(!enabled.equals("true"))throw new IllegalArgumentException("Invalid owned migration mode");return open();
    }
    public static OwnedMigrationMysql open()throws Exception{
        return open("prepared_interview_owned");
    }
    public static OwnedMigrationMysql openForPreparedValidation()throws Exception{
        return open("prepared_interview");
    }
    private static OwnedMigrationMysql open(String schema)throws Exception{
        Path project=Path.of("..").toRealPath(),runtime=project.resolve(".runtime");
        if(!project.getFileName().toString().equals("prepared-interview-system")||Files.isSymbolicLink(runtime)
            ||!Files.isDirectory(runtime,LinkOption.NOFOLLOW_LINKS)
            ||!Files.getPosixFilePermissions(runtime).equals(PosixFilePermissions.fromString("rwx------")))throw new IllegalArgumentException("Private NEW runtime required");
        Path base=Path.of(Objects.requireNonNull(System.getenv("PREPARED_MYSQL_BASEDIR"))).toRealPath();
        int port;try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
        if(port<1024||port==13317)throw new IllegalStateException("Independent migration port required");
        Path root=Files.createDirectory(runtime.resolve("om-"+UUID.randomUUID().toString().substring(0,8)),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
        privateFile(root.resolve(".env.local"),"SPRING_PROFILE='prepared-local'\n");
        byte[] entropy=new byte[36];new SecureRandom().nextBytes(entropy);String appPassword=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        var config=Map.of("PREPARED_MYSQL_BASEDIR",base.toString(),"PREPARED_MYSQL_PORT",Integer.toString(port),"PREPARED_DB_USERNAME","prepared_app","PREPARED_DB_PASSWORD",appPassword,
            "PREPARED_DB_JDBC_URL","jdbc:mysql://127.0.0.1:"+port+"/"+schema+"?sslMode=REQUIRED&serverTimezone=Asia/Shanghai");
        var initialized=PreparedLocalMysqlBootstrap.initialize(root,config);
        String password=Files.readAllLines(initialized.adminConfiguration()).stream().filter(x->x.startsWith("password=\"")).findFirst().orElseThrow().substring(10).replaceFirst("\"$","");
        if(!password.matches("[a-zA-Z0-9_-]{48}")||password.equals(appPassword))throw new IllegalStateException("Separate fixture identity required");
        Path log=root.resolve("server.log");privateFile(log,"");
        var builder=new ProcessBuilder(base.resolve("bin/mysqld").toString(),"--no-defaults","--basedir="+base,"--datadir="+initialized.data(),
            "--socket="+initialized.data().resolve("mysql.sock"),"--pid-file="+initialized.data().resolve("server.pid"),"--bind-address=127.0.0.1","--port="+port,
            "--mysqlx=OFF","--partial-revokes=ON","--log-error="+log).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();builder.environment().put("PATH","/usr/bin:/bin:/usr/sbin:/sbin");
        Process server=builder.start();String url="jdbc:mysql://127.0.0.1:"+port+"/?sslMode=REQUIRED&serverTimezone=Asia/Shanghai";
        // Prepared application tests also run Flyway. Use only the separately
        // generated, schema-scoped migrator of this fresh server, not root and
        // not widened grants on an existing runtime account.
        String testPassword=schema.equals("prepared_interview")?Files.readAllLines(initialized.migrationConfiguration()).stream()
            .filter(x->x.startsWith("password=\"")).findFirst().orElseThrow().substring(10).replaceFirst("\"$",""):appPassword;
        var fixture=new OwnedMigrationMysql(root,base,initialized,url,password,schema,testPassword,server);
        try{fixture.awaitReady();fixture.checkApplicationIdentity();return fixture;}
        catch(Exception failure){stopOwned(server);throw failure;}
    }
    private void awaitReady()throws Exception{
        long until=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        while(server.isAlive()&&System.nanoTime()<until){
            try(var connection=DriverManager.getConnection(url,username(),password);var statement=connection.createStatement();var rows=statement.executeQuery("SELECT @@datadir,@@partial_revokes")){
                if(!rows.next()||!rows.getString(1).equals(data+"/")||rows.getInt(2)!=1)throw new IllegalStateException("Owned migration identity mismatch");return;
            }catch(SQLException pending){Thread.sleep(200);}
        }throw new IllegalStateException("Owned native migration server not ready");
    }
    public String serverUrl(){return url;}public String username(){return "root";}public String password(){return password;}
    public String applicationUrl(){return url.replace("/?","/"+schema+"?");}
    public String applicationUsername(){return schema.equals("prepared_interview")?"prepared_migrator":"prepared_app";}public String applicationPassword(){return appPassword;}
    public void checkApplicationIdentity()throws Exception{
        if(closed||!server.isAlive())throw new IllegalStateException("Owned native server unavailable");
        if(!Files.readString(data.resolve("server.pid")).strip().equals(Long.toString(server.pid())))throw new IllegalStateException("Owned native process mismatch");
        try(var connection=DriverManager.getConnection(applicationUrl(),applicationUsername(),appPassword);var statement=connection.createStatement();
            var rows=statement.executeQuery("SELECT DATABASE(),@@datadir,@@port,@@partial_revokes,CURRENT_USER()")){
            int port=URI.create(url.substring(5)).getPort();
            if(!rows.next()||!schema.equals(rows.getString(1))||!rows.getString(2).equals(data+"/")||rows.getInt(3)!=port||rows.getInt(4)!=1
                ||!rows.getString(5).startsWith(applicationUsername()+"@"))throw new IllegalStateException("Owned application identity mismatch");
        }
    }
    public Path data(){return data;}public long pid(){return server.pid();}
    @Override public String toString(){return "OwnedMigrationMysql[private identity redacted]";}
    @Override public synchronized void close()throws Exception{
        if(closed)return;
        try{
            if(!server.isAlive())throw new IllegalStateException("Owned migration server stopped unexpectedly");
            try(var connection=DriverManager.getConnection(url,username(),password);var statement=connection.createStatement()){
                try(var identity=statement.executeQuery("SELECT @@datadir,@@partial_revokes")){
                    if(!identity.next()||!identity.getString(1).equals(data+"/")||identity.getInt(2)!=1)throw new IllegalStateException("Owned migration identity changed");
                }
                try(var rows=statement.executeQuery("SELECT schema_name FROM information_schema.schemata")){
                    var expected=Set.of("information_schema","mysql","performance_schema","sys",schema);
                    while(rows.next())if(!expected.contains(rows.getString(1)))throw new IllegalStateException("Owned migration scenario cleanup incomplete");
                }
            }
            var command=new ProcessBuilder(base.resolve("bin/mysqladmin").toString(),"--defaults-file="+admin,"--no-login-paths","--connect-timeout=2","shutdown")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            command.environment().clear();command.environment().put("PATH","/usr/bin:/bin:/usr/sbin:/sbin");Process stop=command.start();
            try{if(!stop.waitFor(20,TimeUnit.SECONDS)||stop.exitValue()!=0||!server.waitFor(20,TimeUnit.SECONDS)||server.exitValue()!=0)throw new IllegalStateException("Owned migration clean shutdown failed");}
            finally{stopOwned(stop);}
            privateFile(root.resolve("stopped-proof.txt"),"owned-migration-v1\n");
        }finally{stopOwned(server);closed=true;}
    }
    private static void stopOwned(Process process)throws Exception{
        if(!process.isAlive())return;var children=process.descendants().toList();for(var child:children.reversed())if(child.isAlive())child.destroy();process.destroy();
        if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();if(!process.waitFor(15,TimeUnit.SECONDS))throw new IllegalStateException("Owned process did not stop");}
        for(var child:children)if(child.isAlive())child.destroyForcibly();
    }
    private static void privateFile(Path file,String content)throws Exception{
        Files.createFile(file,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file,content,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
    }
}
