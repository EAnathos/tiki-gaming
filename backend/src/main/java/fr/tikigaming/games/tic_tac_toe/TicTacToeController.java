package fr.tikigaming.games.tic_tac_toe;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import fr.tikigaming.config.ApiException;
import fr.tikigaming.config.ApiExceptionHandler.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/games/tic-tac-toe")
@Tag(name = "tic-tac-toe", description = "Vérification serveur d'une partie de Tic-Tac-Toe (anti-triche).")
@ApiResponse(
        responseCode = "400",
        description = "Corps invalide (coups absents, coordonnées hors grille…)",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "403", description = "Jeton CSRF absent ou invalide")
public class TicTacToeController {

    /** Une case visée par un coup. */
    @Schema(name = "TicTacToeCellRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record CellRequest(
            @Schema(example = "0")
            @NotNull(message = "Indique la ligne.")
            @Min(value = 0, message = "La ligne doit être comprise entre 0 et 2.")
            @Max(value = 2, message = "La ligne doit être comprise entre 0 et 2.")
            Integer row,

            @Schema(example = "0")
            @NotNull(message = "Indique la colonne.")
            @Min(value = 0, message = "La colonne doit être comprise entre 0 et 2.")
            @Max(value = 2, message = "La colonne doit être comprise entre 0 et 2.")
            Integer column) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw unexpectedField();
        }
    }

    /** Partie terminée envoyée par le front : la liste des coups joués, dans l'ordre. */
    @Schema(name = "TicTacToeVerifyRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record VerifyRequest(
            @NotNull(message = "La liste des coups est obligatoire.")
            @Size(min = 1, max = 9, message = "Une partie fait entre 1 et 9 coups.")
            List<@Valid @NotNull CellRequest> moves) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw unexpectedField();
        }
    }

    private final TicTacToeVerifyService service;
    private final TicTacToeVerifyRateLimiter rateLimiter;

    public TicTacToeController(TicTacToeVerifyService service, TicTacToeVerifyRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/verify")
    @Operation(
            summary = "Vérifier une partie terminée",
            description = "Le serveur rejoue les coups sur le moteur du jeu et calcule lui-même le résultat ; "
                    + "la partie vérifiée est archivée côté serveur avant que le front n'envoie un score.")
    @ApiResponse(responseCode = "200", description = "Partie valide : statut et gagnant calculés par le serveur")
    @ApiResponse(
            responseCode = "422",
            description = "Partie refusée : coup illégal ou partie non terminée",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "429",
            description = "Trop de parties envoyées par ce client en une minute",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    ResponseEntity<TicTacToeVerifyResponse> verify(@Valid @RequestBody VerifyRequest body, HttpServletRequest http) {

        // Derrière le proxy, l'adresse du joueur vient de X-Forwarded-For (server.forward-headers-strategy).
        if (!rateLimiter.tryAcquire(http.getRemoteAddr())) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "Trop de parties envoyées : réessaie dans une minute.");
        }
        List<TicTacToeMove> moves = body.moves().stream()
                .map(cell -> new TicTacToeMove(cell.row(), cell.column()))
                .toList();
        return ResponseEntity.ok(service.verify(moves));
    }

    private static IllegalArgumentException unexpectedField() {
        return new IllegalArgumentException("Cette requête contient un champ inattendu.");
    }
}
