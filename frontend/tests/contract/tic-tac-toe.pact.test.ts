import path from 'node:path'

import { MatchersV3, PactV4 } from '@pact-foundation/pact'

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import { verifyMatch } from '@/games/tic-tac-toe/api'

import { CHEATED_MOVES, X_WINNING_MOVES, X_WON } from '@/games/tic-tac-toe/test-fixtures'

import type { TicTacToeVerification } from '@/games/tic-tac-toe/types'

vi.mock('@/lib/logger', () => ({ logger: { error: vi.fn() } }))

const { integer, like } = MatchersV3
const pact = new PactV4({
  consumer: 'tiki-tic-tac-toe-frontend',
  provider: 'tiki-tic-tac-toe-backend',
  dir: path.resolve(import.meta.dirname, '../../pacts'),
  logLevel: 'warn',
})
const ROOT = '/api/games/tic-tac-toe/verify'

const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': like('csrf-ttt') }

function shape(verification: TicTacToeVerification) {
  return {
    status: like(verification.status),
    winner: verification.winner === null ? null : like(verification.winner),
    moves: integer(verification.moves),
  }
}

function routeApiTo(url: string) {
  const fetch = globalThis.fetch
  vi.stubGlobal('fetch', (input: string, init?: RequestInit) => fetch(`${url}${input}`, init))
}

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=csrf-ttt'
})
afterEach(() => vi.unstubAllGlobals())

describe('contrat Tic-Tac-Toe', () => {
  test('vérification d’une partie gagnée par X', async () => {
    await pact
      .addInteraction()
      .given('aucun joueur n’est connecté')
      .uponReceiving('la vérification d’une partie de Tic-Tac-Toe gagnée par X')
      .withRequest('POST', ROOT, (req) => req.headers(headers).jsonBody({ moves: X_WINNING_MOVES }))
      .willRespondWith(200, (res) => res.jsonBody(shape(X_WON)))
      .executeTest(async (server) => {
        routeApiTo(server.url)
        await expect(verifyMatch(X_WINNING_MOVES)).resolves.toMatchObject({ status: 'X_WON', winner: 'X', moves: 5 })
      })
  })

  test('partie truquée refusée avec 422', async () => {
    await pact
      .addInteraction()
      .given('aucun joueur n’est connecté')
      .uponReceiving('la vérification d’une partie truquée (case déjà occupée)')
      .withRequest('POST', ROOT, (req) => req.headers(headers).jsonBody({ moves: CHEATED_MOVES }))
      .willRespondWith(422, (res) => res.jsonBody({ error: like('La case est déjà occupée.') }))
      .executeTest(async (server) => {
        routeApiTo(server.url)
        await expect(verifyMatch(CHEATED_MOVES)).rejects.toMatchObject({ status: 422 })
      })
  })

  test('partie non terminée refusée avec 422', async () => {
    const unfinished = [
      { row: 0, column: 0 },
      { row: 1, column: 1 },
      { row: 2, column: 0 },
    ]
    await pact
      .addInteraction()
      .given('aucun joueur n’est connecté')
      .uponReceiving('la vérification d’une partie non terminée')
      .withRequest('POST', ROOT, (req) => req.headers(headers).jsonBody({ moves: unfinished }))
      .willRespondWith(422, (res) => res.jsonBody({ error: like("La partie n'est pas terminée.") }))
      .executeTest(async (server) => {
        routeApiTo(server.url)
        await expect(verifyMatch(unfinished)).rejects.toMatchObject({ status: 422 })
      })
  })
})
