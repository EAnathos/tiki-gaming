# Qualité du Tic-Tac-Toe

## Périmètre

Les mesures portent sur le domaine TypeScript pur du Tic-Tac-Toe, ses scénarios
Vitest-Cucumber et le client d'appel à l'API de vérification (`api.ts`, testé avec un `fetch` simulé).
Aucun navigateur, réseau, stockage ou service externe n'est utilisé dans ces tests unitaires.

Les niveaux supérieurs sont couverts ailleurs (voir [strategie-tests.md](strategie-tests.md)) : contrat Pact
(`tests/contract/tic-tac-toe.pact.test.ts`), intégration base (`tests/integration/tic-tac-toe.test.ts`,
Testcontainers), HTTP/CSRF/rate-limit côté backend (`TicTacToeControllerTest`), chaos Toxiproxy
(`TicTacToeResultsChaosTest`), IT Testcontainers (`SupabaseTicTacToeResultsClientIT`), charge k6
(`quality/k6/tic-tac-toe.js`) et DAST ZAP (groupe `/v3/api-docs/tic-tac-toe`).

## Résultats

Commande de couverture ciblée (domaine + client API) :

```bash
npx vitest run src/games/tic-tac-toe --coverage \
  --coverage.include='src/games/tic-tac-toe/**' \
  --coverage.thresholds.lines=0 --coverage.thresholds.functions=0 \
  --coverage.thresholds.statements=0 --coverage.thresholds.branches=0
```

| Mesure | Résultat | Objectif |
|---|---:|---:|
| Tests du jeu (BDD 22 + composant 4 + api 7) | 33/33 | 100 % |
| Lignes | 95,65 % (44/46) | >= 80 % |
| Fonctions | 96,15 % (25/26) | >= 80 % |
| Instructions | 94,82 % (55/58) | >= 80 % |
| Branches | 85,71 % (48/56) | >= 80 % |

Couverture du seul domaine (BDD) :

```bash
npx vitest run src/games/tic-tac-toe/features/tictactoe.steps.test.ts --coverage \
  --coverage.include=src/games/tic-tac-toe/domain/game.ts \
  --coverage.thresholds.lines=0 --coverage.thresholds.functions=0 \
  --coverage.thresholds.statements=0 --coverage.thresholds.branches=0
```

| Mesure | Résultat | Objectif |
|---|---:|---:|
| Tests BDD | 22/22 | 100 % |
| Lignes | 100 % (22/22) | >= 80 % |
| Fonctions | 100 % (14/14) | >= 80 % |
| Instructions | 96,77 % (30/31) | >= 80 % |
| Branches | 89,28 % (25/28) | >= 80 % |

Commande de mutation :

```bash
npm run test:mutation -- --mutate src/games/tic-tac-toe/domain/game.ts
```

Stryker a analysé 115 mutants : 111 tués, 0 survivant, 4 non couverts, soit
un score de mutation de **96,52 %**, supérieur au seuil minimal de 75 %.

Les mutants non couverts concernent des chemins déjà impossibles à atteindre
depuis les scénarios sélectionnés ; aucun mutant survivant n'est accepté sur le
domaine. Les messages d'erreur d'occupation et de coordonnées sont vérifiés
explicitement par les step definitions BDD.

## Commandes de qualité

Les contrôles généraux du frontend passent avec :

```bash
npm run lint
npm run build
```

La couverture globale du frontend doit être exécutée avec `npm run test:coverage`
sur la suite complète ; une exécution limitée au seul fichier BDD ne peut pas
respecter les seuils globaux du projet.
