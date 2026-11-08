// Charge ciblée de l'endpoint de vérification du Tic-Tac-Toe (POST /api/games/tic-tac-toe/verify) :
// le serveur rejoue chaque partie et archive le résultat. Environnement local de quality/docker-compose.yml,
// jamais la production.
//   docker compose -f quality/docker-compose.yml run --rm k6 run /quality/k6/tic-tac-toe.js
// Le backend doit tourner sur la machine (port 8080) ; TIKI_TICTACTOE_MAXVERIFICATIONSPERMINUTE relevé en CI
// (k6 envoie depuis une seule adresse, comme pour Rush Hour).
// Seuils = SLO de docs/slo.md : P95 < 300 ms, P99 < 500 ms, moins de 1 % d'erreurs.
import { check } from 'k6'

import exec from 'k6/execution'

import http from 'k6/http'

const BACKEND_URL = __ENV.BACKEND_URL || 'http://host.docker.internal:8080'

const RATE = Number(__ENV.TIC_TAC_TOE_RATE || 10)

export const options = {
  summaryTrendStats: ['avg', 'med', 'max', 'p(95)', 'p(99)'],

  scenarios: {
    tic_tac_toe_verify: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 10,
      maxVUs: 100,
    },
  },
  thresholds: {
    'http_req_duration{scenario:tic_tac_toe_verify}': ['p(95)<300', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
}

// Parties terminées légales, parcourues dans l'ordre : charge reproductible, sans aléatoire.
// prettier-ignore
const GAMES = [
  // X gagne en 5 coups (ligne du haut)
  { moves: [{ row: 0, column: 0 }, { row: 1, column: 0 }, { row: 0, column: 1 }, { row: 1, column: 1 }, { row: 0, column: 2 }] },
  // O gagne en 6 coups (ligne du milieu)
  { moves: [{ row: 0, column: 0 }, { row: 1, column: 0 }, { row: 0, column: 1 }, { row: 1, column: 1 }, { row: 2, column: 0 }, { row: 1, column: 2 }] },
  // Match nul en 9 coups
  { moves: [{ row: 0, column: 0 }, { row: 0, column: 1 }, { row: 0, column: 2 }, { row: 1, column: 1 }, { row: 1, column: 0 }, { row: 1, column: 2 }, { row: 2, column: 1 }, { row: 2, column: 0 }, { row: 2, column: 2 }] },
]

// Jeton CSRF : lu dans le cookie que le backend pose à la visite (k6 vide les cookies entre deux itérations).
function csrfToken() {
  const jar = http.cookieJar()
  let token = jar.cookiesForURL(BACKEND_URL)['XSRF-TOKEN']?.[0]
  if (!token) {
    http.get(`${BACKEND_URL}/api/auth/session`, { tags: { name: 'GET /api/auth/session (CSRF)' } })
    token = jar.cookiesForURL(BACKEND_URL)['XSRF-TOKEN']?.[0]
  }
  return token
}

export default function () {
  const game = GAMES[exec.scenario.iterationInTest % GAMES.length]
  const res = http.post(`${BACKEND_URL}/api/games/tic-tac-toe/verify`, JSON.stringify(game), {
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
    tags: { name: 'POST /api/games/tic-tac-toe/verify' },
  })
  check(res, {
    'vérification : 200 et statut calculé par le serveur': (r) =>
      r.status === 200 && ['X_WON', 'O_WON', 'DRAW'].includes(r.json('status')),
  })
}

export function handleSummary(data) {
  const metric = data.metrics['http_req_duration{scenario:tic_tac_toe_verify}']?.values
  const text = [
    `Requêtes : ${data.metrics.http_reqs.values.count} (${data.metrics.http_reqs.values.rate.toFixed(1)}/s)`,
    `Erreurs  : ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)} %`,
    `P95 = ${metric?.['p(95)']?.toFixed(1)} ms   P99 = ${metric?.['p(99)']?.toFixed(1)} ms`,
  ].join('\n')
  return {
    stdout: `\n${text}\n`,
    '/quality/reports/k6-tic-tac-toe.json': JSON.stringify(data, null, 2),
  }
}
