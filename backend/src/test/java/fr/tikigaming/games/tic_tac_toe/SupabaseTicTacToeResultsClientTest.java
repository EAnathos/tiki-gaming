package fr.tikigaming.games.tic_tac_toe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import fr.tikigaming.config.ApiException;
import fr.tikigaming.config.TikiProperties;
import fr.tikigaming.resilience.CircuitBreaker;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Requête d'archivage envoyée à PostgREST et traduction de ses pannes, sans réseau. */
class SupabaseTicTacToeResultsClientTest {

    private static final String URL = "https://ttt-ref.supabase.co";
    private static final String SERVICE = "service-role-key";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int OUTAGES_BEFORE_OPENING = 2;

    private MockRestServiceServer server;
    private CircuitBreaker breaker;
    private SupabaseTicTacToeResultsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        TikiProperties props = new TikiProperties(
                "http://localhost:5173",
                new TikiProperties.Supabase(URL, "anon-key", SERVICE, "jwt-secret", Duration.ofHours(1)));
        breaker = new CircuitBreaker(
                OUTAGES_BEFORE_OPENING,
                Duration.ofSeconds(30),
                Clock.systemUTC(),
                SupabaseTicTacToeResultsClient::isOutage);
        client = new SupabaseTicTacToeResultsClient(builder, props, JSON, breaker);
    }

    private void recordXWin() {
        client.record(GameStatus.X_WON, 5);
    }

    @Test
    void archivesTheResultWithTheServiceRoleKey() {
        server.expect(requestTo(URL + "/rest/v1/tic_tac_toe_results"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", SERVICE))
                .andExpect(header("Authorization", "Bearer " + SERVICE))
                .andExpect(header("Prefer", "return=minimal"))
                .andExpect(content().json("{\"status\":\"X_WON\",\"moves\":5}"))
                .andRespond(withCreatedEntity(URI.create(URL + "/rest/v1/tic_tac_toe_results?id=eq.1")));

        recordXWin();

        server.verify();
    }

    @Test
    void aServerErrorOfPostgrestBecomesABadGateway() {
        server.expect(requestTo(URL + "/rest/v1/tic_tac_toe_results"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("<html>indisponible</html>"));

        assertThatThrownBy(this::recordXWin)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void aDatabaseRefusalBecomesABadGateway() {
        // Contrainte CHECK ou RLS refusée : panne côté serveur, jamais un message interne au joueur.
        server.expect(requestTo(URL + "/rest/v1/tic_tac_toe_results"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(JSON.writeValueAsString(Map.of(
                                "code",
                                "23514",
                                "message",
                                "violates check constraint \"tic_tac_toe_results_status_check\""))));

        assertThatThrownBy(this::recordXWin).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(e).hasMessage(SupabaseTicTacToeResultsClient.UNAVAILABLE);
        });
    }

    @Test
    void anUnreachableSupabaseBecomesABadGateway() {
        server.expect(requestTo(URL + "/rest/v1/tic_tac_toe_results"))
                .andRespond(withException(new IOException("refusé")));

        assertThatThrownBy(this::recordXWin)
                .isInstanceOf(ApiException.class)
                .hasMessage(SupabaseTicTacToeResultsClient.UNAVAILABLE);
    }

    @Test
    void afterRepeatedOutagesTheCircuitOpensAndSupabaseIsNoLongerCalled() {
        server.expect(ExpectedCount.times(OUTAGES_BEFORE_OPENING), requestTo(URL + "/rest/v1/tic_tac_toe_results"))
                .andRespond(withException(new IOException("délai dépassé")));
        for (int i = 0; i < OUTAGES_BEFORE_OPENING; i++) {
            assertThatThrownBy(this::recordXWin)
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY));
        }

        // Disjoncteur ouvert : réponse immédiate, sans requête (sinon MockRestServiceServer échoue).
        assertThatThrownBy(this::recordXWin).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e).hasMessage(SupabaseTicTacToeResultsClient.UNAVAILABLE);
        });
        server.verify();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
