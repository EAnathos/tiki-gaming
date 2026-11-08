package fr.tikigaming.games.tic_tac_toe;

import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Vérification fournisseur du contrat Pact écrit par le front du Tic-Tac-Toe (frontend/pacts,
 * tests/contract/tic-tac-toe.pact.test.ts). Chaque interaction est rejouée en HTTP contre l'application Spring
 * complète (sécurité, CSRF, validation, moteur réel) ; seul l'archivage Supabase est simulé. Un champ renommé ou
 * un statut changé côté backend fait échouer ce test.
 */
@Provider("tiki-tic-tac-toe-backend")
@PactFolder("../frontend/pacts")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "GOOGLE_CLIENT_ID=test-client-id",
            "GOOGLE_CLIENT_SECRET=test-client-secret",
            "SUPABASE_URL=https://pact-ttt.supabase.invalid",
            "SUPABASE_ANON_KEY=test-anon-key",
            "SUPABASE_SERVICE_ROLE_KEY=test-service-role-key",
            "SUPABASE_JWT_SECRET=test-jwt-secret-with-at-least-32-characters"
        })
class TicTacToePactProviderTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @MockitoBean
    SupabaseTicTacToeResultsClient results;

    /** Cookies de la session ouverte par l'état du contrat, et jeton CSRF qui va avec. */
    private final Map<String, String> cookies = new LinkedHashMap<>();

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifieLeContratDuFrontTicTacToe(PactVerificationContext context, ClassicHttpRequest request) {
        // Le contrat porte un jeton CSRF d'exemple : on envoie celui de la session ouverte par l'état.
        request.setHeader(
                "Cookie",
                cookies.entrySet().stream()
                        .map(c -> c.getKey() + "=" + c.getValue())
                        .collect(Collectors.joining("; ")));
        if (request.containsHeader("X-XSRF-TOKEN")) request.setHeader("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        context.verifyInteraction();
    }

    @State("aucun joueur n’est connecté")
    void aGuest() throws Exception {
        assertThat(send(HttpRequest.newBuilder(url("/api/auth/session")).GET()).statusCode())
                .isEqualTo(200);
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** Envoie la requête avec les cookies connus, puis retient ceux que le backend pose. */
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        if (!cookies.isEmpty()) {
            request.header(
                    "Cookie",
                    cookies.entrySet().stream()
                            .map(c -> c.getKey() + "=" + c.getValue())
                            .collect(Collectors.joining("; ")));
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        for (String header : response.headers().allValues("Set-Cookie")) {
            String[] pair = header.split(";", 2)[0].split("=", 2);
            cookies.put(pair[0], pair[1]);
        }
        return response;
    }
}
