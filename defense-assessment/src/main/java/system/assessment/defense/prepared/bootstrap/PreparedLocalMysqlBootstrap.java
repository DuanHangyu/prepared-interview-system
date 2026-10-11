package system.assessment.defense.prepared.bootstrap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Explicit fresh-local-server provisioning, not a Spring component or a reset tool.
 * Standard-library only so Java 21 source-file mode works before Maven/bootstrap.
 * Never contacts, stops, changes or adopts a previously initialized database.
 */
public final class PreparedLocalMysqlBootstrap {
    private static final LinkOption[] NO_LINKS = {LinkOption.NOFOLLOW_LINKS};
    private static final String MIGRATOR = "prepared_migrator";
    private static final List<String> CONTROL_FILES = List.of("mysql-admin.cnf", "mysql-migration.cnf",
            "mysql-init.sql", "mysql-bootstrap.lock", "mysql-bootstrap.log", "mysql-client-output", "mysql-bootstrap.complete");
    private static final Pattern JDBC = Pattern.compile(
            "jdbc:mysql://127\\.0\\.0\\.1:([0-9]+)/(?<schema>prepared_interview(?:_[a-z0-9]+)*)(?:\\?([a-zA-Z0-9=&%_/:.+-]+))?");

    private PreparedLocalMysqlBootstrap() {}

    /** All policy checks precede writes, generated credentials and process creation. */
    public static Plan validate(Path project, Map<String, String> environment) throws IOException {
        return configuration(project, environment, true);
    }

    /** Installed-server policy is distinct from fresh initialization; never rotates. */
    public static Plan validateInstalled(Path project, Map<String, String> environment) throws IOException {
        return configuration(project, environment, false);
    }

    private static Plan configuration(Path project, Map<String, String> environment, boolean fresh) throws IOException {
        require(project != null && environment != null, "Project and dedicated configuration are required.");
        Path root = project.toAbsolutePath().normalize();
        require(root.equals(root.toRealPath()) && Files.isDirectory(root, NO_LINKS), "Project must be a canonical directory.");
        Path envFile = Path.of(environment.getOrDefault("ENV_FILE", root.resolve(".env.local").toString())).toAbsolutePath().normalize();
        require(envFile.startsWith(root) && !envFile.equals(root), "Configuration must be inside the prepared project.");
        noLinksBetween(root, envFile);
        require(Files.isRegularFile(envFile, NO_LINKS), "Dedicated local configuration is missing.");
        ownerOnly(envFile, root, false);
        require("prepared-local".equals(environment.getOrDefault("SPRING_PROFILE", "prepared-local")), "Only prepared-local is supported.");
        Path runtime = root.resolve(".runtime");
        Path data = runtime.resolve("mysql");
        noLinksBetween(root, data);
        if (Files.exists(runtime, NO_LINKS)) ownerOnly(runtime, root, true);
        if (fresh && Files.exists(data, NO_LINKS)) {
            require(Files.isDirectory(data, NO_LINKS), "Database target must be an empty directory.");
            try (var entries = Files.list(data)) {
                require(entries.findAny().isEmpty(), "Database target contains data; initialization refused without changes.");
            }
            require(Files.getOwner(data, NO_LINKS).equals(Files.getOwner(root, NO_LINKS)), "Database target owner is invalid.");
        }
        if (fresh) {
            for (String name : CONTROL_FILES) {
                require(!Files.exists(runtime.resolve(name), NO_LINKS), "Existing database control or credential file; initialization refused.");
            }
        } else {
            require(Files.isDirectory(data, NO_LINKS) && Files.isDirectory(data.resolve("mysql"), NO_LINKS)
                    && !Files.isSymbolicLink(data.resolve("mysql")) && Files.isRegularFile(data.resolve("mysql.ibd"), NO_LINKS),
                    "Independent installed database evidence is incomplete.");
            require(Files.getOwner(data, NO_LINKS).equals(Files.getOwner(root, NO_LINKS)), "Installed database owner is invalid.");
            Path marker = runtime.resolve("mysql-bootstrap.complete");
            require(!Files.isSymbolicLink(marker) && Files.isRegularFile(marker, NO_LINKS), "Fresh-server policy evidence is missing.");
            ownerOnly(marker, root, false);
            require(Files.size(marker) < 128 && Files.readString(marker).equals("prepared-local-mysql-v1\n"), "Fresh-server policy version is invalid.");
            require(!Files.exists(runtime.resolve("mysql-bootstrap.lock"), NO_LINKS), "Initialization did not complete; migration refused.");
        }
        String baseSetting = required(environment, "PREPARED_MYSQL_BASEDIR");
        Path base = Path.of(baseSetting).toAbsolutePath().normalize();
        require(base.equals(base.toRealPath()), "MySQL installation must be canonical.");
        for (String tool : List.of("mysqld", "mysql", "mysqladmin")) {
            Path binary = base.resolve("bin/" + tool);
            require(Files.isRegularFile(binary, NO_LINKS) && Files.isExecutable(binary), "Required MySQL executable is unavailable.");
        }
        int port = port(environment.getOrDefault("PREPARED_MYSQL_PORT", "13317"));
        require(port != port(environment.getOrDefault("PREPARED_BACKEND_PORT", "13014"))
                && port != port(environment.getOrDefault("PREPARED_FRONTEND_PORT", "13015")), "Database and application ports must differ.");
        String url = required(environment, "PREPARED_DB_JDBC_URL");
        var match = JDBC.matcher(url);
        require(match.matches() && match.group(1).equals(Integer.toString(port)), "Dedicated loopback database URL and port must match.");
        String schema = match.group("schema");
        require(schema.length() <= 64, "Database identifier exceeds its allowed size.");
        String options = match.group(3);
        require(options != null && List.of(options.split("&")).contains("sslMode=REQUIRED"), "Database transport must require TLS.");
        var optionKeys = new java.util.HashSet<String>();
        Map<String, List<String>> allowedOptions = Map.of("sslMode", List.of("REQUIRED"), "useUnicode", List.of("true"),
                "characterEncoding", List.of("UTF-8"), "serverTimezone", List.of("Asia/Shanghai", "Asia%2FShanghai", "UTC"),
                "connectionTimeZone", List.of("Asia/Shanghai", "Asia%2FShanghai", "UTC"));
        for (String option : options.split("&", -1)) {
            String[] parts = option.split("=", -1);
            require(parts.length == 2 && optionKeys.add(parts[0]) && allowedOptions.containsKey(parts[0])
                    && allowedOptions.get(parts[0]).contains(parts[1]), "Database URL options must be unique and approved.");
        }
        String username = required(environment, "PREPARED_DB_USERNAME");
        require(username.matches("[a-z][a-z0-9_]{2,30}") && !username.equals("root") && !username.equals(MIGRATOR), "Application identity must be safe and unprivileged.");
        String password = required(environment, "PREPARED_DB_PASSWORD");
        require(password.matches("[A-Za-z0-9+/_=-]{32,128}"), "Application password requires 32-128 safe characters.");
        return new Plan(root, runtime, data, base, schema, port, username, password);
    }

    public static Result initialize(Path project, Map<String, String> environment) throws Exception {
        Plan plan = validate(project, environment);
        require(plan.socket().toString().getBytes(StandardCharsets.UTF_8).length <= 100, "Local socket path is too long; use a shorter project path.");
        // Refuse any occupied port without authenticating to or signalling its listener.
        try (ServerSocket portCheck = new ServerSocket()) {
            portCheck.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), plan.port));
        } catch (IOException occupied) {
            throw new IllegalArgumentException("Dedicated MySQL port is unavailable; no existing process was contacted or stopped.");
        }
        if (!Files.exists(plan.runtime, NO_LINKS)) privateDirectory(plan.runtime);
        Path lock = plan.runtime.resolve("mysql-bootstrap.lock");
        privateDirectory(lock); // CREATE_NEW directory is also the cross-process operation lock.
        Path log = plan.runtime.resolve("mysql-bootstrap.log");
        privateFile(log, "");
        Path output = plan.runtime.resolve("mysql-client-output");
        privateFile(output, "");
        Process daemon = null;
        boolean verified = false;
        try {
            // Recheck while owning the operation lock; a racing invocation never gets credentials.
            if (Files.exists(plan.data, NO_LINKS)) {
                noLinksBetween(plan.root, plan.data);
                try (var entries = Files.list(plan.data)) {
                    require(entries.findAny().isEmpty(), "Database target changed before initialization; refused.");
                }
                Files.setPosixFilePermissions(plan.data, PosixFilePermissions.fromString("rwx------"));
            } else privateDirectory(plan.data);
            String adminPassword = secret();
            String migrationPassword = secret();
            require(!adminPassword.equals(plan.password) && !migrationPassword.equals(plan.password)
                    && !adminPassword.equals(migrationPassword), "Independent credentials could not be generated.");
            privateFile(plan.admin(), clientConfiguration("root", adminPassword, plan.socket(), 0));
            privateFile(plan.migration(), clientConfiguration(MIGRATOR, migrationPassword, null, plan.port));
            Path init = plan.runtime.resolve("mysql-init.sql");
            privateFile(init, initializationSql(plan, adminPassword, migrationPassword));
            List<String> baseArgs = List.of(plan.base.resolve("bin/mysqld").toString(), "--no-defaults",
                    "--basedir=" + plan.base, "--datadir=" + plan.data);
            List<String> initializeArgs = new ArrayList<>(baseArgs);
            initializeArgs.add("--initialize"); // Never create a passwordless root.
            initializeArgs.add("--log-error=" + log);
            require(run(initializeArgs, output, Duration.ofSeconds(120)) == 0, "Secure MySQL initialization failed; private artifacts retained.");
            List<String> serverArgs = new ArrayList<>(baseArgs);
            serverArgs.addAll(List.of("--skip-networking", "--mysqlx=OFF", "--skip-log-bin", "--partial-revokes=ON",
                    "--socket=" + plan.socket(), "--pid-file=" + plan.data.resolve("server.pid"),
                    "--port=" + plan.port, "--log-error=" + log, "--init-file=" + init));
            // mysqld already owns its private error log; stdout must not truncate it
            // or race with the bounded client's output file.
            daemon = process(serverArgs, log).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            String identity = "";
            while (daemon.isAlive() && System.nanoTime() < deadline) {
                if (Files.exists(plan.socket(), NO_LINKS)) {
                    List<String> clientArgs = client(plan, "mysql");
                    clientArgs.addAll(List.of("--batch", "--skip-column-names", "-e", "SELECT @@datadir,@@partial_revokes"));
                    if (run(clientArgs, output, Duration.ofSeconds(3)) == 0) {
                        require(Files.size(output) <= 65_536, "Database identity output exceeded its limit.");
                        identity = Files.readString(output).strip();
                        if (identity.equals(plan.data + "/\t1")) break;
                    }
                }
                Thread.sleep(200);
            }
            require(daemon.isAlive() && identity.equals(plan.data + "/\t1"), "Fresh database identity could not be verified; private artifacts retained.");
            List<String> verify = client(plan, "mysql");
            verify.addAll(List.of("--batch", "--skip-column-names", "-e",
                    "SELECT COUNT(*) FROM mysql.user WHERE (user='" + plan.username + "' OR user='" + MIGRATOR + "') AND host='127.0.0.1'"));
            require(run(verify, output, Duration.ofSeconds(5)) == 0 && Files.readString(output).strip().equals("2"),
                    "Fresh least-privilege identities could not be verified.");
            List<String> shutdown = client(plan, "mysqladmin");
            shutdown.add("shutdown");
            require(run(shutdown, output, Duration.ofSeconds(15)) == 0 && daemon.waitFor(15, TimeUnit.SECONDS)
                    && daemon.exitValue() == 0, "Fresh database did not stop cleanly; private artifacts retained.");
            // --no-defaults intentionally ignores persisted globals. The normal
            // project launcher must explicitly apply the fresh-server policy.
            privateFile(plan.runtime.resolve("mysql-bootstrap.complete"), "prepared-local-mysql-v1\n");
            // This file is created exclusively by this invocation, never an operator file.
            Files.delete(init);
            Files.delete(output);
            Files.delete(lock);
            verified = true;
            return new Result(plan.data, plan.admin(), plan.migration());
        } finally {
            if (daemon != null && daemon.isAlive()) {
                // Only our Process handle, never a discovered PID or port owner.
                daemon.destroy();
                if (!daemon.waitFor(15, TimeUnit.SECONDS)) daemon.destroyForcibly();
            }
            // On failure preserve the lock, data, credentials and private diagnostics.
            // Recovery is explicit operator work, not blind reset/delete/retry.
            if (!verified) System.err.println("Fresh initialization did not complete. Existing services were not changed; inspect private project artifacts.");
        }
    }

    private static String initializationSql(Plan plan, String rootPassword, String migrationPassword) {
        // SQL account/database identifiers cannot use bind parameters. Every interpolated
        // value is a strict whitelist or internally generated URL-safe random secret.
        return "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + rootPassword + "';\n"
                + "SET PERSIST partial_revokes=ON;\n"
                + "CREATE DATABASE `" + plan.schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n"
                + "CREATE USER '" + plan.username + "'@'127.0.0.1' IDENTIFIED BY '" + plan.password + "' REQUIRE SSL;\n"
                + "CREATE USER '" + MIGRATOR + "'@'127.0.0.1' IDENTIFIED BY '" + migrationPassword + "' REQUIRE SSL;\n"
                + "GRANT SELECT,INSERT,UPDATE,DELETE ON `" + plan.schema + "`.* TO '" + plan.username + "'@'127.0.0.1';\n"
                + "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,DROP,INDEX,REFERENCES,CREATE ROUTINE,ALTER ROUTINE,EXECUTE ON `" + plan.schema + "`.* TO '" + MIGRATOR + "'@'127.0.0.1';\n";
    }

    private static List<String> client(Plan plan, String binary) {
        return new ArrayList<>(List.of(plan.base.resolve("bin/" + binary).toString(),
                "--defaults-file=" + plan.admin(), "--no-login-paths", "--connect-timeout=2"));
    }

    private static ProcessBuilder process(List<String> arguments, Path privateOutput) {
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(privateOutput.toFile());
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
        builder.environment().put("LC_ALL", "C");
        return builder;
    }

    private static int run(List<String> arguments, Path output, Duration timeout) throws Exception {
        Process command = process(arguments, output).start();
        try {
            require(command.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS), "Database command timed out; private artifacts retained.");
            return command.exitValue();
        } finally {
            if (command.isAlive()) { command.destroy(); if (!command.waitFor(2, TimeUnit.SECONDS)) command.destroyForcibly(); }
        }
    }

    private static String clientConfiguration(String user, String password, Path socket, int port) {
        String transport = socket != null ? "protocol=SOCKET\nsocket=" + quoted(socket.toString()) + "\n"
                : "protocol=TCP\nhost=127.0.0.1\nport=" + port + "\nssl-mode=REQUIRED\n";
        return "[client]\nuser=" + quoted(user) + "\npassword=" + quoted(password) + "\n" + transport;
    }

    private static String quoted(String value) {
        require(value.indexOf('\n') < 0 && value.indexOf('\r') < 0, "Configuration path contains an invalid character.");
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String secret() {
        byte[] bytes = new byte[36]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void privateDirectory(Path path) throws IOException {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private static void privateFile(Path path, String content) throws IOException {
        try (var stream = Files.newByteChannel(path, java.util.Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var bytes = java.nio.ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) stream.write(bytes);
        }
    }

    private static void noLinksBetween(Path root, Path leaf) {
        Path current = root;
        for (Path part : root.relativize(leaf)) {
            current = current.resolve(part);
            require(!Files.isSymbolicLink(current), "Project configuration and database paths must not be symbolic links.");
        }
    }

    private static void ownerOnly(Path path, Path root, boolean directory) throws IOException {
        require((directory ? Files.isDirectory(path, NO_LINKS) : Files.isRegularFile(path, NO_LINKS))
                        && Files.getOwner(path, NO_LINKS).equals(Files.getOwner(root, NO_LINKS))
                        && Files.getPosixFilePermissions(path, NO_LINKS).equals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")),
                "Dedicated configuration/runtime must be owner-only with the project owner.");
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        require(value != null && !value.isBlank(), "Missing dedicated setting: " + name);
        return value;
    }

    private static int port(String value) {
        require(value.matches("[1-9][0-9]{3,4}"), "Invalid unprivileged port.");
        int port = Integer.parseInt(value);
        require(port >= 1024 && port <= 65535, "Invalid unprivileged port.");
        return port;
    }

    private static void require(boolean condition, String safeMessage) {
        if (!condition) throw new IllegalArgumentException(safeMessage);
    }

    public static final class Plan {
        private final Path root, runtime, data, base;
        private final String schema, username, password;
        private final int port;
        private Plan(Path root, Path runtime, Path data, Path base, String schema, int port, String username, String password) {
            this.root = root; this.runtime = runtime; this.data = data; this.base = base;
            this.schema = schema; this.port = port; this.username = username; this.password = password;
        }
        public Path data() { return data; }
        public Path root() { return root; }
        public Path runtime() { return runtime; }
        public Path base() { return base; }
        public Path socket() { return data.resolve("mysql.sock"); }
        public Path admin() { return runtime.resolve("mysql-admin.cnf"); }
        public Path migration() { return runtime.resolve("mysql-migration.cnf"); }
        public String schema() { return schema; }
        public int port() { return port; }
        String applicationPassword() { return password; }
        @Override public String toString() { return "FreshPreparedMysqlPlan[credentials=REDACTED]"; }
    }

    public record Result(Path data, Path adminConfiguration, Path migrationConfiguration) {}

    public static void main(String[] args) {
        try {
            require(args.length == 1, "Exactly one prepared project path is required.");
            initialize(Path.of(args[0]), System.getenv());
            System.out.println("Fresh independent MySQL initialized and stopped. Run migrations with the private migration identity before application startup.");
        } catch (Exception failure) {
            // Never emit exception messages/stack traces which may contain file/SQL secrets.
            System.err.println("Initialization refused or failed. No existing server was modified; inspect owner-only project diagnostics.");
            System.exit(1);
        }
    }
}
