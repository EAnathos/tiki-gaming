# Stratégie de tests du Tic-Tac-Toe

## Objectif

Cette stratégie couvre les risques du Tic-Tac-Toe au niveau le plus bas capable de
les détecter. Le domaine reste pur et en mémoire ; Playwright est réservé aux
parcours que seuls le navigateur et l'accessibilité peuvent valider.

## Risques et niveaux de test

| Risque                                                          | Gravité | Preuve                                         |
| --------------------------------------------------------------- | ------: | ---------------------------------------------- |
| Une case occupée est écrasée ou un joueur joue deux fois        |   Haute | Tests domaine Java/TypeScript et BDD           |
| Une ligne, colonne ou diagonale gagnante est mal détectée       |   Haute | Tests unitaires, Scenario Outline et mutation  |
| Un match nul ou un coup après la fin est mal géré               |   Haute | Tests domaine et BDD                           |
| Le score de session est perdu au redémarrage                    | Moyenne | Tests composant et E2E                         |
| La grille ou l'annonce de victoire est inutilisable au clavier  | Moyenne | Tests composant et audit axe-core E2E          |
| Le domaine diverge entre les implémentations Java et TypeScript | Moyenne | Suites de règles équivalentes et revue de code |
| Une partie truquée ou non terminée est acceptée par la vérification serveur | Haute | 422 : `TicTacToeVerifyServiceTest`, `TicTacToeControllerTest`, contrat Pact |
| L'archivage des résultats vérifiés est indisponible ou trop lent | Moyenne | 502 puis 503 (disjoncteur) : `TicTacToeResultsChaosTest` (Toxiproxy), `SupabaseTicTacToeResultsClientIT` (Testcontainers), limite de débit 429 |

## Techniques appliquées

- **Boîte noire** : partitions coup valide/occupé/invalide, partie active/terminée,
  victoire/nul, avec valeurs limites de coordonnées `-1`, `0`, `2` et `3`.
- **Boîte blanche** : couverture des branches de victoire, de changement de joueur,
  de grille pleine et de refus après fin.
- **TDD et AAA** : les tests du domaine sont déterministes, isolés et structurés
  Arrange/Act/Assert.
- **Mutation** : Stryker vérifie que les assertions détectent les modifications
  des règles du domaine ; le seuil est fixé à 75 %.
- **E2E** : deux parcours critiques couvrent victoire, score et replay ; l'audit
  axe-core couvre la page réellement rendue.

## Périmètre d'intégration

Le jeu local reste pur en mémoire, mais une **frontière serveur** existe pour la vérification anti-triche :
`POST /api/games/tic-tac-toe/verify` rejoue les coups envoyés par le front sur le moteur Java
(`TicTacToeGame`), refuse une partie truquée ou non terminée (422), limite le débit par client (429) et archive
le résultat vérifié dans `public.tic_tac_toe_results` (écriture `service_role` uniquement, RLS). Cette frontière
est couverte par chaque niveau manquant auparavant :

| Notion | Preuve | Commande / CI |
|--------|--------|---------------|
| Testcontainers (backend) | `SupabaseTicTacToeResultsClientIT` : vrai PostgreSQL + PostgREST, migrations rejouées, RLS et contraintes réelles | `mvn -Pintegration '-Dit.test=fr/tikigaming/games/tic_tac_toe/**/*IT' test-compile failsafe:integration-test failsafe:verify` (job CI `integration`) |
| Testcontainers (front) | `tests/integration/tic-tac-toe.test.ts` : table, RLS (anon/authenticated refusés), contraintes CHECK | `npm run test:integration` (job CI `integration`) |
| Pact | `tests/contract/tic-tac-toe.pact.test.ts` (consommateur) + `pacts/tiki-tic-tac-toe-frontend-tiki-tic-tac-toe-backend.json` + `TicTacToePactProviderTest` (fournisseur) | `npm run test:contract` puis `mvn test` (jobs CI `frontend`/`backend`) |
| k6 | `quality/k6/tic-tac-toe.js` : scénario `tic_tac_toe_verify`, seuils SLO P95 < 300 ms / P99 < 500 ms | job CI `performance` |
| Toxiproxy | `TicTacToeResultsChaosTest` : latence/coupure injectées entre le client et un faux PostgREST, timeout puis disjoncteur (503), refermeture | `mvn test` (ignoré sans Docker, exécuté en CI) |
| ZAP | scan baseline du groupe OpenAPI `/v3/api-docs/tic-tac-toe` + tests CSRF (403 sans jeton) dans `TicTacToeControllerTest` | job CI `dast` |

## Commandes de preuve

```bash
npm test -- --run src/games/tic-tac-toe
npm run test:contract
npm run rtm:check
npx playwright test e2e/tic-tac-toe.spec.ts --project=chromium
npm run lint
npm run build
npm run test:mutation -- --mutate src/games/tic-tac-toe/domain/game.ts
cd backend && mvn -B test -Dtest='TicTacToe*' && mvn -Pintegration '-Dit.test=fr/tikigaming/games/tic_tac_toe/**/*IT' test-compile failsafe:integration-test failsafe:verify
```
