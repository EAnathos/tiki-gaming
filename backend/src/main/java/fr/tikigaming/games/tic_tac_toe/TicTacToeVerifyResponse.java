package fr.tikigaming.games.tic_tac_toe;

import io.swagger.v3.oas.annotations.media.Schema;

/** Résultat d'une partie vérifiée par le serveur : le navigateur ne calcule jamais le gagnant. */
@Schema(name = "TicTacToeVerifyResponse")
public record TicTacToeVerifyResponse(
        @Schema(example = "X_WON") GameStatus status,
        @Schema(example = "X") Mark winner,
        @Schema(example = "5") int moves) {

    static TicTacToeVerifyResponse of(TicTacToeGame game, int moves) {
        Mark winner = switch (game.status()) {
            case X_WON -> Mark.X;
            case O_WON -> Mark.O;
            case DRAW, IN_PROGRESS -> null;
        };
        return new TicTacToeVerifyResponse(game.status(), winner, moves);
    }
}
