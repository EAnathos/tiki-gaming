// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { verifyMatch } from './api'
import { CHEATED_MOVES, X_WINNING_MOVES, X_WON } from './test-fixtures'

vi.mock('@/lib/logger', () => ({ logger: { error: vi.fn() } }))
const fetchStub = vi.fn<typeof fetch>()
const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  fetchStub.mockReset()
  vi.stubGlobal('fetch', fetchStub)
  document.cookie = 'XSRF-TOKEN=jeton-ttt'
})
afterEach(() => vi.unstubAllGlobals())

test('envoie la liste des coups avec le cookie de session et le jeton CSRF du socle', async () => {
  fetchStub.mockResolvedValue(response(X_WON))
  await expect(verifyMatch(X_WINNING_MOVES)).resolves.toEqual(X_WON)
  expect(fetchStub).toHaveBeenCalledWith('/api/games/tic-tac-toe/verify', {
    credentials: 'same-origin',
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': 'jeton-ttt' },
    body: JSON.stringify({ moves: X_WINNING_MOVES }),
  })
})

test.each([400, 403, 422, 429, 502, 503])('conserve le statut %i et le message du serveur', async (status) => {
  fetchStub.mockResolvedValue(response({ error: 'Partie refusée.' }, status))
  await expect(verifyMatch(CHEATED_MOVES)).rejects.toMatchObject({ status, message: 'Partie refusée.' })
})
