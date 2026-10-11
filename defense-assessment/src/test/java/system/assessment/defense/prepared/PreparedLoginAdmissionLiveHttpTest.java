package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import system.assessment.defense.application.service.PasswordSecurityService;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Real local accounts/cookies/MySQL/Redis/HTTP only; no model, audio or production capacity claim. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS", matches="true")
class PreparedLoginAdmissionLiveHttpTest {
    private record Student(int id, String account) {}
    private final List<Student> owned = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private HttpClient http;
    private String password;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                PreparedLiveDatabase.jdbcUrl(),
                Objects.requireNonNull(System.getenv("PREPARED_DB_USERNAME")),
                Objects.requireNonNull(System.getenv("PREPARED_DB_PASSWORD"))));
        assertThat(jdbc.queryForObject("SELECT @@datadir", String.class))
                .isEqualTo(PreparedLiveDatabase.checked().expectedData().toString()+"/");
        password = Objects.requireNonNull(System.getenv("PREPARED_TEST_LOGIN_PASSWORD"));
        String hash = new PasswordSecurityService().encode(password);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_1_1).build();
        for (int index = 0; index < 120; index++) {
            String account = "prepared-login-" + UUID.randomUUID();
            jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('共享出口登录联调',?,?,0)", account, hash);
            int id = jdbc.queryForObject("SELECT id FROM `user` WHERE account=?", Integer.class, account);
            owned.add(new Student(id, account));
        }
    }

    @AfterEach void cleanup() {
        try { if (http != null) http.close(); }
        finally {
            if (jdbc != null) for (var student : owned) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_student_relation WHERE student_id=?", Integer.class, student.id())).isZero();
                assertThat(jdbc.update("DELETE FROM `user` WHERE id=? AND account=?", student.id(), student.account())).isEqualTo(1);
            }
        }
    }

    @Test @Timeout(value=5, unit=TimeUnit.MINUTES)
    void oneHundredTwentyRealStudentsCanLoginAndLogoutThreeTimesFromTheSameExit() throws Exception {
        for (int round = 1; round <= 3; round++) for (var student : owned) {
            var login = send("POST", "/login", null, Map.of("account",student.account(),"password",password));
            success(login);
            var cookies = login.headers().allValues("Set-Cookie").stream().flatMap(h -> HttpCookie.parse(h).stream())
                    .filter(c -> c.getMaxAge()!=0).map(c -> c.getName()+"="+c.getValue()).toList();
            assertThat(cookies).isNotEmpty();
            String cookie = String.join("; ",cookies);
            try {
                var identity = success(send("GET", "/user-detail",cookie,null));
                assertThat(identity.path("id").asInt()).isEqualTo(student.id());
                assertThat(identity.path("account").asText()).isEqualTo(student.account());
                assertThat(identity.path("role").asText()).isEqualTo("STUDENT");
            } finally { success(send("POST", "/logout",cookie,Map.of())); }
        }
        // Do not exhaust/delete the shared real exit's failure budget to clean up a test.
        // Exact failed/in-flight limits and generation races are covered with owned Redis keys separately.
    }

    private HttpResponse<String> send(String method, String route, String cookie, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:13016"+route)).timeout(Duration.ofSeconds(8));
        if (cookie != null) builder.header("Cookie",cookie);
        if ("POST".equals(method)) builder.header("Origin","http://127.0.0.1:13017")
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else builder.GET();
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode success(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        var envelope = json.readTree(response.body());
        assertThat(envelope.path("code").asInt()).isEqualTo(200);
        return envelope.path("data");
    }
}
