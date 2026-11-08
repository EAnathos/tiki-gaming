import type { TicTacToeCell, TicTacToeVerification } from './types'

/** X aligne la ligne du haut en 5 coups. */
export const X_WINNING_MOVES: TicTacToeCell[] = [
  { row: 0, column: 0 },
  { row: 1, column: 0 },
  { row: 0, column: 1 },
  { row: 1, column: 1 },
  { row: 0, column: 2 },
]

export const X_WON: TicTacToeVerification = { status: 'X_WON', winner: 'X', moves: 5 }

/** Partie truquée : la même case est jouée deux fois. */
export const CHEATED_MOVES: TicTacToeCell[] = [
  { row: 0, column: 0 },
  { row: 0, column: 0 },
]
