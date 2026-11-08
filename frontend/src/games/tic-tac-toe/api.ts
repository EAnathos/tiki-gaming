import { apiPost } from '@/lib/api'
import type { TicTacToeCell, TicTacToeVerification } from './types'

const ROOT = '/games/tic-tac-toe/verify'

/**
 * Envoie la liste des coups d'une partie terminée ; le serveur la rejoue sur son propre moteur et renvoie le
 * résultat calculé (anti-triche avant envoi d'un score au classement).
 */
export function verifyMatch(moves: TicTacToeCell[]): Promise<TicTacToeVerification> {
  return apiPost<TicTacToeVerification>(ROOT, { moves })
}
