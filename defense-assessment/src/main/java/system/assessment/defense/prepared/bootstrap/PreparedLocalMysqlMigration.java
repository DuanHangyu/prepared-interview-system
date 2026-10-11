package system.assessment.defense.prepared.bootstrap;

import org.flywaydb.core.Flyway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Explicit local Flyway command, never a Spring bean, reset, repair or auto-start. */
public final class PreparedLocalMysqlMigration {
    private static final LinkOption[] NO_LINKS = {LinkOption.NOFOLLOW_LINKS};
    private static final String MIGRATOR = "prepared_migrator";

    private PreparedLocalMysqlMigration() {}

    public static int migrate(Path project, Map<String, String> environment) throws Exception {
        var plan = PreparedLocalMysqlBootstrap.validateInstalled(project, environment);
        verifyOwnedListener(plan); // Before even reading migration/admin credentials.
        Credential credential = readCredential(plan); // Does not read admin configuration.
        Path lock = plan.runtime().resolve("mysql-migrate.lock");
        Files.createDirectory(lock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        boolean complete = false;
        try {
            String url = boundedDatabaseUrl(environment.get("PREPARED_DB_JDBC_URL"));
            try (Connection connection = DriverManager.getConnection(url, MIGRATOR, credential.password)) {
                verifyServer(connection, plan, MIGRATOR);
            }
            Flyway flyway = Flyway.configure().dataSource(url, MIGRATOR, credential.password)
                    .schemas(plan.schema()).defaultSchema(plan.schema()).createSchemas(false)
                    .locations("classpath:db/migration").baselineOnMigrate(false)
                    .cleanDisabled(true).validateOnMigrate(true).connectRetries(0).load();
            int applied = flyway.migrate().migrationsExecuted;
            flyway.validate();
            require(flyway.info().pending().length == 0, "Migration did not reach the installed application version.");
            Files.delete(lock); // Only our exclusively created empty operation lock.
            complete = true;
            return applied;
        } finally {
            if (!complete) System.err.println("Migration incomplete; private operation lock and database state retained. No automatic repair or cleanup was performed.");
        }
    }

    static String boundedDatabaseUrl(String validatedUrl) {
        // validateInstalled rejects all user-supplied deadline/retry overrides.
        return validatedUrl + "&connectTimeout=5000&socketTimeout=30000";
    }

    static Credential readCredential(PreparedLocalMysqlBootstrap.Plan plan) throws IOException {
        Path config = plan.migration();
        require(Files.isRegularFile(config, NO_LINKS) && !Files.isSymbolicLink(config)
                        && Files.getOwner(config, NO_LINKS).equals(Files.getOwner(plan.root(), NO_LINKS))
                        && Files.getPosixFilePermissions(config, NO_LINKS).equals(PosixFilePermissions.fromString("rw-------")),
                "Dedicated migration configuration must be owner-only and regular.");
        require(Files.size(config) <= 4096, "Migration configuration exceeds its limit.");
        List<String> lines = Files.readAllLines(config);
        require(!lines.isEmpty() && lines.getFirst().equals("[client]"), "Migration configuration section is invalid.");
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            var match = java.util.regex.Pattern.compile("([a-z-]+)=(.*)").matcher(line);
            require(match.matches(), "Migration configuration contains unsupported syntax.");
            String key = match.group(1), value = match.group(2);
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) value = value.substring(1, value.length() - 1);
            require(options.putIfAbsent(key, value) == null, "Migration configuration contains duplicate options.");
        }
        require(options.keySet().equals(java.util.Set.of("user", "password", "protocol", "host", "port", "ssl-mode")), "Only dedicated migration options are permitted.");
        require(MIGRATOR.equals(options.get("user")) && "TCP".equals(options.get("protocol"))
                        && "127.0.0.1".equals(options.get("host")) && Integer.toString(plan.port()).equals(options.get("port"))
                        && "REQUIRED".equals(options.get("ssl-mode")), "Migration identity or endpoint is invalid.");
        String password = options.get("password");
        require(password.matches("[A-Za-z0-9_-]{32,128}") && !password.equals(plan.applicationPassword()), "Migration credential must be independent and strong.");
        return new Credential(password);
    }

    static void verifyOwnedListener(PreparedLocalMysqlBootstrap.Plan plan) throws Exception {
        Path pidFile = plan.data().resolve("server.pid");
        require(Files.isRegularFile(pidFile, NO_LINKS) && !Files.isSymbolicLink(pidFile)
                        && Files.getOwner(pidFile, NO_LINKS).equals(Files.getOwner(plan.root(), NO_LINKS)) && Files.size(pidFile) <= 32,
                "Owned database process evidence is missing.");
        String pid = Files.readString(pidFile).strip();
        require(pid.matches("[1-9][0-9]{0,17}"), "Owned database PID is invalid.");
        ProcessHandle process = ProcessHandle.of(Long.parseLong(pid)).orElseThrow(() -> new IllegalArgumentException("Owned database process is not running."));
        require(process.isAlive(), "Owned database process is not running.");
        ProcessHandle.Info info = process.info();
        String command = info.command().orElse("");
        List<String> arguments = List.of(info.arguments().orElse(new String[0]));
        verifyProcessCommand(plan, command, arguments);
        Path lsof = Files.isExecutable(Path.of("/usr/sbin/lsof")) ? Path.of("/usr/sbin/lsof") : Path.of("/usr/bin/lsof");
        require(Files.isRegularFile(lsof) && Files.isExecutable(lsof), "Local listener ownership tool is unavailable.");
        ProcessBuilder builder = new ProcessBuilder(lsof.toString(), "-nP", "-t", "-iTCP:" + plan.port(), "-sTCP:LISTEN").redirectErrorStream(true);
        builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"); builder.environment().put("LC_ALL", "C");
        Process probe = builder.start();
        try {
            require(probe.waitFor(3, TimeUnit.SECONDS), "Listener ownership probe timed out.");
            byte[] bytes = probe.getInputStream().readNBytes(4097);
            require(bytes.length <= 4096 && probe.exitValue() == 0
                    && new String(bytes, java.nio.charset.StandardCharsets.UTF_8).strip().equals(pid), "TCP listener is not the owned database process.");
        } finally { if (probe.isAlive()) probe.destroyForcibly(); }
    }

    static void verifyProcessCommand(PreparedLocalMysqlBootstrap.Plan plan, String command, List<String> arguments) {
        require(command.equals(plan.base().resolve("bin/mysqld").toString())
                        && arguments.contains("--no-defaults") && arguments.contains("--datadir=" + plan.data())
                        && arguments.contains("--pid-file=" + plan.data().resolve("server.pid")) && arguments.contains("--port=" + plan.port())
                        && arguments.contains("--bind-address=127.0.0.1") && arguments.contains("--partial-revokes=ON"),
                "Database command does not belong to this initialized project.");
    }

    static void verifyServer(Connection connection, PreparedLocalMysqlBootstrap.Plan plan, String user) throws Exception {
        try (var statement = connection.prepareStatement("SELECT @@datadir,@@port,@@partial_revokes,CURRENT_USER()")) {
            statement.setQueryTimeout(3);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && (plan.data() + "/").equals(rows.getString(1)) && rows.getInt(2) == plan.port()
                        && rows.getInt(3) == 1 && (user + "@127.0.0.1").equals(rows.getString(4)), "Authenticated database identity does not match the owned project.");
            }
        }
    }

    private static void require(boolean condition, String safeMessage) {
        if (!condition) throw new IllegalArgumentException(safeMessage);
    }

    static final class Credential {
        private final String password;
        private Credential(String password) { this.password = password; }
        @Override public String toString() { return "PreparedMigrationCredential[REDACTED]"; }
    }

    public static void main(String[] args) {
        try {
            require(args.length == 1, "Exactly one prepared project path is required.");
            int applied = migrate(Path.of(args[0]), System.getenv());
            System.out.println("Independent migration completed; applied versions: " + applied + ".");
        } catch (Exception failure) {
            System.err.println("Migration refused or failed; inspect private project diagnostics. Existing services were not stopped or reset.");
            System.exit(1);
        }
    }
}
