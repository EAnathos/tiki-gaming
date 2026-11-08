import { expect, test } from 'vitest'
import { as, asServer, describeDb, useDatabase } from './db'

// Table d'archivage des parties de Tic-Tac-Toe vérifiées par le backend (POST /api/games/tic-tac-toe/verify) :
// écrite et lisible uniquement en service_role, la RLS bloque anon et authenticated.
describeDb('table tic_tac_toe_results (RLS)', () => {
  const db = useDatabase()

  test('existe avec les colonnes attendues', async () => {
    const { rows } = await db.query(
      `select column_name from information_schema.columns
       where table_schema = 'public' and table_name = 'tic_tac_toe_results' order by ordinal_position`,
    )
    expect(rows.map((r) => r.column_name)).toEqual(['id', 'status', 'moves', 'created_at'])
  })

  test('un visiteur ne peut ni lire ni écrire', async () => {
    await expect(as(db, null, () => db.query('select count(*) from public.tic_tac_toe_results'))).rejects.toThrow(
      /permission denied/,
    )
    await expect(
      as(db, null, () => db.query(`insert into public.tic_tac_toe_results (status, moves) values ('X_WON', 5)`)),
    ).rejects.toThrow(/permission denied/)
  })

  test('un joueur connecté ne peut ni lire ni écrire', async () => {
    const userId = crypto.randomUUID()
    await expect(as(db, userId, () => db.query('select count(*) from public.tic_tac_toe_results'))).rejects.toThrow(
      /permission denied/,
    )
    await expect(
      as(db, userId, () => db.query(`insert into public.tic_tac_toe_results (status, moves) values ('DRAW', 9)`)),
    ).rejects.toThrow(/permission denied/)
  })

  test('le backend (service_role) archive et relit un résultat vérifié', async () => {
    await asServer(db, () => db.query(`insert into public.tic_tac_toe_results (status, moves) values ('X_WON', 5)`))
    const { rows } = await asServer(db, () => db.query('select status, moves from public.tic_tac_toe_results'))
    expect(rows).toEqual([{ status: 'X_WON', moves: 5 }])
  })

  test('la contrainte n’accepte qu’une partie terminée de 1 à 9 coups', async () => {
    await expect(
      asServer(db, () => db.query(`insert into public.tic_tac_toe_results (status, moves) values ('IN_PROGRESS', 3)`)),
    ).rejects.toThrow(/tic_tac_toe_results_status_check/)
    await expect(
      asServer(db, () => db.query(`insert into public.tic_tac_toe_results (status, moves) values ('DRAW', 10)`)),
    ).rejects.toThrow(/tic_tac_toe_results_moves_check/)
    await expect(
      asServer(db, () => db.query(`insert into public.tic_tac_toe_results (status, moves) values ('X_WON', 0)`)),
    ).rejects.toThrow(/tic_tac_toe_results_moves_check/)
  })
})
