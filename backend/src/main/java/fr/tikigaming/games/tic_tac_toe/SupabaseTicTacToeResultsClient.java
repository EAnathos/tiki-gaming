package fr.tikigaming.games.tic_tac_toe;

import fr.tikigaming.config.ApiException;
import fr.tikigaming.config.TikiProperties;
import fr.tikigaming.resilience.CircuitBreaker;
import fr.tikigaming.resilience.CircuitOpenException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Archive les parties de Tic-Tac-Toe vérifiées dans public.tic_tac_toe_results via PostgREST, avec la clé
 * service_role : c'est le seul chemin d'écriture (la RLS refuse tout aux joueurs).
 *
 * <p>Résilience : les timeouts de connexion et de lecture viennent de {@code spring.http.clients.*}
 * (application.yml) ; un disjoncteur s'ouvre après plusieurs pannes consécutives et répond alors 503 tout de
 * suite, sans attendre Supabase.
 */
@Component
public class SupabaseTicTacToeResultsClient {

    static final String UNAVAILABLE = "Le service de vérification rencontre un problème. Réessaie plus tard.";

    private static final Logger log = LoggerFactory.getLogger(SupabaseTicTacToeResultsClient.class);

    private final RestClient rest;
    private final CircuitBreaker breaker;

    @Autowired
    public SupabaseTicTacToeResultsClient(
            RestClient.Builder builder,
            TikiProperties props,
            ObjectMapper mapper,
            @Value("${tiki.resilience.tic-tac-toe.failure-threshold:5}") int failureThreshold,
            @Value("${tiki.resilience.tic-tac-toe.open-duration:30s}") Duration openDuration) {
        this(
                builder,
                props,
                mapper,
                new CircuitBreaker(
                        failureThreshold, openDuration, Clock.systemUTC(), SupabaseTicTacToeResultsClient::isOutage));
    }

    SupabaseTicTacToeResultsClient(
            RestClient.Builder builder, TikiProperties props, ObjectMapper mapper, CircuitBreaker breaker) {
        this.breaker = breaker;
        String serviceRoleKey = props.supabase().serviceRoleKey();
        this.rest = builder.baseUrl(props.supabase().url())
                .defaultHeaders(h -> {
                    h.set("apikey", serviceRoleKey);
                    h.setBearerAuth(serviceRoleKey);
                })
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                    log.warn(
                            "PostgREST a refusé l'archivage d'une partie de Tic-Tac-Toe : {}",
                            readError(mapper, res.getBody()));
                    throw new ApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE);
                })
                .build();
    }

    /** Archive le résultat d'une partie vérifiée. */
    public void record(GameStatus status, int moves) {
        try {
            breaker.call(() -> post(status, moves));
        } catch (CircuitOpenException e) {
            log.warn(
                    "Disjoncteur de vérification ouvert : résultat non archivé sans appeler Supabase (essai dans {})",
                    e.retryAfter());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        }
    }

    /** Panne de Supabase (injoignable, timeout, 5xx, refus RLS), par opposition à un refus métier de la partie. */
    static boolean isOutage(RuntimeException e) {
        return e instanceof ApiException api && api.status() == HttpStatus.BAD_GATEWAY;
    }

    private Void post(GameStatus status, int moves) {
        try {
            rest.post()
                    .uri("/rest/v1/tic_tac_toe_results")
                    .header("Prefer", "return=minimal")
                    .body(Map.of("status", status.name(), "moves", moves))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        } catch (ResourceAccessException e) {
            log.error("Supabase injoignable pendant l'archivage d'une partie de Tic-Tac-Toe", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE);
        }
    }

    /** Code SQLSTATE (ou PGRST) et message de l'erreur PostgREST ; journalisé, jamais renvoyé au client. */
    private static String readError(ObjectMapper mapper, InputStream body) {
        try {
            JsonNode json = mapper.readTree(body);
            if (json == null) return "";
            return json.path("code").asString("") + " " + json.path("message").asString("");
        } catch (JacksonException ignored) {
            return "";
        }
    }
}
