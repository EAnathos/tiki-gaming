package fr.tikigaming.games.tic_tac_toe;

import fr.tikigaming.config.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Le serveur fait autorité : il rejoue les coups envoyés par le front sur le moteur du jeu, refuse une partie
 * truquée (422) et archive le résultat vérifié (anti-triche avant envoi du score au classement).
 */
@Service
public class TicTacToeVerifyService {

    static final String NOT_FINISHED = "La partie n'est pas terminée.";

    private final SupabaseTicTacToeResultsClient results;

    public TicTacToeVerifyService(SupabaseTicTacToeResultsClient results) {
        this.results = results;
    }

    public TicTacToeVerifyResponse verify(List<TicTacToeMove> moves) {
        TicTacToeGame game = new TicTacToeGame();
        try {
            for (TicTacToeMove move : moves) {
                game.place(move.row(), move.column());
            }
        } catch (IllegalArgumentException | IllegalStateException refused) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, refused.getMessage());
        }
        if (game.status() == GameStatus.IN_PROGRESS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, NOT_FINISHED);
        }
        results.record(game.status(), moves.size());
        return TicTacToeVerifyResponse.of(game, moves.size());
    }
}
