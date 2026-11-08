export type TicTacToeMark = 'X' | 'O'

export type TicTacToeStatus = 'IN_PROGRESS' | 'X_WON' | 'O_WON' | 'DRAW'

/** Une case visée par un coup, envoyée au serveur pour vérification. */
export type TicTacToeCell = { row: number; column: number }

/** Résultat d'une partie vérifiée par le serveur : le navigateur ne calcule ni le statut ni le gagnant. */
export type TicTacToeVerification = {
  status: TicTacToeStatus
  winner: TicTacToeMark | null
  moves: number
}
