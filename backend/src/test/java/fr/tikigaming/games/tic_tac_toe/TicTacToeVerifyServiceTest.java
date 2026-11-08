package fr.tikigaming.games.tic_tac_toe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;

import fr.tikigaming.config.ApiException;
import fr.tikigaming.config.TikiProperties;
import fr.tikigaming.resilience.CircuitBreaker;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Le service rejoue les coups sur le vrai moteur ; le client d'archivage est réel mais branché sur un faux
 * PostgREST (MockRestServiceServer) : une requête inattendue fait échouer le test.
 */
class TicTacToeVerifyServiceTest {

    private static final String URL = "https://ttt-test.supabase.co";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MockRestServiceServer server;
    private TicTacToeVerifyService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        TikiProperties props = new TikiProperties(
                "http://localhost:5173",
                new TikiProperties.Supabase(URL, "anon", "service-key", "jwt-secret", Duration.ofHours(1)));
        SupabaseTicTacToeResultsClient client = new SupabaseTicTacToeResultsClient(
                builder,
                props,
                JSON,
                new CircuitBreaker(
                        5, Duration.ofSeconds(30), Clock.systemUTC(), SupabaseTicTacToeResultsClient::isOutage));
        service = new TicTacToeVerifyService(client);
    }

    private static List<TicTacToeMove> moves(int... coordinates) {
        List<TicTacToeMove> moves = new ArrayList<>();
        for (int i = 0; i + 1 < coordinates.length; i += 2) {
            moves.add(new TicTacToeMove(coordinates[i], coordinates[i + 1]));
        }
        return moves;
    }

    private void expectArchiving(String status, int moves) {
        server.expect(requestTo(URL + "/rest/v1/tic_tac_toe_results"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"status\":\"" + status + "\",\"moves\":" + moves + "}"))
                .andRespond(withCreatedEntity(URI.create(URL + "/rest/v1/tic_tac_toe_results?id=eq.1")));
    }

    @Test
    void replaysAWinningGameAndArchivesTheServerSideResult() {
        // Arrange : X aligne la ligne du haut en 5 coups
        expectArchiving("X_WON", 5);

        // Act
        TicTacToeVerifyResponse response = service.verify(moves(0, 0, 1, 0, 0, 1, 1, 1, 0, 2));

        // Assert
        assertThat(response.status()).isEqualTo(GameStatus.X_WON);
        assertThat(response.winner()).isEqualTo(Mark.X);
        assertThat(response.moves()).isEqualTo(5);
        server.verify();
    }

    @Test
    void acceptsADrawAndArchivesItWithoutAWinner() {
        // Arrange : grille pleine sans ligne gagnante (9 coups)
        expectArchiving("DRAW", 9);

        // Act
        TicTacToeVerifyResponse response = service.verify(moves(0, 0, 0, 1, 0, 2, 1, 1, 1, 0, 1, 2, 2, 1, 2, 0, 2, 2));

        // Assert
        assertThat(response.status()).isEqualTo(GameStatus.DRAW);
        assertThat(response.winner()).isNull();
        assertThat(response.moves()).isEqualTo(9);
        server.verify();
    }

    @Test
    void anOccupiedCellIsRefusedWith422AndNothingIsArchived() {
        // Act + Assert : aucun appel au stockage n'est attendu (MockRestServiceServer échoue sinon)
        assertThatThrownBy(() -> service.verify(moves(0, 0, 0, 0))).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(e.getMessage()).isEqualTo("La case est déjà occupée.");
        });
        server.verify();
    }

    @Test
    void aMoveAfterTheEndOfTheGameIsRefusedWith422() {
        // Arrange : X gagne en 5 coups, puis un 6e coup est tenté
        assertThatThrownBy(() -> service.verify(moves(0, 0, 1, 0, 0, 1, 1, 1, 0, 2, 2, 2)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.getMessage()).isEqualTo("La partie est terminée.");
                });
        server.verify();
    }

    @Test
    void anUnfinishedGameIsRefusedWith422() {
        assertThatThrownBy(() -> service.verify(moves(0, 0, 1, 1, 2, 0)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.getMessage()).isEqualTo(TicTacToeVerifyService.NOT_FINISHED);
                });
        server.verify();
    }
}
