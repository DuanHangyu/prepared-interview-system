package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import system.assessment.defense.application.service.PasswordSecurityService;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Real default prepared backend/HTTP/upgrade/owned SQL/Redis. No application
 * WebSocket frames, recording, model calls or claim of resource exploitation. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedLegacyWebSocketIsolationLiveTest {
    private final ObjectMapper json=new ObjectMapper();
    private final Set<String> ownedKeys=new HashSet<>();
    private JdbcTemplate jdbc;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private HttpClient http;
    private String namespace,account,password;
    private Integer id;

    @BeforeEach void setup() throws Exception {
        var database=PreparedLiveDatabase.checked(); assertThat(database.pid()).isPositive();
        jdbc=new JdbcTemplate(new DriverManagerDataSource(database.url(),database.username(),database.password()));
        namespace=Objects.requireNonNull(System.getenv("SA_TOKEN_TOKEN_NAME"));
        assertThat(namespace).matches("prepared-interview-validation-[a-f0-9-]{36}");
        assertThat(System.getenv("PREPARED_REDIS_HOST")).isEqualTo("127.0.0.1");
        int port=Integer.parseInt(Objects.requireNonNull(System.getenv("PREPARED_REDIS_PORT")));
        String descriptor=System.getenv("PREPARED_AUTH_REDIS_DESCRIPTOR");
        if(descriptor==null)assertThat(port).isEqualTo(16389);
        else {
            Path file=Path.of(descriptor),project=Path.of("..").toRealPath();
            assertThat(file.isAbsolute()).isTrue(); assertThat(file.toRealPath()).isEqualTo(file);
            assertThat(file.getFileName().toString()).isEqualTo("auth-redis.json");
            assertThat(file.getParent().getParent()).isEqualTo(project.resolve(".runtime"));
            assertThat(file.getParent().getFileName().toString()).matches("validation-[A-Za-z0-9]{6}");
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.size(file)).isLessThan(4096);
            var proof=json.readTree(Files.readString(file));
            assertThat(proof.path("schemaVersion").asInt()).isEqualTo(1);
            assertThat(proof.path("port").asInt()).isEqualTo(port); assertThat(port).isBetween(1024,65535).isNotEqualTo(16389);
            assertThat(ProcessHandle.of(proof.path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false)).isTrue();
            assertThat(ProcessHandle.of(proof.path("runnerPid").asLong()).map(ProcessHandle::isAlive).orElse(false)).isTrue();
            assertThat(Files.readString(file.getParent().resolve("owned-redis/server.pid")).strip())
                    .isEqualTo(Long.toString(proof.path("pid").asLong()));
        }
        var configuration=new RedisStandaloneConfiguration("127.0.0.1",port); configuration.setDatabase(1);
        String redisPassword=System.getenv("PREPARED_REDIS_PASSWORD");
        if(redisPassword!=null&&!redisPassword.isBlank())configuration.setPassword(redisPassword);
        factory=new LettuceConnectionFactory(configuration); factory.afterPropertiesSet();
        redis=new StringRedisTemplate(factory); redis.afterPropertiesSet();
        try(var connection=factory.getConnection()){assertThat(connection.ping()).isEqualTo("PONG");}
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_1_1).build();
        account="prepared-ws-isolation-"+UUID.randomUUID(); password=Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));
        jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('实时入口隔离联调',?,?,0)",account,new PasswordSecurityService().encode(password));
        id=jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);
        ownedKeys.add(namespace+":login:session:"+id);
    }

    @AfterEach void cleanup() {
        try {
            if(id!=null)assertThat(jdbc.update("DELETE FROM `user` WHERE id=? AND account=?",id,account)).isEqualTo(1);
            if(redis!=null&&!ownedKeys.isEmpty()){
                assertThat(ownedKeys).allMatch(key->key.startsWith(namespace+":login:")); redis.delete(ownedKeys);
            }
        }finally{try{if(http!=null)http.close();}finally{if(factory!=null)factory.destroy();}}
    }

    private void mustBeAbsent(String route,boolean removeBinding) throws Exception {
        var login=HttpRequest.newBuilder(URI.create("http://127.0.0.1:13016/login")).timeout(Duration.ofSeconds(8))
                .header("Origin","http://127.0.0.1:13017").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("account",account,"password",password)))).build();
        var response=http.send(login,HttpResponse.BodyHandlers.ofString()); assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(200);
        var cookie=response.headers().allValues("Set-Cookie").stream().flatMap(header->HttpCookie.parse(header).stream())
                .filter(value->namespace.equals(value.getName())&&value.getMaxAge()!=0).findFirst().orElseThrow();
        String raw=URLDecoder.decode(cookie.getValue(),StandardCharsets.UTF_8); if(raw.startsWith("Bearer "))raw=raw.substring(7);
        assertThat(raw.length()).isBetween(32,256); assertThat(raw.indexOf(':')).isNegative(); assertThat(raw.indexOf('\n')).isNegative();
        String key=namespace+":login:token-session:"+raw;
        ownedKeys.add(key); ownedKeys.add(namespace+":login:token:"+raw); ownedKeys.add(namespace+":login:last-active:"+raw);
        assertThat(redis.hasKey(key)).isTrue();
        if(removeBinding)assertThat(redis.delete(key)).isTrue();
        assertThat(redis.hasKey(namespace+":login:token:"+raw)).isTrue();
        // Supply the actual prepared login Cookie as well as the raw token
        // required by the archived handshake. A removed token-session has not
        // removed its login map. This separates endpoint exposure from a
        // malformed/unrecognized prepared identity.
        String preparedCookie=cookie.getName()+"="+cookie.getValue();
        try {
            var socket=http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8))
                    .header("Origin","http://127.0.0.1:13017").header("Cookie",preparedCookie+"; Authorization="+raw)
                    .buildAsync(URI.create("ws://127.0.0.1:13016"+route),new WebSocket.Listener(){
                        @Override public void onOpen(WebSocket socket){socket.request(1);}
                    }).get(8,TimeUnit.SECONDS);
            socket.abort();
            fail("LEGACY_UPGRADE_ACCEPTED: prepared backend must not register "+route);
        } catch(ExecutionException error) {
            assertThat(error.getCause()).isInstanceOf(WebSocketHandshakeException.class);
            assertThat(((WebSocketHandshakeException)error.getCause()).getResponse().statusCode()).isEqualTo(removeBinding?401:404);
        }
        var identity=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:13016/user-detail"))
                .timeout(Duration.ofSeconds(8)).header("Cookie",preparedCookie).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(identity.statusCode()).isEqualTo(removeBinding?401:200);
        assertThat(json.readTree(identity.body()).path("code").asInt()).isEqualTo(removeBinding?401:200);
    }

    @RepeatedTest(3) void textEntryIsAbsent(){assertThatCode(()->mustBeAbsent("/defense-assessment",false)).doesNotThrowAnyException();}
    @RepeatedTest(3) void voiceEntryIsAbsent(){assertThatCode(()->mustBeAbsent("/voice/defense-assessment",false)).doesNotThrowAnyException();}
    @RepeatedTest(3) void playbackEntryIsAbsent(){assertThatCode(()->mustBeAbsent("/voice-play/defense-assessment",false)).doesNotThrowAnyException();}
    @RepeatedTest(3) void textDoesNotAcceptAnUnboundToken(){assertThatCode(()->mustBeAbsent("/defense-assessment",true)).doesNotThrowAnyException();}
    @RepeatedTest(3) void voiceDoesNotAcceptAnUnboundToken(){assertThatCode(()->mustBeAbsent("/voice/defense-assessment",true)).doesNotThrowAnyException();}
    @RepeatedTest(3) void playbackDoesNotAcceptAnUnboundToken(){assertThatCode(()->mustBeAbsent("/voice-play/defense-assessment",true)).doesNotThrowAnyException();}
}
