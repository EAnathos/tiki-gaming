package fr.tikigaming.games.tic_tac_toe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.tikigaming.config.ApiException;
import fr.tikigaming.resilience.CircuitBreaker;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Intégration réelle de l'archivage : vrai PostgREST devant un vrai PostgreSQL (même image que la production),
 * migrations du dépôt rejouées, RLS réelle. Le client porte le JWT service_role, comme en production.
 *
 * <p>Tourne avec le profil {@code integration} (Docker obligatoire) : {@code mvn -Pintegration verify}.
 */
class SupabaseTicTacToeResultsClientIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TicTacToeDatabase DATABASE = new TicTacToeDatabase();
    private static SupabaseTicTacToeResultsClient serviceClient;
    private static SupabaseTicTacToeResultsClient anonClient;

    @BeforeAll
    static void startContainersAndApplyEveryMigration() throws Exception {
        DATABASE.start();
        serviceClient = client(DATABASE.token("service_role"));
        anonClient = client(DATABASE.token("anon"));
    }

    private static SupabaseTicTacToeResultsClient client(String jwt) {
        // PostgREST du conteneur sert à la racine : pas de préfixe /rest/v1 (réservé à Kong en production).
        RestClient rest = RestClient.builder()
                .baseUrl(DATABASE.restUrl())
                .defaultHeaders(h -> h.setBearerAuth(jwt))
                .build();
        return new SupabaseTicTacToeResultsClient(
                rest,
                new CircuitBreaker(
                        5, Duration.ofSeconds(30), Clock.systemUTC(), SupabaseTicTacToeResultsClient::isOutage));
    }

    @AfterAll
    static void stopContainers() {
        DATABASE.close();
    }

    @BeforeEach
    void isolateTestData() throws SQLException {
        try (Connection connection = DATABASE.connect();
                var statement = connection.createStatement()) {
            statement.execute("truncate public.tic_tac_toe_results");
        }
    }

    private static long countRows() throws SQLException {
        try (Connection connection = DATABASE.connect();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "select count(*), max(status), max(moves) from public.tic_tac_toe_results")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void archivesAVverifiedResultWithTheServiceRole() throws Exception {
        serviceClient.record(GameStatus.X_WON, 5);

        try (Connection connection = DATABASE.connect();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("select status, moves from public.tic_tac_toe_results")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("status")).isEqualTo("X_WON");
            assertThat(rows.getInt("moves")).isEqualTo(5);
        }
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void theAnonymousRoleCanNeitherReadNorWriteTheResults() throws Exception {
        // PostgREST refuse l'insertion (RLS : aucun droit pour anon) → traduit en panne 502 côté client.
        assertThatThrownBy(() -> anonClient.record(GameStatus.O_WON, 7))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e).hasMessage(SupabaseTicTacToeResultsClient.UNAVAILABLE);
                });

        // En SQL direct, la RLS bloque aussi la lecture pour anon et authenticated.
        for (String role : new String[] {"anon", "authenticated"}) {
            try (Connection connection = DATABASE.connect();
                    var statement = connection.createStatement()) {
                statement.execute("set role " + role);
                assertThatThrownBy(() -> statement.executeQuery("select count(*) from public.tic_tac_toe_results"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
        }
        assertThat(countRows()).isZero();
    }

    @Test
    void theDatabaseConstraintsRejectInvalidRows() throws SQLException {
        try (Connection connection = DATABASE.connect();
                var statement = connection.createStatement()) {
            // Une partie vérifiée est terminée : IN_PROGRESS est refusé.
            assertThatThrownBy(() -> statement.execute(
                            "insert into public.tic_tac_toe_results (status, moves) values ('IN_PROGRESS', 3)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("tic_tac_toe_results_status_check");
            // Entre 1 et 9 coups.
            assertThatThrownBy(() -> statement.execute(
                            "insert into public.tic_tac_toe_results (status, moves) values ('DRAW', 10)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("tic_tac_toe_results_moves_check");
            assertThatThrownBy(() -> statement.execute(
                            "insert into public.tic_tac_toe_results (status, moves) values ('X_WON', 0)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("tic_tac_toe_results_moves_check");
        }
        assertThat(countRows()).isZero();
    }

    @Test
    void postgrestServesTheArchivedRowsToTheServiceRole() throws Exception {
        serviceClient.record(GameStatus.DRAW, 9);

        String body = RestClient.create(DATABASE.restUrl())
                .get()
                .uri("/tic_tac_toe_results?select=status,moves")
                .header("Authorization", "Bearer " + DATABASE.token("service_role"))
                .retrieve()
                .body(String.class);
        var rows = JSON.readTree(body);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("status").asString()).isEqualTo("DRAW");
        assertThat(rows.get(0).path("moves").asInt()).isEqualTo(9);
    }
}
