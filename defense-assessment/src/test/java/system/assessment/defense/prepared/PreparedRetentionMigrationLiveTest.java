package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import system.assessment.defense.prepared.bootstrap.PreparedLocalMysqlBootstrap;
import java.net.ServerSocket;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Runs ALL six existing real migration scenarios, three rounds on a fresh owned
 * native MySQL. No widening current runtime grants or adoption of existing data.
 * The inner six-case reports are separately retained; one outer case is not 18 cases. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedRetentionMigrationLiveTest {
    @Test void freshIsolatedNativeServerExecutesAllMigrationScenariosAcrossThreeRestarts()throws Exception{
        Path project=Path.of("..").toRealPath(),runtime=project.resolve(".runtime");
        Path root=Files.createDirectory(runtime.resolve("rm-"+UUID.randomUUID().toString().substring(0,8)),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
        Path base=Path.of(Objects.requireNonNull(System.getenv("PREPARED_MYSQL_BASEDIR"))).toRealPath();int port;
        try(var available=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){port=available.getLocalPort();}
        byte[] bytes=new byte[36];new SecureRandom().nextBytes(bytes);String appPassword=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String schemaUrl="jdbc:mysql://127.0.0.1:"+port+"/prepared_interview_retention?sslMode=REQUIRED&serverTimezone=Asia/Shanghai";
        var config=Map.of("PREPARED_MYSQL_BASEDIR",base.toString(),"PREPARED_MYSQL_PORT",Integer.toString(port),"PREPARED_DB_USERNAME","prepared_app","PREPARED_DB_PASSWORD",appPassword,"PREPARED_DB_JDBC_URL",schemaUrl);
        privateFile(root.resolve(".env.local"),"SPRING_PROFILE='prepared-local'\n");
        var initialized=PreparedLocalMysqlBootstrap.initialize(root,config);
        String password=Files.readAllLines(initialized.adminConfiguration()).stream().filter(x->x.startsWith("password=\"")).findFirst().orElseThrow().substring(10).replaceFirst("\"$","");
        assertNotEquals(appPassword,password,"Actual isolated administrator credential must be separate");
        String serverUrl="jdbc:mysql://127.0.0.1:"+port+"/?sslMode=REQUIRED&serverTimezone=Asia/Shanghai";
        Path backend=project.resolve("defense-assessment"),maven=backend.resolve("mvnw");
        for(int round=0;round<3;round++){
            Path output=root.resolve("server-"+round+".log");privateFile(output,"");
            var serverBuilder=new ProcessBuilder(base.resolve("bin/mysqld").toString(),"--no-defaults","--basedir="+base,"--datadir="+initialized.data(),"--socket="+initialized.data().resolve("mysql.sock"),"--pid-file="+initialized.data().resolve("server.pid"),"--bind-address=127.0.0.1","--port="+port,"--mysqlx=OFF","--partial-revokes=ON","--log-error="+output).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            serverBuilder.environment().clear();serverBuilder.environment().put("PATH","/usr/bin:/bin:/usr/sbin:/sbin");
            Process server=serverBuilder.start();
            try{
                awaitOwnedServer(serverUrl,password,server,initialized.data());
                Path log=root.resolve("migrations-"+round+".log");privateFile(log,"");
                long since=System.currentTimeMillis();
                var builder=new ProcessBuilder(maven.toString(),"--batch-mode","--no-transfer-progress","test","-Dtest=DatabaseMigrationMySqlIT","-DskipTests=false","-Dmaven.test.skip=false","-Dmaven.test.failure.ignore=false","-Djacoco.destFile="+root.resolve("migration-"+round+".exec")).directory(backend.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
                builder.environment().clear();var env=builder.environment();
                env.put("HOME",System.getenv("HOME"));env.put("PATH",System.getProperty("java.home")+"/bin:/usr/bin:/bin:/usr/sbin:/sbin");env.put("JAVA_HOME",System.getProperty("java.home"));env.put("JAVA_TOOL_OPTIONS","-Xms64m -Xmx512m");
                env.put("MYSQL_INTEGRATION_SERVER_URL",serverUrl);env.put("MYSQL_INTEGRATION_USERNAME","root");env.put("MYSQL_INTEGRATION_PASSWORD",password);
                Process child=builder.start();
                try{assertTrue(child.waitFor(300,TimeUnit.SECONDS),"Owned migration scenarios must complete; inspect private fixture diagnostics");assertEquals(0,child.exitValue(),"All six migration cases must pass; no grants are widened to repair the environment");}
                finally{stopOwned(child);}
                Path report=backend.resolve("target/surefire-reports/TEST-system.assessment.defense.infrastructure.migration.DatabaseMigrationMySqlIT.xml");
                assertTrue(Files.getLastModifiedTime(report).toMillis()>=since,"Fresh inner report required");String xml=Files.readString(report);
                assertTrue(xml.matches("(?s).*<testsuite[^>]* tests=\"6\"[^>]*>.*"));
                for(String field:List.of("failures","errors","skipped"))assertTrue(xml.matches("(?s).*<testsuite[^>]* "+field+"=\"0\"[^>]*>.*"),"No failed or skipped migration case is accepted");
                privateFile(root.resolve("migration-report-"+round+".xml"),xml);
                try(var connection=DriverManager.getConnection(serverUrl,"root",password);var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name LIKE 'ai\\_migration\\_it\\_%'")){assertTrue(rows.next());assertEquals(0,rows.getInt(1),"Only owned scenario schemas must be cleaned");}
                var shutdown=new ProcessBuilder(base.resolve("bin/mysqladmin").toString(),"--defaults-file="+initialized.adminConfiguration(),"--no-login-paths","--connect-timeout=2","shutdown").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
                shutdown.environment().clear();shutdown.environment().put("PATH","/usr/bin:/bin:/usr/sbin:/sbin");Process stop=shutdown.start();
                try{assertTrue(stop.waitFor(20,TimeUnit.SECONDS));assertEquals(0,stop.exitValue());assertTrue(server.waitFor(20,TimeUnit.SECONDS));assertEquals(0,server.exitValue());}finally{stopOwned(stop);}
            }finally{stopOwned(server);}
        }
        // Retain only these private owned fixture files; do not remove current/old data.
    }
    private static void awaitOwnedServer(String url,String password,Process server,Path data)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        while(server.isAlive()&&System.nanoTime()<deadline){
            try(var connection=DriverManager.getConnection(url,"root",password);var statement=connection.createStatement();var rows=statement.executeQuery("SELECT @@datadir,@@partial_revokes")){
                assertTrue(rows.next());assertEquals(data+"/",rows.getString(1));assertEquals(1,rows.getInt(2));return;
            }catch(SQLException notReady){Thread.sleep(200);}
        }fail("Owned isolated native server did not become ready; inspect private diagnostics");
    }
    private static void privateFile(Path file,String value)throws Exception{
        Files.writeString(file,value,StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
    }
    private static void stopOwned(Process process)throws Exception{
        if(!process.isAlive())return;var children=process.descendants().toList();for(var child:children.reversed())if(child.isAlive())child.destroy();
        process.destroy();if(!process.waitFor(15,TimeUnit.SECONDS))process.destroyForcibly();for(var child:children)if(child.isAlive())child.destroyForcibly();
    }
}
