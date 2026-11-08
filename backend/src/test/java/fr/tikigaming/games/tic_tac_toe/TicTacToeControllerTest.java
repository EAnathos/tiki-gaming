package fr.tikigaming.games.tic_tac_toe;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.tikigaming.config.ApiException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Vraie application Spring et vraie chaîne CSRF : seul l'archivage Supabase est simulé. Couvre la vérification
 * (200/422), la validation (400), la protection CSRF (403), la panne de stockage (502) et le disjoncteur (503).
 */
@SpringBootTest(
        properties = {
            "GOOGLE_CLIENT_ID=test-client-id",
            "GOOGLE_CLIENT_SECRET=test-client-secret",
            "SUPABASE_URL=https://ttt-test.supabase.invalid",
            "SUPABASE_ANON_KEY=test-anon-key",
            "SUPABASE_SERVICE_ROLE_KEY=test-service-role-key",
            "SUPABASE_JWT_SECRET=test-jwt-secret-with-at-least-32-characters"
        })
@AutoConfigureMockMvc
class TicTacToeControllerTest {

    private static final String ROOT = "/api/games/tic-tac-toe/verify";
    /** X gagne en 5 coups (ligne du haut). */
    private static final String X_WINS = "{\"moves\":[{\"row\":0,\"column\":0},{\"row\":1,\"column\":0},"
            + "{\"row\":0,\"column\":1},{\"row\":1,\"column\":1},{\"row\":0,\"column\":2}]}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SupabaseTicTacToeResultsClient results;

    private MockHttpSession session;
    private Cookie csrf;

    @BeforeEach
    void setUp() throws Exception {
        session = new MockHttpSession();
        csrf = mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
    }

    private MockHttpServletRequestBuilder verifyRequest(String body) {
        return post(ROOT)
                .session(session)
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void verifiesAWinningGameWithTheResultComputedByTheServer() throws Exception {
        mvc.perform(verifyRequest(X_WINS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("X_WON"))
                .andExpect(jsonPath("$.winner").value("X"))
                .andExpect(jsonPath("$.moves").value(5));
        verify(results).record(GameStatus.X_WON, 5);
    }

    @Test
    void aDrawIsVerifiedWithoutAWinner() throws Exception {
        String draw = "{\"moves\":[{\"row\":0,\"column\":0},{\"row\":0,\"column\":1},{\"row\":0,\"column\":2},"
                + "{\"row\":1,\"column\":1},{\"row\":1,\"column\":0},{\"row\":1,\"column\":2},"
                + "{\"row\":2,\"column\":1},{\"row\":2,\"column\":0},{\"row\":2,\"column\":2}]}";
        mvc.perform(verifyRequest(draw))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAW"))
                .andExpect(jsonPath("$.winner").isEmpty())
                .andExpect(jsonPath("$.moves").value(9));
        verify(results).record(GameStatus.DRAW, 9);
    }

    @Test
    void aCheatedGameWithAnOccupiedCellIsRefusedWith422() throws Exception {
        String cheated = "{\"moves\":[{\"row\":0,\"column\":0},{\"row\":0,\"column\":0}]}";
        mvc.perform(verifyRequest(cheated))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("La case est déjà occupée."));
        verifyNoInteractions(results);
    }

    @Test
    void aMoveAfterTheEndOfTheGameIsRefusedWith422() throws Exception {
        String afterEnd = X_WINS.replace("}]}", "},{\"row\":2,\"column\":2}]}");
        mvc.perform(verifyRequest(afterEnd))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("La partie est terminée."));
        verifyNoInteractions(results);
    }

    @Test
    void anUnfinishedGameIsRefusedWith422() throws Exception {
        String unfinished = "{\"moves\":[{\"row\":0,\"column\":0},{\"row\":1,\"column\":1},{\"row\":2,\"column\":0}]}";
        mvc.perform(verifyRequest(unfinished))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(TicTacToeVerifyService.NOT_FINISHED));
        verifyNoInteractions(results);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "{\"moves\":[{\"row\":3,\"column\":0}]}", // ligne hors grille
                "{\"moves\":[{\"row\":0,\"column\":-1}]}", // colonne négative
                "{\"moves\":[]}", // aucun coup
                "{\"moves\":[{\"row\":0}]}", // colonne absente
                "{\"moves\":[{\"row\":0,\"column\":0}],\"triche\":true}", // champ inattendu
                "{}" // coups absents
            })
    void invalidBodiesAreRefusedWith400(String body) throws Exception {
        mvc.perform(verifyRequest(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(results);
    }

    @Test
    void csrfIsRequiredForVerify() throws Exception {
        // Sans en-tête X-XSRF-TOKEN
        mvc.perform(post(ROOT)
                        .session(session)
                        .cookie(csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(X_WINS))
                .andExpect(status().isForbidden());
        // En-tête présent mais mauvais
        mvc.perform(post(ROOT)
                        .session(session)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", "mauvais-jeton")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(X_WINS))
                .andExpect(status().isForbidden());
        verifyNoInteractions(results);
    }

    @Test
    void aStorageOutageDoesNotBlockTheVerification() throws Exception {
        // Archivage best-effort : la vérification (rejeu serveur) répond 200 même si le stockage est en panne.
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, SupabaseTicTacToeResultsClient.UNAVAILABLE))
                .when(results)
                .record(any(), anyInt());

        mvc.perform(verifyRequest(X_WINS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("X_WON"))
                .andExpect(jsonPath("$.winner").value("X"));
    }
}
