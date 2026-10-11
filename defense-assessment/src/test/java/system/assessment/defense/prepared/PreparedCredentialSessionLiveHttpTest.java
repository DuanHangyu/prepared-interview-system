package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import system.assessment.defense.application.service.PasswordSecurityService;

import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Actual BCrypt12, local application HTTP, owned native MySQL and Redis.
 * SQL changes and one owned token-session deletion are explicit fault injection,
 * not administrator UI, concurrent-login, recording or complete permission acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS", matches="true")
class PreparedCredentialSessionLiveHttpTest {
    private record Account(int id, String account) {}
    private record Cookie(String header, String token) {}
    private final ObjectMapper json = new ObjectMapper();
    private final PasswordSecurityService passwords = new PasswordSecurityService();
    private final List<Account> accounts = new ArrayList<>();
    private final Set<String> sessionKeys = new HashSet<>();
    private JdbcTemplate jdbc;
    private LettuceConnectionFactory redisFactory;
    private StringRedisTemplate redis;
    private HttpClient http;
    private String original, replacement, namespace;

    @BeforeEach void setup() throws Exception {
        var database = PreparedLiveDatabase.checked();
        assertThat(database.pid()).isPositive(); // Never adopt the canonical database for writes.
        jdbc = new JdbcTemplate(new DriverManagerDataSource(database.url(), database.username(), database.password()));
        namespace = Objects.requireNonNull(System.getenv("SA_TOKEN_TOKEN_NAME"));
        assertThat(namespace).matches("prepared-interview-validation-[a-f0-9-]{36}");
        original = Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));
        replacement = "Next-" + original;
        passwords.encode(replacement); // Same public password constraints, no weaker test encoder.
        String host = Objects.requireNonNull(System.getenv("PREPARED_REDIS_HOST"));
        int port = Integer.parseInt(Objects.requireNonNull(System.getenv("PREPARED_REDIS_PORT")));
        assertThat(host).isEqualTo("127.0.0.1");
        String descriptor = System.getenv("PREPARED_AUTH_REDIS_DESCRIPTOR");
        if (descriptor != null) validateOwnedRedis(descriptor, port);
        else assertThat(port).isEqualTo(16389); // Original standard full-regression Redis contract.
        var config = new RedisStandaloneConfiguration(host, port); config.setDatabase(1);
        String password = System.getenv("PREPARED_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) config.setPassword(password);
        redisFactory = new LettuceConnectionFactory(config); redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory); redis.afterPropertiesSet();
        try (var connection=redisFactory.getConnection()) { assertThat(connection.ping()).isEqualTo("PONG"); }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_1_1).build();
    }

    private void validateOwnedRedis(String descriptor, int port) throws Exception {
        Path project=Path.of("..").toRealPath(), file=Path.of(descriptor);
        assertThat(file.isAbsolute()).isTrue(); assertThat(file.toRealPath()).isEqualTo(file);
        assertThat(file.getFileName().toString()).isEqualTo("auth-redis.json");
        assertThat(file.getParent().getParent()).isEqualTo(project.resolve(".runtime"));
        assertThat(file.getParent().getFileName().toString()).matches("validation-[A-Za-z0-9]{6}");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.size(file)).isLessThan(4096);
        var value=json.readTree(Files.readString(file));
        assertThat(value.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(value.path("port").asInt()).isEqualTo(port); assertThat(port).isBetween(1024,65535).isNotEqualTo(16389);
        assertThat(ProcessHandle.of(value.path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false)).isTrue();
        assertThat(ProcessHandle.of(value.path("runnerPid").asLong()).map(ProcessHandle::isAlive).orElse(false)).isTrue();
        assertThat(Files.readString(file.getParent().resolve("owned-redis/server.pid")).strip())
                .isEqualTo(Long.toString(value.path("pid").asLong()));
    }

    @AfterEach void cleanup() {
        try {
            if (jdbc != null) for (var account : accounts)
                assertThat(jdbc.update("DELETE FROM `user` WHERE id=? AND account=?", account.id(), account.account())).isEqualTo(1);
            if (redis != null && !sessionKeys.isEmpty()) {
                assertThat(sessionKeys).allMatch(key -> key.startsWith(namespace+":login:"));
                redis.delete(sessionKeys); // Exact owned keys only, no scan/flush or shared limit reset.
            }
        } finally {
            try { if (http != null) http.close(); }
            finally { if (redisFactory != null) redisFactory.destroy(); }
        }
    }

    private Account account(int role, boolean legacy) {
        String name="prepared-credential-"+UUID.randomUUID();
        jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('专属会话联调',?,?,?)",
                name, legacy ? original : passwords.encode(original), role);
        var account=new Account(jdbc.queryForObject("SELECT id FROM `user` WHERE account=?", Integer.class,name),name);
        accounts.add(account); sessionKeys.add(namespace+":login:session:"+account.id()); return account;
    }

    private Cookie login(Account account, String password) throws Exception {
        var response=send("POST","/login",null,Map.of("account",account.account(),"password",password)); success(response);
        var headers=response.headers().allValues("Set-Cookie");
        assertThat(headers.stream().filter(header -> header.startsWith(namespace+"=")).findFirst().orElseThrow().toLowerCase(Locale.ROOT))
                .contains("httponly", "samesite=strict");
        var cookie=headers.stream().flatMap(header -> HttpCookie.parse(header).stream())
                .filter(value -> namespace.equals(value.getName())&&value.getMaxAge()!=0).findFirst().orElseThrow();
        String token=URLDecoder.decode(cookie.getValue(),StandardCharsets.UTF_8);
        if(token.startsWith("Bearer "))token=token.substring(7);
        assertThat(token.length()).isBetween(32,256);
        assertThat(token.indexOf(':')).isNegative(); assertThat(token.indexOf('\n')).isNegative();
        sessionKeys.add(namespace+":login:token:"+token); sessionKeys.add(namespace+":login:token-session:"+token);
        sessionKeys.add(namespace+":login:last-active:"+token);
        assertThat(redis.hasKey(namespace+":login:token-session:"+token)).isTrue();
        return new Cookie(cookie.getName()+"="+cookie.getValue(),token);
    }

    private JsonNode identity(Account account, Cookie cookie, String role) throws Exception {
        var data=success(send("GET","/user-detail",cookie,null));
        assertThat(data.path("id").asInt()).isEqualTo(account.id());
        assertThat(data.path("account").asText()).isEqualTo(account.account());
        assertThat(data.path("role").asText()).isEqualTo(role);
        assertThat(data.has("password")).isFalse(); assertThat(data.has("verifiedCredential")).isFalse(); return data;
    }

    @RepeatedTest(3) void normalStudentTeacherAndAdminSessionsRemainBoundAndIsolated() throws Exception {
        var roles=List.of("STUDENT","TEACHER","ADMIN");
        for(int role=0;role<roles.size();role++){
            var account=account(role,false); var cookie=login(account,original); identity(account,cookie,roles.get(role));
            success(send("POST","/logout",cookie,Map.of())); rejected(send("GET","/user-detail",cookie,null),401);
        }
    }

    @RepeatedTest(3) void successfulLegacyUpgradeHasAUsableBindingAndDoesNotRewriteProfile() throws Exception {
        var account=account(0,true); var cookie=login(account,original); identity(account,cookie,"STUDENT");
        String stored=jdbc.queryForObject("SELECT password FROM `user` WHERE id=?",String.class,account.id());
        assertThat(stored).startsWith("$2"); assertThat(passwords.verify(original,stored).matched()).isTrue();
        assertThat(jdbc.queryForObject("SELECT name FROM `user` WHERE id=?",String.class,account.id())).isEqualTo("专属会话联调");
    }

    @RepeatedTest(3) void changingOwnPasswordRevokesOldCookieAndAllowsFreshLogin() throws Exception {
        var account=account(0,false); var old=login(account,original);
        assertThat(success(send("POST","/change-password",old,Map.of("currentPassword",original,"newPassword",replacement))).asBoolean()).isTrue();
        rejected(send("GET","/user-detail",old,null),401);
        rejected(send("POST","/login",null,Map.of("account",account.account(),"password",original)),401);
        identity(account,login(account,replacement),"STUDENT");
    }

    @RepeatedTest(3) void aCommittedPasswordWriteWithoutLogoutStillRejectsThePresentOldToken() throws Exception {
        var account=account(0,false); var old=login(account,original); identity(account,old,"STUDENT");
        assertThat(redis.hasKey(namespace+":login:token:"+old.token())).isTrue();
        assertThat(jdbc.update("UPDATE `user` SET password=? WHERE id=? AND account=?",passwords.encode(replacement),account.id(),account.account())).isEqualTo(1);
        assertThat(redis.hasKey(namespace+":login:token:"+old.token())).isTrue(); // SQL did not revoke Redis.
        rejected(send("GET","/user-detail",old,null),401);
        identity(account,login(account,replacement),"STUDENT");
    }

    @RepeatedTest(3) void deletingAnOwnedAccountRejectsItsStillPresentCookie() throws Exception {
        var account=account(0,false); var old=login(account,original); identity(account,old,"STUDENT");
        assertThat(jdbc.update("DELETE FROM `user` WHERE id=? AND account=?",account.id(),account.account())).isEqualTo(1);
        accounts.remove(account); rejected(send("GET","/user-detail",old,null),401);
    }

    @RepeatedTest(3) void aTokenWithoutItsCredentialBindingIsNotAutomaticallyUpgraded() throws Exception {
        var account=account(0,false); var old=login(account,original); identity(account,old,"STUDENT");
        String key=namespace+":login:token-session:"+old.token();
        assertThat(redis.delete(key)).isTrue();
        assertThat(redis.hasKey(namespace+":login:token:"+old.token())).isTrue();
        rejected(send("GET","/user-detail",old,null),401);
        identity(account,login(account,original),"STUDENT");
    }

    @RepeatedTest(3) void validRoleChangesAreReadFreshAndDoNotKeepStudentPermission() throws Exception {
        var account=account(0,false); var cookie=login(account,original);
        success(send("GET","/front/student-assessment/get-machine-time",cookie,null));
        assertThat(jdbc.update("UPDATE `user` SET type=1 WHERE id=? AND account=?",account.id(),account.account())).isEqualTo(1);
        identity(account,cookie,"TEACHER"); rejected(send("GET","/front/student-assessment/get-machine-time",cookie,null),403);
    }

    private HttpResponse<String> send(String method,String route,Cookie cookie,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:13016"+route)).timeout(Duration.ofSeconds(8));
        if(cookie!=null)request.header("Cookie",cookie.header());
        if(method.equals("POST"))request.header("Origin","http://127.0.0.1:13017").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else request.GET();
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode success(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200); var envelope=json.readTree(response.body());
        assertThat(envelope.path("code").asInt()).isEqualTo(200); return envelope.path("data");
    }
    private void rejected(HttpResponse<String> response,int code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(code); assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(code);
    }
}
