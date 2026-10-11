package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.util.*;

/** Test-only connection boundary. No implicit address/account fallback. */
public final class PreparedLiveDatabase {
    private PreparedLiveDatabase(){}
    public record Configuration(String url,String username,String password,Path expectedData,long pid,long ownerPid) {
        @Override public String toString(){return "PreparedLiveDatabase[private identity redacted]";}
    }
    public static Configuration validate(Map<String,String> env) {
        try {
            String url=Objects.requireNonNull(env.get("PREPARED_DB_JDBC_URL"));
            String user=Objects.requireNonNull(env.get("PREPARED_DB_USERNAME")),password=Objects.requireNonNull(env.get("PREPARED_DB_PASSWORD"));
            if(!user.matches("prepared_[a-z0-9_]+")||password.isBlank()||password.matches("(?s).*[\\r\\n\\x00].*"))throw new IllegalArgumentException();
            URI uri=URI.create(url.substring(5));
            if(!url.startsWith("jdbc:mysql://")||!"127.0.0.1".equals(uri.getHost())||uri.getUserInfo()!=null||uri.getFragment()!=null
                ||!"/prepared_interview".equals(uri.getPath())||uri.getPort()<1024||uri.getPort()>65535)throw new IllegalArgumentException();
            Map<String,String> options=new HashMap<>();
            for(String item:Objects.requireNonNull(uri.getRawQuery()).split("&",-1)) {
                String[] pair=item.split("=",-1);
                if(pair.length!=2||options.putIfAbsent(pair[0],pair[1])!=null||!Set.of("sslMode","serverTimezone","connectionTimeZone","useUnicode","characterEncoding").contains(pair[0]))throw new IllegalArgumentException();
            }
            if(!"REQUIRED".equals(options.get("sslMode")))throw new IllegalArgumentException();
            Path project=Path.of("..").toRealPath();
            if(!project.getFileName().toString().equals("prepared-interview-system"))throw new IllegalArgumentException();
            String owner=env.get("PREPARED_VALIDATION_DATABASE_DESCRIPTOR");
            if(owner==null) {
                if(uri.getPort()!=13317)throw new IllegalArgumentException();
                return new Configuration(url,user,password,project.resolve(".runtime/mysql"),0,0);
            }
            Path file=Path.of(owner);
            if(!file.isAbsolute()||!file.equals(file.toRealPath())||!file.getFileName().toString().equals("database.json")
                ||!file.getParent().getParent().equals(project.resolve(".runtime"))||!file.getParent().getFileName().toString().matches("validation-[A-Za-z0-9]{6}")
                ||!Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------"))||!Files.getOwner(file).equals(Files.getOwner(project))
                ||Files.size(file)>8192||uri.getPort()==13317)throw new IllegalArgumentException();
            var json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(Files.readString(file));
            Set<String> keys=new HashSet<>();json.fieldNames().forEachRemaining(keys::add);
            if(!keys.equals(Set.of("schemaVersion","url","username","password","data","pid","ownerPid","nonce"))||json.path("schemaVersion").asInt()!=1
                ||!url.equals(json.path("url").asText())||!user.equals(json.path("username").asText())||!password.equals(json.path("password").asText())
                ||!json.path("nonce").asText().matches("[a-f0-9-]{36}"))throw new IllegalArgumentException();
            Path data=Path.of(json.path("data").asText()).toRealPath();
            if(!data.getFileName().toString().equals("mysql")||!data.getParent().getFileName().toString().equals(".runtime")
                ||!data.getParent().getParent().getParent().equals(project.resolve(".runtime"))
                ||!data.getParent().getParent().getFileName().toString().matches("om-[a-f0-9]{8}"))throw new IllegalArgumentException();
            long pid=json.path("pid").asLong(),ownerPid=json.path("ownerPid").asLong();
            if(pid<=0||ownerPid<=0||!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)||!ProcessHandle.of(ownerPid).map(ProcessHandle::isAlive).orElse(false))throw new IllegalArgumentException();
            return new Configuration(url,user,password,data,pid,ownerPid);
        }catch(Exception invalid){throw new IllegalArgumentException("Dedicated prepared test database required");}
    }
    public static Configuration checked() {
        var config=validate(System.getenv());
        try(var connection=DriverManager.getConnection(config.url(),config.username(),config.password());var statement=connection.createStatement();
            var rows=statement.executeQuery("SELECT DATABASE(),@@datadir,@@port,CURRENT_USER()")) {
            if(!rows.next()||!"prepared_interview".equals(rows.getString(1))||!Path.of(rows.getString(2)).toRealPath().equals(config.expectedData().toRealPath())
                ||rows.getInt(3)!=URI.create(config.url().substring(5)).getPort()||!rows.getString(4).startsWith(config.username()+"@"))throw new IllegalArgumentException();
            if(config.pid()>0&&!Files.readString(config.expectedData().resolve("server.pid")).strip().equals(Long.toString(config.pid())))throw new IllegalArgumentException();
            return config;
        }catch(Exception invalid){throw new IllegalArgumentException("Dedicated prepared test identity mismatch");}
    }
    public static String jdbcUrl(){return checked().url();}
    public static void browserEnvironment(Map<String,String> env) {
        env.keySet().retainAll(Set.of("HOME","PATH","JAVA_HOME","CI","PREPARED_TEST_LOGIN_PASSWORD","PREPARED_VALIDATION_RUN_ID",
            "PREPARED_VALIDATION_BROWSER_DESCRIPTOR","PREPARED_VALIDATION_BROWSER_RUN_ID"));
    }
}
