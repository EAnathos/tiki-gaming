package fr.tikigaming.games.tic_tac_toe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Deux services éphémères réels, ports aléatoires, aucune configuration de production lue. */
public final class TicTacToeDatabase implements AutoCloseable {

    private static final String PASSWORD = "postgres";
    private static final String SECRET = "tiki-ttt-integration-secret-0123456789";
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> database = new GenericContainer<>(
                    DockerImageName.parse("supabase/postgres:17.11.0.018"))
            .withNetwork(network)
            .withNetworkAliases("db")
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withExposedPorts(5432)
            // Le port s'ouvre dès le serveur temporaire de l'init : on attend le second « ready ».
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
            .withStartupTimeout(Duration.ofMinutes(3));
    private final GenericContainer<?> api = new GenericContainer<>(DockerImageName.parse("postgrest/postgrest:v16.5"))
            .withNetwork(network)
            .withEnv("PGRST_DB_URI", "postgres://authenticator:postgres@db:5432/postgres")
            .withEnv("PGRST_DB_SCHEMAS", "public")
            .withEnv("PGRST_DB_ANON_ROLE", "anon")
            .withEnv("PGRST_JWT_SECRET", SECRET)
            .withExposedPorts(3000)
            .waitingFor(Wait.forHttp("/").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(2));

    public void start() throws Exception {
        try {
            Path root = Path.of(System.getProperty("ticTacToe.repositoryRoot", ".."));
            // Ce script doit tourner à l'initialisation en supabase_admin, comme dans docker-compose.yml.
            database.withCopyFileToContainer(
                    MountableFile.forHostPath(root.resolve("quality/db-init/99999999999999_quality-setup.sql")),
                    "/docker-entrypoint-initdb.d/migrations/99999999999999_quality-setup.sql");
            database.start();
            try (Connection connection = connect();
                    var statement = connection.createStatement();
                    var files = Files.list(root.resolve("supabase/migrations"))) {
                connection.setAutoCommit(false);
                for (Path file : files.filter(p -> p.toString().endsWith(".sql"))
                        .sorted()
                        .toList()) {
                    statement.execute(Files.readString(file));
                    connection.commit();
                }
            }
            api.start();
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgresql://" + database.getHost() + ":" + database.getMappedPort(5432) + "/postgres",
                "postgres",
                PASSWORD);
    }

    public String restUrl() {
        return "http://" + api.getHost() + ":" + api.getMappedPort(3000);
    }

    /** Jeton signé avec le secret du conteneur, rôle demandé (`service_role`, `anon` ou `authenticated`). */
    public String token(String role) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = encoder.encodeToString(
                ("{\"role\":\"" + role + "\",\"exp\":4102444800}").getBytes(StandardCharsets.UTF_8));
        String unsigned = header + "." + claims;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return unsigned + "." + encoder.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void close() {
        try {
            api.close();
        } finally {
            try {
                database.close();
            } finally {
                network.close();
            }
        }
    }
}
